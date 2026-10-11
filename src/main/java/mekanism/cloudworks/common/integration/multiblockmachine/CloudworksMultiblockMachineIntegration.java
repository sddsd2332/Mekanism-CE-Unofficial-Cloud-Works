package mekanism.cloudworks.common.integration.multiblockmachine;

import java.util.Collections;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.CloudworksMachineAdapter;
import mekanism.cloudworks.common.recipe.CloudworksChemicalPairRecipeAdapter;
import mekanism.cloudworks.common.recipe.CloudworksGasFluidRecipeAdapter;
import mekanism.cloudworks.common.recipe.CloudworksGasGeneratorAdapter;
import mekanism.cloudworks.common.recipe.CloudworksWindGeneratorAdapter;
import mekanism.cloudworks.common.recipe.CloudworksSeparatorRecipeAdapter;
import mekanism.cloudworks.common.recipe.CloudworksSolarNeutronRecipeAdapter;
import mekanism.cloudworks.common.registry.CloudworksMachineAdapters;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.registry.MachineExecutionProfile;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import mekanism.common.block.states.BlockStateMachine.MachineType;
import mekanism.common.config.GeneratorsConfig;
import mekanism.common.config.MekanismConfig;
import mekanism.common.config.MultiblockMachineConfig;
import mekanism.common.recipe.RecipeHandler;
import mekanism.multiblockmachine.common.registries.MultiblockMachineBlocks;
import net.minecraft.item.ItemStack;

/** Optional large-machine declarations, with native parameters captured before Runtime starts. */
public final class CloudworksMultiblockMachineIntegration {
    private static final int GAS_CAPACITY = 8_192_000;
    private static final int SEPARATOR_FLUID_CAPACITY = 81_920_000;
    private static boolean registered;

    private CloudworksMultiblockMachineIntegration() {
    }

    public static synchronized void register(boolean generatorsLoaded) {
        if (registered) return;
        MultiblockMachineConfig config = MekanismConfig.current().multiblock;
        if (config == null) throw new IllegalStateException("MultiblockMachine configuration is not loaded");
        int infuserProcesses = config.LargeChemicalInfuserProcesses.val();
        register(new ItemStack(MultiblockMachineBlocks.LargeChemicalInfuser),
                processing(MachineType.CHEMICAL_INFUSER, infuserProcesses),
                new MachineSlotLayout()
                        .add("gas_input_left", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, GAS_CAPACITY)
                        .add("gas_input_right", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, GAS_CAPACITY)
                        .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, GAS_CAPACITY),
                new CloudworksChemicalPairRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_INFUSER.get()));
        register(new ItemStack(MultiblockMachineBlocks.LargeChemicalWasher),
                processing(MachineType.CHEMICAL_WASHER, config.LargeChemicalWasherProcesses.val()),
                new MachineSlotLayout()
                        .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, GAS_CAPACITY)
                        .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, GAS_CAPACITY)
                        .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, GAS_CAPACITY),
                new CloudworksGasFluidRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_WASHER.get()));
        int separatorProcesses = config.LargeElectrolyticSeparatorProcesses.val();
        // The per-recipe energy is the entire cost; a zero base avoids charging it twice.
        register(new ItemStack(MultiblockMachineBlocks.LargeElectrolyticSeparator),
                new MachineExecutionProfile(separatorProcesses, 0,
                        MachineType.ELECTROLYTIC_SEPARATOR.getStorage() * separatorProcesses, 1),
                new MachineSlotLayout()
                        .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, SEPARATOR_FLUID_CAPACITY)
                        .add("gas_output_left", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, GAS_CAPACITY)
                        .add("gas_output_right", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, GAS_CAPACITY),
                new CloudworksSeparatorRecipeAdapter(RecipeHandler.Recipe.ELECTROLYTIC_SEPARATOR.get(), true));
        register(new ItemStack(MultiblockMachineBlocks.LargeSolarNeutronActivator),
                new MachineExecutionProfile(config.LargeSolarNeutronProcesses.val(), 0, 0, 1),
                new MachineSlotLayout()
                        .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, GAS_CAPACITY)
                        .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, GAS_CAPACITY),
                new CloudworksSolarNeutronRecipeAdapter(RecipeHandler.Recipe.SOLAR_NEUTRON_ACTIVATOR.get()));
        if (generatorsLoaded) registerGenerators(config);
        registered = true;
    }

    private static MachineExecutionProfile processing(MachineType type, int processes) {
        return new MachineExecutionProfile(processes, type.getUsage(), type.getStorage() * processes, 1);
    }

    private static void register(ItemStack machine, MachineExecutionProfile profile,
            MachineSlotLayout layout, CloudworksMachineAdapter adapter) {
        CloudworksRegistries.registerMachine(machine, profile, layout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), adapter);
    }

    static void registerGenerators(MultiblockMachineConfig config) {
        GeneratorsConfig generators = MekanismConfig.current().generators;
        if (generators == null) throw new IllegalStateException("Generators configuration is not loaded");
        ItemStack wind = new ItemStack(MultiblockMachineBlocks.LargeWindGenerator);
        int windProcesses = config.LargeWindGeneratorProcesses.val();
        CloudworksRegistries.registerMachine(wind,
                new MachineExecutionProfile(windProcesses, 0, generators.windGeneratorStorage.val() * windProcesses, 1),
                new MachineSlotLayout(), Collections.emptyList());
        CloudworksRegistries.registerEnergyGenerator(wind, new CloudworksWindGeneratorAdapter(
                config.LargeWindGenerationMin.val(), config.LargeWindGenerationMax.val(),
                config.LargeWindGenerationMinY.val(), config.LargeWindGenerationMaxY.val(), 46));
        ItemStack gas = new ItemStack(MultiblockMachineBlocks.LargeGasGenerator);
        int gasProcesses = config.LargeGasGeneratorProcesses.val();
        CloudworksRegistries.registerMachine(gas,
                new MachineExecutionProfile(gasProcesses, 0,
                        MekanismConfig.current().general.FROM_H2.val() * 1000 * gasProcesses, 1),
                new MachineSlotLayout().add("gas_input", QIOResourceCodecs.GAS_STACK_ID,
                        MachineSlotLayout.PortRole.INPUT, GAS_CAPACITY), Collections.emptyList());
        CloudworksGasGeneratorAdapter fuel = new CloudworksGasGeneratorAdapter(
                RecipeHandler.Recipe.GAS_FUEL_TO_ENERGY_RECIPE.get());
        CloudworksRegistries.registerSpecial(gas, fuel);
        CloudworksRegistries.registerEnergyGenerator(gas, fuel);
    }
}
