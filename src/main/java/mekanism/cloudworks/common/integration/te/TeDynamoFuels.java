package mekanism.cloudworks.common.integration.te;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import cofh.core.inventory.ComparableItemStack;
import cofh.thermalexpansion.util.managers.dynamo.CompressionManager;
import cofh.thermalexpansion.util.managers.dynamo.EnervationManager;
import cofh.thermalexpansion.util.managers.dynamo.MagmaticManager;
import cofh.thermalexpansion.util.managers.dynamo.NumismaticManager;
import cofh.thermalexpansion.util.managers.dynamo.ReactantManager;
import cofh.thermalexpansion.util.managers.dynamo.SteamManager;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.common.recipe.CloudworksRecipeBatch;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import cofh.thermalexpansion.util.managers.device.CoolantManager;
import java.util.LinkedHashMap;
import java.util.Set;
import mekanism.common.Mekanism;

/**
 * Fuel-contract adapters for the six Thermal Expansion dynamos. Every entry is
 * strict conservation: the fuel burns for exactly its live manager energy
 * (converted with FROM_RF) plus its side stream (steam water, compression
 * coolant) or discharge form (enervation containers). Fuel entries burn per
 * machine tick; the native maxPower pacing is not expressible on the current
 * contract and stays a pending ruling. All tables are enumerated live at
 * freeze — they are runtime data (parsers/IMC), never snapshot constants.
 */
final class TeDynamoFuels {

    /**
     * Steam dynamo water buffer: 25,000 fuel units per 100 mB input batch.
     */
    static final int STEAM_RF_PER_100_MB_WATER = 25_000;

    /**
     * Hard cap protecting one dynamo's fuel freeze.
     */
    static final int MAX_FUEL_ENTRIES = 10_000;

    /**
     * The reactant dynamo consumes 100 mB of fluid per reaction.
     */
    static final int REACTANT_FLUID_MB = 100;

    /**
     * One item x fluid reaction pair.
     */
    static final class ReactantContract {
        final ItemStack reactant;
        final Fluid fluid;
        final int energy;

        ReactantContract(ItemStack reactant, Fluid fluid, int energy) {
            this.reactant = reactant;
            this.fluid = fluid;
            this.energy = energy;
        }
    }

    private TeDynamoFuels() {
    }

    // -----------------------------------------------------------------------------------------
    // Production dispatch
    // -----------------------------------------------------------------------------------------

    static CloudworksMachineAdapter byMeta(int meta, double rfToJoules) {
        Supplier<Fluid> water = () -> FluidRegistry.WATER;
        return switch (meta) {
            case 0 -> steam(TeDynamoFuels::steamFuels,
                    SteamManager::getFuelEnergy, water, rfToJoules);
            case 1 -> magmatic(MagmaticManager::getFuels, MagmaticManager::getFuelEnergy, rfToJoules);
            case 2 -> compression(CompressionManager::getFuels, CompressionManager::getFuelEnergy,
                    water, CoolantManager::getCoolantRF100mB,
                    rfToJoules);
            case 3 -> reactant(() -> reactions(), rfToJoules);
            case 4 -> enervation(() -> comparableStacks(EnervationManager::getFuels),
                    EnervationManager::getFuelEnergy, TeDynamoFuels::dischargeables, rfToJoules);
            case 5 -> numismatic(() -> comparableStacks(NumismaticManager::getFuels),
                    NumismaticManager::getFuelEnergy, rfToJoules);
            default -> throw new IllegalArgumentException("Unknown Thermal Expansion dynamo meta " + meta);
        };
    }

    private static List<ItemStack> comparableStacks(Supplier<Set<ComparableItemStack>> set) {
        List<ItemStack> stacks = new ArrayList<>();
        Set<ComparableItemStack> fuels;
        try {
            fuels = set.get();
        } catch (RuntimeException | LinkageError unavailable) {
            return stacks;
        }
        if (fuels == null) return stacks;
        for (ComparableItemStack fuel : fuels) {
            if (fuel == null) continue;
            try {
                ItemStack stack = fuel.toItemStack();
                if (stack != null && !stack.isEmpty()) stacks.add(stack);
            } catch (RuntimeException | LinkageError ignored) {
                // One malformed third-party entry does not abort registration.
            }
        }
        return stacks;
    }

    /**
     * SteamManager delegates to the vanilla furnace fallback for fuels absent from its table.
     */
    private static List<ItemStack> steamFuels() {
        return steamFuels(() -> comparableStacks(SteamManager::getFuels), Item.REGISTRY, SteamManager::getFuelEnergy);
    }

    /** Test seam: the manager table union the furnace fallback scan, deduplicated. */
    static List<ItemStack> steamFuels(Supplier<List<ItemStack>> table, Iterable<Item> items,
                                      ToIntFunction<ItemStack> fuelEnergy) {
        Map<String, ItemStack> unique = new LinkedHashMap<>();
        List<ItemStack> listed = table.get();
        if (listed != null) for (ItemStack stack : listed)
            if (stack != null && !stack.isEmpty()) unique.put(fuelKey(stack), stack);
        if (items == null) return new ArrayList<>(unique.values());
        for (Item item : items) {
            if (item == null) continue;
            ItemStack stack = new ItemStack(item);
            if (safeEnergy(fuelEnergy, stack) > 0)
                unique.putIfAbsent(fuelKey(stack), stack);
        }
        return new ArrayList<>(unique.values());
    }

    private static String fuelKey(ItemStack stack) {
        return String.valueOf(stack.getItem().getRegistryName()) + ":" + stack.getMetadata();
    }

    private static List<ReactantContract> reactions() {
        ReactantManager.Reaction[] reactions = ReactantManager.getReactionList();
        List<ReactantContract> contracts = new ArrayList<>();
        if (reactions == null) return contracts;
        for (ReactantManager.Reaction reaction : reactions)
            if (reaction != null && reaction.getFluid() != null)
                contracts.add(new ReactantContract(reaction.getReactant(), reaction.getFluid(),
                        reaction.getEnergy()));
        return contracts;
    }

    /**
     * Enervation fallback: chargeable items that the manager table does not
     * already list freeze as full-to-empty discharge contracts (the charger
     * probe inverted); table items burn up entirely instead.
     */
    private static List<TeChargerAdapter.ChargeContract> dischargeables() {
        List<TeChargerAdapter.ChargeContract> contracts = new ArrayList<>();
        for (ItemStack stack : TeChargerAdapter.registeredItems()) {
            if (stack.isEmpty() || EnervationManager.getFuelEnergy(stack) > 0) continue;
            TeChargerAdapter.ChargeContract contract;
            try {
                contract = TeChargerAdapter.probeEnergy(stack);
            } catch (RuntimeException broken) {
                continue;
            }
            if (contract != null) contracts.add(contract);
        }
        return contracts;
    }

    // -----------------------------------------------------------------------------------------
    // Per-dynamo adapters (seams injectable for JVM tests)
    // -----------------------------------------------------------------------------------------

    /**
     * Steam: one fuel burn consumes as many complete native 100 mB water batches as needed.
     */
    static CloudworksMachineAdapter steam(Supplier<List<ItemStack>> fuels,
                                          ToIntFunction<ItemStack> fuelEnergy, Supplier<Fluid> water, double rfToJoules) {
        return freeze("te_dynamo_steam", () -> {
            List<CloudworksRecipeBatch> batches = new ArrayList<>();
            Fluid waterFluid = water.get();
            if (waterFluid == null) return batches;
            for (ItemStack fuel : nonEmpty(fuels)) {
                int rf = safeEnergy(fuelEnergy, fuel);
                if (rf <= 0) continue;
                int waterAmount = fluidAmountForBatchedEnergy(rf, STEAM_RF_PER_100_MB_WATER);
                if (waterAmount <= 0) continue;
                batches.add(itemFuel(fuel, rf, rfToJoules).fluid(new FluidStack(waterFluid, waterAmount)));
            }
            return batches;
        });
    }

    /**
     * Magmatic: hot fluids by the bucket.
     */
    static CloudworksMachineAdapter magmatic(Supplier<Set<String>> fluidNames,
                                             ToIntFunction<FluidStack> energyPerBucket, double rfToJoules) {
        return freeze("te_dynamo_magmatic", () -> {
            List<CloudworksRecipeBatch> batches = new ArrayList<>();
            for (Fluid fluid : fluids(fluidNames)) {
                int rf = safeEnergy(energyPerBucket, new FluidStack(fluid, 1000));
                if (rf <= 0) continue;
                batches.add(fluidFuel(fluid, 1000, rf, rfToJoules));
            }
            return batches;
        });
    }

    /**
     * Compression: fuel fluid plus its water coolant cost.
     */
    static CloudworksMachineAdapter compression(Supplier<Set<String>> fluidNames,
                                                ToIntFunction<FluidStack> energyPerBucket, Supplier<Fluid> coolant,
                                                ToIntFunction<Fluid> coolantRF100, double rfToJoules) {
        return freeze("te_dynamo_compression", () -> {
            List<CloudworksRecipeBatch> batches = new ArrayList<>();
            for (Fluid fluid : fluids(fluidNames)) {
                int rf = safeEnergy(energyPerBucket, new FluidStack(fluid, 1000));
                if (rf <= 0) continue;
                int rf100 = coolantRF100.applyAsInt(coolant.get());
                Fluid coolantFluid = coolant.get();
                if (rf100 <= 0 || coolantFluid == null) continue;
                int coolantAmount = fluidAmountForBatchedEnergy(rf, rf100);
                if (coolantAmount <= 0) continue;
                batches.add(fluidFuel(fluid, 1000, rf, rfToJoules)
                        .fluid(new FluidStack(coolantFluid, coolantAmount)));
            }
            return batches;
        });
    }

    /**
     * Reactant: item x fluid pairs, 100 mB per reaction.
     */
    static CloudworksMachineAdapter reactant(Supplier<List<ReactantContract>> reactions, double rfToJoules) {
        return freeze("te_dynamo_reactant", () -> {
            List<CloudworksRecipeBatch> batches = new ArrayList<>();
            for (ReactantContract reaction : safe(reactions)) {
                if (reaction == null || reaction.reactant.isEmpty() || reaction.fluid == null
                        || reaction.energy <= 0) continue;
                batches.add(itemFuel(reaction.reactant, reaction.energy, rfToJoules)
                        .fluid(new FluidStack(reaction.fluid, REACTANT_FLUID_MB)));
            }
            return batches;
        });
    }

    /**
     * Enervation: table items burn up; chargeables discharge full-to-empty.
     */
    static CloudworksMachineAdapter enervation(Supplier<List<ItemStack>> fuels,
                                               ToIntFunction<ItemStack> fuelEnergy, Supplier<List<TeChargerAdapter.ChargeContract>> dischargeable,
                                               double rfToJoules) {
        return freeze("te_dynamo_enervation", () -> {
            List<CloudworksRecipeBatch> batches = new ArrayList<>();
            for (ItemStack fuel : nonEmpty(fuels)) {
                int rf = safeEnergy(fuelEnergy, fuel);
                if (rf <= 0) continue;
                batches.add(itemFuel(fuel, rf, rfToJoules));
            }
            for (TeChargerAdapter.ChargeContract contract : safe(dischargeable)) {
                if (contract == null || contract.joules() <= 0D) continue;
                batches.add(new CloudworksRecipeBatch()
                        .item(contract.full())
                        .output(contract.empty())
                        .energy(contract.joules()));
            }
            return batches;
        });
    }

    /**
     * Numismatic: the coin table (the gem subset rides the pending @720 mode).
     */
    static CloudworksMachineAdapter numismatic(Supplier<List<ItemStack>> coins,
                                               ToIntFunction<ItemStack> fuelEnergy, double rfToJoules) {
        return freeze("te_dynamo_numismatic", () -> {
            List<CloudworksRecipeBatch> batches = new ArrayList<>();
            for (ItemStack coin : nonEmpty(coins)) {
                int rf = safeEnergy(fuelEnergy, coin);
                if (rf <= 0) continue;
                batches.add(itemFuel(coin, rf, rfToJoules));
            }
            return batches;
        });
    }

    // -----------------------------------------------------------------------------------------
    // Shared plumbing
    // -----------------------------------------------------------------------------------------

    private static CloudworksMachineAdapter freeze(String idPrefix, Supplier<List<CloudworksRecipeBatch>> batches) {
        return registration -> freezeFuels(idPrefix, batches);
    }

    static List<CloudworksRecipeSpec> freezeFuels(String idPrefix, Supplier<List<CloudworksRecipeBatch>> batches) {
        List<CloudworksRecipeBatch> list = batches.get();
        if (list == null) return Collections.emptyList();
        List<CloudworksRecipeSpec> result = new ArrayList<>();
        boolean truncated = false;
        for (CloudworksRecipeBatch batch : list) {
            if (result.size() >= MAX_FUEL_ENTRIES) {
                truncated = true;
                break;
            }
            if (batch == null) continue;
            try {
                // Fuel entries settle per machine tick; duration is inert here.
                result.add(streamSideInput(batch.specification(idPrefix + "_" + result.size(), 1)));
            } catch (IllegalArgumentException unsupported) {
                // One unfreezable fuel rejects only itself.
            }
        }
        if (truncated && Mekanism.logger != null)
            Mekanism.logger.warn(
                    "Cloudworks Thermal Expansion dynamo fuel table '{}' exceeded {}; remaining entries skipped",
                    idPrefix, MAX_FUEL_ENTRIES);
        return Collections.unmodifiableList(result);
    }

    /** Large native burns refill their 4,000 mB tank while fuel credit remains. */
    static CloudworksRecipeSpec streamSideInput(CloudworksRecipeSpec recipe) {
        if (recipe.inputs().size() != 2 || !recipe.chanceOutputs().isEmpty() || !recipe.choiceOutputs().isEmpty()) return recipe;
        CloudworksRecipeSpec.Ingredient primary = recipe.inputs().get(0), side = recipe.inputs().get(1);
        if (side.amount() <= CloudworksTeDynamos.DYNAMO_FLUID_PORT_CAPACITY
                || primary.matcher().singleResource() == null
                || side.matcher().alternatives().size() != 1
                || !QIOResourceCodecs.FLUID_STACK_ID.equals(side.matcher().alternatives().get(0).getCodecId())) return recipe;
        long units = side.amount();
        for (CloudworksRecipeSpec.Output output : recipe.outputs()) units = gcd(units, output.amount());
        if (recipe.energyPerOperation() > 0 && side.amount() % 100 == 0) units = side.amount() / 100;
        CloudworksRecipeSpec.Builder builder = CloudworksRecipeSpec.builder(recipe.id()).duration(1)
                .input(primary.matcher().singleResource(), primary.amount(), primary.port())
                .matcher(side.matcher(), side.amount() / units, side.port())
                .prepaidFuel(primary.matcher().singleResource(), units);
        if (recipe.energyPerOperation() > 0) builder.energyPerOperation(recipe.energyPerOperation() / units);
        for (CloudworksRecipeSpec.Output output : recipe.outputs())
            builder.unscaledOutput(output.descriptor(), output.amount() / units, output.port());
        return builder.build();
    }

    private static long gcd(long a, long b) {
        while (b != 0) { long next = a % b; a = b; b = next; }
        return a;
    }

    private static CloudworksRecipeBatch itemFuel(ItemStack fuel, int rf, double rfToJoules) {
        return new CloudworksRecipeBatch().item(fuel).energy(rf * rfToJoules);
    }

    private static CloudworksRecipeBatch fluidFuel(Fluid fluid, int amount, int rf, double rfToJoules) {
        return new CloudworksRecipeBatch().fluid(new FluidStack(fluid, amount)).energy(rf * rfToJoules);
    }

    /**
     * Converts an RF budget into the full 100 mB batches actually drained by TE.
     */
    static int fluidAmountForBatchedEnergy(double energy, int energyPer100Mb) {
        if (!Double.isFinite(energy) || energy <= 0D || energyPer100Mb <= 0) return 0;
        double amount = Math.ceil(energy / energyPer100Mb) * 100D;
        return !Double.isFinite(amount) || amount > Integer.MAX_VALUE ? 0 : (int) amount;
    }

    private static List<ItemStack> nonEmpty(Supplier<List<ItemStack>> source) {
        List<ItemStack> stacks = source.get();
        if (stacks == null) return Collections.emptyList();
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : stacks)
            if (stack != null && !stack.isEmpty()) result.add(stack);
        return result;
    }

    private static <T> List<T> safe(Supplier<List<T>> source) {
        List<T> list = source.get();
        return list == null ? Collections.emptyList() : list;
    }

    private static List<Fluid> fluids(Supplier<Set<String>> names) {
        Set<String> set;
        try {
            set = names.get();
        } catch (RuntimeException | LinkageError unavailable) {
            return Collections.emptyList();
        }
        if (set == null) return Collections.emptyList();
        List<Fluid> fluids = new ArrayList<>();
        for (String name : set) {
            Fluid fluid = FluidRegistry.getFluid(name);
            if (fluid != null) fluids.add(fluid);
        }
        return fluids;
    }

    private static <T> int safeEnergy(ToIntFunction<T> function, T value) {
        try {
            return function.applyAsInt(value);
        } catch (RuntimeException | LinkageError unavailable) {
            return 0;
        }
    }
}
