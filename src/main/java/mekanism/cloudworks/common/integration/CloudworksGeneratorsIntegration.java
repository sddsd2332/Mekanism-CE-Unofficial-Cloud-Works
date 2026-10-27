package mekanism.cloudworks.common.integration;

import java.util.Collections;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.*;
import mekanism.cloudworks.api.card.*;
import mekanism.cloudworks.api.environment.*;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.api.node.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.cloudworks.api.transfer.*;
import mekanism.cloudworks.api.work.*;
import mekanism.cloudworks.common.recipe.CloudworksGasGeneratorAdapter;
import mekanism.cloudworks.common.recipe.CloudworksHeatGeneratorAdapter;
import mekanism.cloudworks.common.recipe.CloudworksSolarGeneratorAdapter;
import mekanism.cloudworks.common.recipe.CloudworksWindGeneratorAdapter;
import mekanism.common.config.MekanismConfig;
import mekanism.common.recipe.RecipeHandler;
import mekanism.generators.common.block.states.BlockStateGenerator.GeneratorType;
import net.minecraft.item.ItemStack;
import mekanism.common.content.boiler.SynchronizedBoilerData;
import mekanism.generators.common.FusionReactor;

/** Direct Mekanism Generators integration, invoked only through a Forge optional-method gate. */
public final class CloudworksGeneratorsIntegration {
    private CloudworksGeneratorsIntegration() {
    }

    public static void register() {
        registerGasGenerator();
        registerAmbientGenerators();
        registerHeatGenerator();
        registerFusionController();
        CloudworksAPI.registerMachine(GeneratorType.BIO_GENERATOR.getStack(),
                new MachineExecutionProfile(1, 0, MekanismConfig.current().generators.bioGeneratorStorage.val(), 1, MachineUpgradePolicy.NONE),
                new MachineSlotLayout().add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                        .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 24000),
                new mekanism.cloudworks.common.recipe.CloudworksBioGeneratorAdapter(MekanismConfig.current().generators.bioGeneration.val()),
                MachineIdentityPolicy.none());
    }

    private static void registerGasGenerator() {
        ItemStack machine = GeneratorType.GAS_GENERATOR.getStack();
        MachineSlotLayout layout = new MachineSlotLayout()
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 18_000);
        CloudworksGasGeneratorAdapter adapter =
                new CloudworksGasGeneratorAdapter(RecipeHandler.Recipe.GAS_FUEL_TO_ENERGY_RECIPE.get());
        CloudworksAPI.registerMachine(machine, new MachineExecutionProfile(1, 0D, 100_000D, 1, MachineUpgradePolicy.NONE),
                layout, adapter, MachineIdentityPolicy.none());
    }

    private static void registerFusionController() {
        mekanism.cloudworks.common.recipe.CloudworksFusionControllerAdapter dry =
                new mekanism.cloudworks.common.recipe.CloudworksFusionControllerAdapter(false);
        mekanism.cloudworks.common.recipe.CloudworksFusionControllerAdapter water =
                new mekanism.cloudworks.common.recipe.CloudworksFusionControllerAdapter(true);
        MachineSlotLayout layout = mekanism.cloudworks.common.recipe.CloudworksFusionNodeContracts.slots(2);
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(
                mekanism.generators.common.block.states.BlockStateReactor.ReactorBlockType.REACTOR_CONTROLLER.getStack(1),
                new MachineExecutionProfile(1, 0, MekanismConfig.current().generators.reactorGeneratorStorage.val(), 1, MachineUpgradePolicy.NONE),
                layout, MachineIdentityPolicy.none(), java.util.Arrays.asList(
                        new CloudworksRecipeMode("cloudworks:fusion_dry", 0, layout, dry),
                        new CloudworksRecipeMode("cloudworks:fusion_water", 1, layout, water)), null, dry.thermalPlan()));
    }

    private static void registerAmbientGenerators() {
        registerAmbient(GeneratorType.WIND_GENERATOR.getStack(),
                MekanismConfig.current().generators.windGeneratorStorage.val(),
                new CloudworksWindGeneratorAdapter(
                        MekanismConfig.current().generators.windGenerationMin.val(),
                        MekanismConfig.current().generators.windGenerationMax.val(),
                        MekanismConfig.current().generators.windGenerationMinY.val(),
                        MekanismConfig.current().generators.windGenerationMaxY.val(), 0));
        registerAmbient(GeneratorType.SOLAR_GENERATOR.getStack(),
                MekanismConfig.current().generators.solarGeneratorStorage.val(),
                new CloudworksSolarGeneratorAdapter(false));
        registerAmbient(GeneratorType.ADVANCED_SOLAR_GENERATOR.getStack(),
                MekanismConfig.current().generators.advancedSolarGeneratorStorage.val(),
                new CloudworksSolarGeneratorAdapter(true));
    }

    private static void registerHeatGenerator() {
        ItemStack machine = GeneratorType.HEAT_GENERATOR.getStack();
        CloudworksHeatGeneratorAdapter generator = new CloudworksHeatGeneratorAdapter();
        MachineSlotLayout layout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT,
                        Math.max(1, MekanismConfig.current().generators.heatTankCapacity.val()))
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(machine, new MachineExecutionProfile(1, 0D,
                MekanismConfig.current().generators.heatGeneratorStorage.val(), 1, MachineUpgradePolicy.NONE),
                layout, MachineIdentityPolicy.none(), Collections.emptyList(), null,
                CloudworksThermalContracts.heatGenerator()));
        CloudworksAPI.acceptMachineUpgrade(machine, MachineModuleType.lavaHeat(), 7);
    }

    private static void registerAmbient(ItemStack machine, double capacity, CloudworksEnergyGenerator generator) {
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(machine,
                new MachineExecutionProfile(1, 0D, capacity, 1, MachineUpgradePolicy.NONE),
                new MachineSlotLayout(), MachineIdentityPolicy.none(), Collections.emptyList(), generator, null));
        if (generator instanceof CloudworksWindGeneratorAdapter)
            CloudworksAPI.acceptMachineUpgrade(machine, MachineModuleType.height(),
                    ((CloudworksWindGeneratorAdapter) generator).heightLimit());
    }

    /**
     * Fusion plan factory; it hard-references Mekanism Generators classes and
     * therefore lives behind the generators gate instead of the core recipe
     * contract package.
     */
    public static CloudworksThermalPlan fusionPlan(int injection, boolean cooled) {
        return new CloudworksThermalPlan(
                CloudworksThermalPlan.Kind.FUSION,
                Math.max(1, FusionReactor.caseHeatCapacity),
                Math.max(1, 1 / Math.max(0.001, MekanismConfig.current().generators.fusionCasingThermalConductivity.val())), 100000,
                MekanismConfig.current().generators.energyPerFusionFuel.val(),
                MekanismConfig.current().generators.fusionThermocoupleEfficiency.val(),
                FusionReactor.burnTemperature,
                FusionReactor.plasmaCaseConductivity,
                SynchronizedBoilerData.getHeatEnthalpy()
                        / SynchronizedBoilerData.getSteamEnergyEfficiency(),
                0, 0, MekanismConfig.current().generators.fusionWaterHeatingRatio.val(),
                0, 1e12, Math.max(1, FusionReactor.plasmaHeatCapacity), injection, cooled);
    }
}
