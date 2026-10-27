package mekanism.cloudworks.common.integration;

import java.util.Collections;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.machine.CloudworksThermalContracts;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.common.recipe.*;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.machine.CloudworksMachineDefinition;
import mekanism.cloudworks.api.recipe.CloudworksRecipeMode;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.cloudworks.api.machine.MachineIdentityPolicy;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.api.machine.MachineSlotLayout.PortRole;
import mekanism.common.recipe.RecipeHandler;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;
import mekanism.cloudworks.api.module.MachineUpgradePolicy;

/** Unbound multiblock types. Only explicit script/addon registration associates an item. */
public final class CloudworksMultiblocks {
    private CloudworksMultiblocks() {}

    public static String[] types() {
        return new String[]{"fusion", "turbine", "evaporation", "boiler", "fission_water", "fission_sodium", "sps"};
    }

    public static void evaporation(ItemStack machine, int parallel, int ticks, int input, int output) {
        bind(machine, parallel, ticks, 0, new MachineSlotLayout()
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, PortRole.INPUT, input)
                .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, PortRole.OUTPUT, output),
                new CloudworksThermalEvaporationRecipeAdapter(RecipeHandler.Recipe.THERMAL_EVAPORATION_PLANT.get()),
                CloudworksThermalContracts.evaporation());
    }

    public static void sps(ItemStack machine, int ticks, double capacity) {
        sps(machine, ticks, capacity, MachineIdentityPolicy.exactNbt());
    }
    public static void sps(ItemStack machine, int ticks, double capacity, MachineIdentityPolicy identity) {
        positiveTicks(ticks);
        MachineSlotLayout layout = new MachineSlotLayout()
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, PortRole.INPUT, 1000)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, PortRole.OUTPUT, 1000);
        CloudworksSPSRecipeAdapter adapter = new CloudworksSPSRecipeAdapter(ticks);
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(machine,
                new MachineExecutionProfile(1, 0, capacity, 1, MachineUpgradePolicy.NONE), layout, identity,
                Collections.singletonList(new CloudworksRecipeMode("cloudworks:processing", 0, null, adapter))));
    }

    public static void boiler(ItemStack machine, int water, int ticks, int fluidCapacity, int gasCapacity) {
        bind(machine, 1, ticks, 0, new MachineSlotLayout()
                .add("water_input", QIOResourceCodecs.FLUID_STACK_ID, PortRole.INPUT, fluidCapacity)
                .add("heated_coolant_input", QIOResourceCodecs.GAS_STACK_ID, PortRole.INPUT, gasCapacity)
                .add("steam_output", QIOResourceCodecs.FLUID_STACK_ID, PortRole.OUTPUT, fluidCapacity)
                .add("cooled_coolant_output", QIOResourceCodecs.GAS_STACK_ID, PortRole.OUTPUT, gasCapacity),
                new CloudworksBoilerRecipeAdapter(water, ticks), CloudworksThermalContracts.boiler(water, ticks));
    }

    public static void fission(ItemStack machine, boolean sodium, int ticks, int fuelCapacity, int coolantCapacity) {
        generators();
        MachineSlotLayout layout = new MachineSlotLayout()
                .add("fuel_input", QIOResourceCodecs.GAS_STACK_ID, PortRole.INPUT, fuelCapacity)
                .add("waste_output", QIOResourceCodecs.GAS_STACK_ID, PortRole.OUTPUT, fuelCapacity);
        if (sodium) layout.add("coolant_input", QIOResourceCodecs.GAS_STACK_ID, PortRole.INPUT, coolantCapacity)
                .add("heated_coolant_output", QIOResourceCodecs.GAS_STACK_ID, PortRole.OUTPUT, coolantCapacity);
        else layout.add("water_input", QIOResourceCodecs.FLUID_STACK_ID, PortRole.INPUT, coolantCapacity)
                .add("steam_output", QIOResourceCodecs.FLUID_STACK_ID, PortRole.OUTPUT, coolantCapacity);
        bind(machine, 1, ticks, 0, layout, new CloudworksFissionReactorRecipeAdapter(sodium, ticks),
                CloudworksThermalContracts.fission(sodium, ticks));
    }

    public static void turbine(ItemStack machine, int steam, int blades, int coils, int condensers,
            int steamCapacity, int waterCapacity, double energyCapacity) {
        generators();
        bind(machine, 1, 1, energyCapacity, new MachineSlotLayout()
                .add("steam_input", QIOResourceCodecs.FLUID_STACK_ID, PortRole.INPUT, steamCapacity)
                .add("water_output", QIOResourceCodecs.FLUID_STACK_ID, PortRole.OUTPUT, waterCapacity),
                new CloudworksTurbineRecipeAdapter(steam, blades, coils, condensers));
    }

    public static void fusion(ItemStack machine, int injection, boolean cooled, int gasCapacity,
            int fluidCapacity, double energyCapacity) {
        generators();
        MachineSlotLayout layout = new MachineSlotLayout()
                .add("fuel_input", QIOResourceCodecs.GAS_STACK_ID, PortRole.INPUT, gasCapacity)
                .add("deuterium_input", QIOResourceCodecs.GAS_STACK_ID, PortRole.INPUT, gasCapacity)
                .add("tritium_input", QIOResourceCodecs.GAS_STACK_ID, PortRole.INPUT, gasCapacity);
        if (cooled) layout.add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, PortRole.INPUT, fluidCapacity)
                .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, PortRole.OUTPUT, fluidCapacity);
        bind(machine, 1, 1, energyCapacity, layout, new CloudworksFusionReactorRecipeAdapter(injection, cooled),
                CloudworksGeneratorsIntegration.fusionPlan(injection, cooled));
    }

    private static void bind(ItemStack machine, int parallel, int ticks, double capacity,
            MachineSlotLayout layout, CloudworksMachineAdapter adapter) {
        bind(machine, parallel, ticks, capacity, layout, adapter, null);
    }

    private static void bind(ItemStack machine, int parallel, int ticks, double capacity,
            MachineSlotLayout layout, CloudworksMachineAdapter adapter, CloudworksThermalPlan thermal) {
        positiveTicks(ticks);
        if (parallel < 1) throw new IllegalArgumentException("Invalid multiblock parallelism");
        CloudworksScriptMachines.validate(layout, adapter);
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(machine,
                new MachineExecutionProfile(parallel, 0, capacity, ticks,
                        MachineUpgradePolicy.NONE),
                layout, MachineIdentityPolicy.exactNbt(), Collections.singletonList(
                        new CloudworksRecipeMode("cloudworks:processing", 0, null, adapter)), null, thermal));
    }

    private static void positiveTicks(int ticks) {
        if (ticks < 1) throw new IllegalArgumentException("Invalid multiblock duration");
    }

    private static void generators() {
        if (!Loader.isModLoaded("mekanismgenerators"))
            throw new IllegalStateException("This multiblock requires mekanismgenerators");
    }
}
