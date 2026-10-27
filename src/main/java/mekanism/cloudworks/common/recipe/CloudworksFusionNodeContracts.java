package mekanism.cloudworks.common.recipe;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.common.registry.*;
import mekanism.cloudworks.common.service.CloudworksNativeContracts;
import mekanism.common.config.MekanismConfig;
import mekanism.common.recipe.RecipeHandler;
import net.minecraft.item.ItemStack;

/** Owner-frozen variants share the native/CRT fusion construction, with one injection setting per node. */
public final class CloudworksFusionNodeContracts {
    private static final Map<String, Variant> VARIANTS = new ConcurrentHashMap<>();
    private CloudworksFusionNodeContracts() {}
    public static boolean isController(ItemStack machine) {
        return !machine.isEmpty() && CloudworksMachineAdapters.getAll(machine).stream()
                .anyMatch(adapter -> adapter instanceof CloudworksFusionControllerAdapter);
    }
    public static int maximumInjection() {
        int maximum = MekanismConfig.current().generators == null ? 98
                : Math.max(2, MekanismConfig.current().generators.reactorGeneratorInjectionRate.val());
        return maximum - maximum % 2;
    }
    public static void validateInjection(int injection) {
        if (injection < 0 || (injection & 1) != 0 || injection > maximumInjection())
            throw new IllegalArgumentException("cloudworks.error.fusion_injection_invalid");
    }
    static int capacity(int base, int injection, int maximum) {
        int capped = Math.min(Math.min(Math.max(1, injection), maximum), 1000);
        int multiplier = Math.max(0, capped - capped % 2);
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, base) * multiplier);
    }
    public static MachineSlotLayout slots(int injection) {
        MachineSlotLayout layout = new MachineSlotLayout()
                .add("fuel_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT,
                        Math.max(1, MekanismConfig.current().generators.FusionReactorsFuelTank.val()))
                .add("deuterium_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT,
                        Math.max(1, MekanismConfig.current().generators.FusionReactorsDeuteriumTank.val()))
                .add("tritium_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT,
                        Math.max(1, MekanismConfig.current().generators.FusionReactorsTritiumTank.val()));
        int maximum = MekanismConfig.current().generators.reactorGeneratorInjectionRate.val();
        int water = capacity(MekanismConfig.current().generators.FusionReactorsWaterTank.val(), injection, maximum);
        int steam = capacity(MekanismConfig.current().generators.FusionReactorsSteamTank.val(), injection, maximum);
        // A formed native reactor at injection zero has zero-capacity water/steam tanks.
        // Zero-capacity CloudWorks ports are represented by their absence.
        if (water > 0) layout.add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, water);
        if (steam > 0) layout.add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, steam);
        return CloudworksThermalPlan.ports(layout).frozenCopy();
    }
    public static Variant resolve(String identity, ItemStack machine, String mode, int injection) {
        boolean cooled = "cloudworks:fusion_water".equals(mode);
        if (!mode.isEmpty() && !cooled && !"cloudworks:fusion_dry".equals(mode))
            throw new IllegalArgumentException("cloudworks.error.recipe_type");
        String key = identity + "|fusion=" + injection + "|cooled=" + cooled + "|v=" + RecipeHandler.getGlobalRecipeVersion();
        Variant cached = VARIANTS.get(key);
        if (cached != null && CloudworksAsyncRecipeAdmission.peek(cached.adapter).isPresent()) return cached;
        if (CloudworksNativeContracts.isWorker()) throw new IllegalStateException("Fusion injection variant was not frozen before dispatch");
        validateInjection(injection);
        CloudworksMachineAdapter adapter = new CloudworksFusionReactorRecipeAdapter(Math.max(2, injection), cooled);
        CloudworksThermalPlan thermal = adapter.thermalPlan().withRate(injection);
        MachineSlotLayout slots = slots(injection);
        CloudworksMachineRegistration registration = new CloudworksMachineRegistration(machine, CloudworksRegistries.profile(machine),
                CloudworksRegistries.identityPolicy(machine), slots, mode);
        CloudworksMachineAdapters.registerDerived(adapter, registration);
        CloudworksAsyncRecipeAdmission.admitDerived(adapter, registration);
        Variant result = new Variant(adapter, thermal, slots); VARIANTS.put(key, result); return result;
    }
    public static final class Variant {
        public final CloudworksMachineAdapter adapter;
        public final CloudworksThermalPlan thermal;
        public final MachineSlotLayout slots;
        private Variant(CloudworksMachineAdapter adapter, CloudworksThermalPlan thermal, MachineSlotLayout slots) {
            this.adapter = adapter; this.thermal = thermal; this.slots = slots;
        }
    }
}
