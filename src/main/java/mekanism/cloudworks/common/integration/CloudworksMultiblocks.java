package mekanism.cloudworks.common.integration;

import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.CloudworksMachineAdapter;
import mekanism.cloudworks.common.recipe.*;
import mekanism.cloudworks.common.registry.CloudworksMachineAdapters;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.registry.MachineExecutionProfile;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import mekanism.cloudworks.common.slot.MachineSlotLayout.PortRole;
import mekanism.common.recipe.RecipeHandler;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;

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
                new CloudworksThermalEvaporationRecipeAdapter(RecipeHandler.Recipe.THERMAL_EVAPORATION_PLANT.get()));
        CloudworksRegistries.registerThermal(machine, CloudworksThermalContracts.evaporation());
    }

    public static void sps(ItemStack machine, int ticks, double capacity) {
        positiveTicks(ticks);
        bind(machine, 1, ticks, capacity, new MachineSlotLayout()
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, PortRole.INPUT, 2000)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, PortRole.OUTPUT, 1000),
                new CloudworksSPSRecipeAdapter(ticks));
    }

    public static void boiler(ItemStack machine, int water, int ticks, int fluidCapacity, int gasCapacity) {
        bind(machine, 1, ticks, 0, new MachineSlotLayout()
                .add("water_input", QIOResourceCodecs.FLUID_STACK_ID, PortRole.INPUT, fluidCapacity)
                .add("heated_coolant_input", QIOResourceCodecs.GAS_STACK_ID, PortRole.INPUT, gasCapacity)
                .add("steam_output", QIOResourceCodecs.FLUID_STACK_ID, PortRole.OUTPUT, fluidCapacity)
                .add("cooled_coolant_output", QIOResourceCodecs.GAS_STACK_ID, PortRole.OUTPUT, gasCapacity),
                new CloudworksBoilerRecipeAdapter(water, ticks));
        CloudworksRegistries.registerThermal(machine, CloudworksThermalContracts.boiler(water, ticks));
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
        bind(machine, 1, ticks, 0, layout, new CloudworksFissionReactorRecipeAdapter(sodium, ticks));
        CloudworksRegistries.registerThermal(machine, CloudworksThermalContracts.fission(sodium, ticks));
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
        bind(machine, 1, 1, energyCapacity, layout, new CloudworksFusionReactorRecipeAdapter(injection, cooled));
        CloudworksRegistries.registerThermal(machine, CloudworksThermalContracts.fusion(injection, cooled));
    }

    private static void bind(ItemStack machine, int parallel, int ticks, double capacity,
            MachineSlotLayout layout, CloudworksMachineAdapter adapter) {
        positiveTicks(ticks);
        if (parallel < 1) throw new IllegalArgumentException("Invalid multiblock parallelism");
        CloudworksScriptMachines.validate(layout, adapter);
        CloudworksScriptMachines.register(machine, new MachineExecutionProfile(parallel, 0, capacity, ticks), layout);
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), adapter);
        if (adapter instanceof mekanism.cloudworks.api.CloudworksEnergyGenerator generator)
            CloudworksRegistries.registerEnergyGenerator(machine, generator);
    }

    private static void positiveTicks(int ticks) {
        if (ticks < 1) throw new IllegalArgumentException("Invalid multiblock duration");
    }

    private static void generators() {
        if (!Loader.isModLoaded("mekanismgenerators"))
            throw new IllegalStateException("This multiblock requires mekanismgenerators");
    }
}
