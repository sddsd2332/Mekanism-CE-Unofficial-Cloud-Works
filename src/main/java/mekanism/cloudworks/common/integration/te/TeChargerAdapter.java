package mekanism.cloudworks.common.integration.te;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import cofh.redstoneflux.api.IEnergyContainerItem;
import mekanism.api.Action;
import mekanism.api.energy.IStrictEnergyHandler;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.CloudworksMachineRegistration;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;
import mekanism.cloudworks.common.recipe.CloudworksRecipeBatch;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.integration.forgeenergy.ForgeEnergyIntegration;
import mekanism.common.integration.ic2.IC2Integration;
import mekanism.common.integration.redstoneflux.RFIntegration;
import mekanism.common.integration.tesla.TeslaIntegration;
import mekanism.common.util.MekanismUtils;
import net.darkhax.tesla.api.ITeslaConsumer;
import net.darkhax.tesla.api.ITeslaProducer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fml.common.registry.GameRegistry;

/**
 * The charger's universal energy line: every chargeable item freezes as one
 * empty-to-full recipe. The empty input and full output are canonical NBT
 * forms built by executing the item's own energy system (extract to empty,
 * receive to full), so identical exhausted batteries stack into one cloud
 * resource. Accepted simplifications (user-ruled): half-charged states never
 * match, unusable items skip themselves, and discharge (full-to-empty) is not
 * modelled.
 *
 * <p>The probe and candidate list are injected so JVM tests drive the freeze
 * with substitutes; the production probe mirrors {@code ChargeUtils.charge}'s
 * system order and runs only during the lifecycle freeze.</p>
 */
final class TeChargerAdapter implements CloudworksMachineAdapter {

    /** A big finite transfer amount for the execute-mode fill/drain probes. */
    private static final double BIG_JOULES = 1.0E15D;

    /** Hard cap protecting the freeze from pathological registries. */
    static final int MAX_ENERGY_RECIPES = 10_000;

    /** One empty->full charging contract for one item. */
    static final class ChargeContract {
        final ItemStack empty;
        final ItemStack full;
        final double joules;

        ChargeContract(ItemStack empty, ItemStack full, double joules) {
            this.empty = empty;
            this.full = full;
            this.joules = joules;
        }

        ItemStack empty() { return empty; }
        ItemStack full() { return full; }
        double joules() { return joules; }
    }

    private final String idPrefix;
    private final Supplier<List<ItemStack>> candidates;
    private final Function<ItemStack, ChargeContract> probe;
    private final double basePowerRf;
    private final double speed;
    private final double rfToJoules;

    TeChargerAdapter(String idPrefix, Supplier<List<ItemStack>> candidates,
            Function<ItemStack, ChargeContract> probe, double basePowerRf, double speed, double rfToJoules) {
        this.idPrefix = idPrefix;
        this.candidates = candidates;
        this.probe = probe;
        this.basePowerRf = basePowerRf;
        this.speed = speed;
        this.rfToJoules = rfToJoules;
    }

    String idPrefix() {
        return idPrefix;
    }

    @Override
    public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
        List<ItemStack> items = candidates.get();
        if (items == null) return Collections.emptyList();
        List<CloudworksRecipeSpec> result = new ArrayList<>();
        for (ItemStack candidate : items) {
            if (candidate == null || candidate.isEmpty()) continue;
            if (result.size() >= MAX_ENERGY_RECIPES) break;
            ChargeContract contract;
            try {
                contract = probe.apply(candidate);
            } catch (RuntimeException broken) {
                // A broken energy item skips only itself.
                continue;
            }
            if (contract == null || contract.joules() <= 0D) continue;
            try {
                result.add(new CloudworksRecipeBatch()
                        .item(contract.empty())
                        .output(contract.full())
                        .specification(idPrefix + "_" + result.size(), durationTicks(contract)));
            } catch (IllegalArgumentException unsupported) {
                // An unfreezable NBT form rejects only itself.
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Fixed-rate duration: the charger runs its full power at the item. */
    private int durationTicks(ChargeContract contract) {
        double rf = contract.joules() / rfToJoules;
        return (int) Math.max(1L, Math.ceil(rf / (basePowerRf * speed)));
    }

    // -----------------------------------------------------------------------------------------
    // Production probe (lifecycle freeze only)
    // -----------------------------------------------------------------------------------------

    /** All registered items at meta zero; the canonical upload form. */
    static List<ItemStack> registeredItems() {
        List<ItemStack> items = new ArrayList<>();
        for (Item item : GameRegistry.findRegistry(Item.class).getValues()) items.add(new ItemStack(item));
        return items;
    }

    /**
     * Mirrors {@code ChargeUtils.charge}'s system order: strict energy, then
     * Tesla, Forge Energy, RF and IC2 behind their config gates. Returns the
     * canonical empty/full pair plus the measured inserted joules, or null
     * when the item accepts no energy.
     */
    static ChargeContract probeEnergy(ItemStack stack) {
        ItemStack full = stack.copy();
        double inserted = fill(full);
        if (inserted <= 0D) return null;
        ItemStack empty = stack.copy();
        drain(empty);
        if (ItemStack.areItemStacksEqual(empty, full)) return null;
        return new ChargeContract(empty, full, inserted);
    }

    private static double fill(ItemStack stack) {
        if (stack.hasCapability(Capabilities.STRICT_ENERGY_CAPABILITY, null)) {
            IStrictEnergyHandler handler = stack.getCapability(Capabilities.STRICT_ENERGY_CAPABILITY, null);
            if (handler != null && handler.getEnergyContainerCount() > 0) {
                return BIG_JOULES - handler.insertEnergy(BIG_JOULES, Action.EXECUTE);
            }
        }
        if (MekanismUtils.useTesla() && stack.hasCapability(Capabilities.TESLA_CONSUMER_CAPABILITY, null)) {
            ITeslaConsumer consumer = stack.getCapability(Capabilities.TESLA_CONSUMER_CAPABILITY, null);
            if (consumer != null) {
                return TeslaIntegration.fromTesla(consumer.givePower(Long.MAX_VALUE / 2, false));
            }
        }
        if (MekanismUtils.useForge() && stack.hasCapability(CapabilityEnergy.ENERGY, null)) {
            IEnergyStorage storage = stack.getCapability(CapabilityEnergy.ENERGY, null);
            if (storage != null && storage.canReceive()) {
                return ForgeEnergyIntegration.fromForge(storage.receiveEnergy(Integer.MAX_VALUE, false));
            }
        }
        if (MekanismUtils.useRF() && stack.getItem() instanceof IEnergyContainerItem item) {
            return RFIntegration.fromRF(item.receiveEnergy(stack, Integer.MAX_VALUE, false));
        }
        if (MekanismUtils.useIC2()) {
            return IC2Integration.fromEU(IC2Integration.chargeItem(stack, Integer.MAX_VALUE, true, false));
        }
        return 0D;
    }

    private static void drain(ItemStack stack) {
        if (stack.hasCapability(Capabilities.STRICT_ENERGY_CAPABILITY, null)) {
            IStrictEnergyHandler handler = stack.getCapability(Capabilities.STRICT_ENERGY_CAPABILITY, null);
            if (handler != null && handler.getEnergyContainerCount() > 0) {
                handler.extractEnergy(BIG_JOULES, Action.EXECUTE);
                return;
            }
        }
        if (MekanismUtils.useTesla() && stack.hasCapability(Capabilities.TESLA_PRODUCER_CAPABILITY, null)) {
            ITeslaProducer producer = stack.getCapability(Capabilities.TESLA_PRODUCER_CAPABILITY, null);
            if (producer != null) {
                producer.takePower(Long.MAX_VALUE / 2, false);
                return;
            }
        }
        if (MekanismUtils.useForge() && stack.hasCapability(CapabilityEnergy.ENERGY, null)) {
            IEnergyStorage storage = stack.getCapability(CapabilityEnergy.ENERGY, null);
            if (storage != null) {
                storage.extractEnergy(Integer.MAX_VALUE, false);
                return;
            }
        }
        if (MekanismUtils.useRF() && stack.getItem() instanceof IEnergyContainerItem item) {
            item.extractEnergy(stack, Integer.MAX_VALUE, false);
            return;
        }
        if (MekanismUtils.useIC2()) {
            IC2Integration.dischargeItemToMekanism(stack, Integer.MAX_VALUE, true, false);
        }
    }
}
