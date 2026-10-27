package mekanism.cloudworks.common.registry;

import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.CloudworksInfuseResourceCodec;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineIdentityPolicy;
import mekanism.cloudworks.common.recipe.*;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.api.module.MachineUpgradePolicy;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.machine.CloudworksMachineDefinition;
import mekanism.cloudworks.api.recipe.CloudworksRecipeMode;
import mekanism.common.base.IFactory.RecipeType;
import mekanism.common.block.states.BlockStateBasic.BasicBlockType;
import mekanism.common.block.states.BlockStateMachine.MachineType;
import mekanism.common.recipe.RecipeHandler;
import mekanism.common.tier.FactoryTier;
import mekanism.common.util.MekanismUtils;
import mekanism.common.tile.machine.TileEntityChemicalOxidizer;
import mekanism.common.tier.GasTankTier;
import mekanism.common.config.MekanismConfig;
import mekanism.common.tile.multiblock.TileEntityThermalEvaporationController;
import net.minecraft.item.ItemStack;

import java.util.Collections;
import java.util.Map;
import mekanism.cloudworks.api.machine.CloudworksThermalContracts;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.common.lib.radiation.RadiationUtil;

/**
 * First built-in Mekanism adapters; additions stay behind the same registration API.
 */
public final class CloudworksMekanismBuiltins {
    private static final MachineIdentityPolicy FACTORY_IDENTITY =
            MachineIdentityPolicy.keys("recipeTypeName", "recipeType", "recipeTypeVersion");
    private static boolean registered;

    private CloudworksMekanismBuiltins() {
    }

    public static synchronized void register() {
        if (registered) return;
        CloudworksInfuseResourceCodec.register();
        CloudworksAPI.registerMachineModule(mekanism.cloudworks.common.integration.CloudworksGasUpgrade.type());
        CloudworksAPI.registerMachine(new mekanism.cloudworks.api.machine.CloudworksMachineDefinition(
                MachineType.OREDICTIONIFICATOR.getStack(), new MachineExecutionProfile(1, 0, 0, 1, MachineUpgradePolicy.NONE),
                new MachineSlotLayout().add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                        .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64),
                MachineIdentityPolicy.none(), Collections.singletonList(new CloudworksRecipeMode("cloudworks:ore_conversion", 0, null,
                        mekanism.cloudworks.common.recipe.CloudworksOredictionificator.registrationAdapter()))));
        MachineSlotLayout itemLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        // Ticks mirror the CEU native baseTicksRequired of each tile
        // (TileEntityCrusher:14, TileEntityEnrichmentChamber:14, TileEntityCombiner:22,
        // TileEntityEnergizedSmelter:14 are all 200).
        register(MachineType.CRUSHER.getStack(), RecipeHandler.Recipe.CRUSHER.get(), 200, itemLayout);
        register(MachineType.ENRICHMENT_CHAMBER.getStack(), RecipeHandler.Recipe.ENRICHMENT_CHAMBER.get(), 200, itemLayout);
        registerDouble(MachineType.COMBINER.getStack(), RecipeHandler.Recipe.COMBINER.get(), 200, itemLayout);
        register(MachineType.ENERGIZED_SMELTER.getStack(), RecipeHandler.Recipe.ENERGIZED_SMELTER.get(), 200, itemLayout);
        registerAdditionalItemMachines(itemLayout);
        MachineSlotLayout infuserLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("infuse_item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("infuse_input", CloudworksInfuseResourceCodec.ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        // TileEntityMetallurgicInfuser:81 and TileEntityPrecisionSawmill:26 run 200 ticks.
        registerInfuser(MachineType.METALLURGIC_INFUSER.getStack(), RecipeHandler.Recipe.METALLURGIC_INFUSER.get(), 200, infuserLayout);
        registerFactoryModes(itemLayout);
        registerChance(MachineType.PRECISION_SAWMILL.getStack(), RecipeHandler.Recipe.PRECISION_SAWMILL.get(), 200, itemLayout);
        MachineSlotLayout gasLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 210)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        registerGas(MachineType.PURIFICATION_CHAMBER.getStack(), RecipeHandler.Recipe.PURIFICATION_CHAMBER.get(), 200, gasLayout);
        registerGas(MachineType.OSMIUM_COMPRESSOR.getStack(), RecipeHandler.Recipe.OSMIUM_COMPRESSOR.get(), 200, gasLayout);
        registerGas(MachineType.CHEMICAL_INJECTION_CHAMBER.getStack(), RecipeHandler.Recipe.CHEMICAL_INJECTION_CHAMBER.get(), 200, gasLayout);
        registerGasOutput(MachineType.CHEMICAL_OXIDIZER.getStack(), RecipeHandler.Recipe.CHEMICAL_OXIDIZER.get(), 100,
                new MachineSlotLayout().add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                        .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT,
                                TileEntityChemicalOxidizer.MAX_GAS));
        MachineSlotLayout ambientLayout = new MachineSlotLayout()
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 10000);
        // TileEntityAmbientAccumulator:204 runs one roll per tick; the passive
        // variant burns nothing and the electric variant pays its live usage.
        registerAmbient(MachineType.AMBIENT_ACCUMULATOR.getStack(), RecipeHandler.Recipe.AMBIENT_ACCUMULATOR.get(),
                0D, ambientLayout, MachineUpgradePolicy.NONE);
        registerAmbient(MachineType.AMBIENT_ACCUMULATOR_ENERGY.getStack(), RecipeHandler.Recipe.AMBIENT_ACCUMULATOR_ENERGY.get(),
                MachineType.AMBIENT_ACCUMULATOR_ENERGY.getUsage(),
                new MachineSlotLayout().add("gas_output", QIOResourceCodecs.GAS_STACK_ID,
                        MachineSlotLayout.PortRole.OUTPUT, GasTankTier.BASIC.getBaseStorage()), MachineUpgradePolicy.MEKANISM);
        MachineSlotLayout fluidLayout = new MachineSlotLayout()
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT,
                        (int) Math.min(Integer.MAX_VALUE, (long) TileEntityThermalEvaporationController.MAX_HEIGHT
                                * 4L * Math.max(1, MekanismConfig.current().general.evaporationFluidPerTank.val())))
                .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT,
                        Math.max(1, MekanismConfig.current().general.evaporationOutputTankCapacity.val()));
        ItemStack thermal = BasicBlockType.THERMAL_EVAPORATION_CONTROLLER.getStack(1);
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(thermal,
                new MachineExecutionProfile(1, 0D, 0D, 100, MachineUpgradePolicy.NONE), fluidLayout,
                MachineIdentityPolicy.none(), Collections.singletonList(new CloudworksRecipeMode(
                        "cloudworks:processing", 0, null,
                        new CloudworksThermalEvaporationRecipeAdapter(RecipeHandler.Recipe.THERMAL_EVAPORATION_PLANT.get()))),
                null, evaporationThermalPlan()));
        registerHeaters();
        mekanism.cloudworks.common.integration.CloudworksMultiblocks.sps(MachineType.SPS.getStack(), 1, MachineType.SPS.getStorage(), MachineIdentityPolicy.none());
        CloudworksAPI.registerMachineModule(mekanism.cloudworks.common.integration.CloudworksPumpIntegration.filter());
        mekanism.cloudworks.common.integration.CloudworksPumpIntegration.register(
                MachineType.ELECTRIC_PUMP.getStack(), 1, MachineIdentityPolicy.none());
        MachineSlotLayout basePrcLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10000)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 10000);
        // TileEntityPRC:87 runs 100 ticks.
        registerPressurized(MachineType.PRESSURIZED_REACTION_CHAMBER.getStack(),
                RecipeHandler.Recipe.PRESSURIZED_REACTION_CHAMBER.get(), 100, basePrcLayout);
        MachineSlotLayout gasPairLayout = new MachineSlotLayout()
                .add("gas_input_left", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10000)
                .add("gas_input_right", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10000)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 10000);
        ItemStack chemicalInfuser = MachineType.CHEMICAL_INFUSER.getStack();
        // TileEntityChemicalInfuser:76 runs one operation per tick.
        CloudworksAPI.registerMachine(chemicalInfuser, new MachineExecutionProfile(1, 200D, 1), gasPairLayout,
                new CloudworksChemicalPairRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_INFUSER.get()), MachineIdentityPolicy.none());
        MachineSlotLayout crystallizerLayout = new MachineSlotLayout()
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        ItemStack crystallizer = MachineType.CHEMICAL_CRYSTALLIZER.getStack();
        // TileEntityChemicalCrystallizer:79 runs 200 ticks.
        CloudworksAPI.registerMachine(crystallizer, new MachineExecutionProfile(1, 200D, 200), crystallizerLayout,
                new CloudworksGasItemRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_CRYSTALLIZER.get()), MachineIdentityPolicy.none());
        MachineSlotLayout separatorLayout = new MachineSlotLayout()
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 24000)
                .add("gas_output_left", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 2400)
                .add("gas_output_right", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 2400);
        ItemStack separator = MachineType.ELECTROLYTIC_SEPARATOR.getStack();
        // TileEntityElectrolyticSeparator:115 runs one operation per tick and its
        // onCachedRecipeChanged (:188-197) replaces the base rate with each
        // recipe's energyUsage — the MultiblockMachine registration pattern.
        CloudworksAPI.registerMachine(separator, new MachineExecutionProfile(1, 0D,
                MachineType.ELECTROLYTIC_SEPARATOR.getStorage(), 1), separatorLayout,
                new CloudworksSeparatorRecipeAdapter(RecipeHandler.Recipe.ELECTROLYTIC_SEPARATOR.get(), true),
                MachineIdentityPolicy.none());
        ItemStack rotary = MachineType.ROTARY_CONDENSENTRATOR.getStack();
        // TileEntityRotaryCondensentrator converts continuously (no ticks axis).
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(rotary, new MachineExecutionProfile(1, 200D, 1),
                CloudworksRotaryRecipeAdapter.fluidToGasLayout(10000), MachineIdentityPolicy.none(),
                CloudworksRotaryRecipeAdapter.recipeTypes(RecipeHandler.Recipe.ROTARY_CONDENSENTRATOR.get())));
        MachineSlotLayout dissolutionLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10000)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 10000);
        ItemStack dissolution = MachineType.CHEMICAL_DISSOLUTION_CHAMBER.getStack();
        CloudworksAPI.registerMachine(dissolution, new MachineExecutionProfile(1, 400D, 100), dissolutionLayout,
                new CloudworksDissolutionRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_DISSOLUTION_CHAMBER.get()), MachineIdentityPolicy.none());
        MachineSlotLayout washerLayout = new MachineSlotLayout()
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10000)
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10000)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 10000);
        ItemStack washer = MachineType.CHEMICAL_WASHER.getStack();
        // TileEntityChemicalWasher:84 runs one operation per tick.
        CloudworksAPI.registerMachine(washer, new MachineExecutionProfile(1, 200D, 1), washerLayout,
                new CloudworksGasFluidRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_WASHER.get()), MachineIdentityPolicy.none());
        MachineSlotLayout gasToGasLayout = new MachineSlotLayout()
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10000)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 10000);
        ItemStack isotopic = MachineType.ISOTOPIC_CENTRIFUGE.getStack();
        // TileEntityIsotopicCentrifuge:88 runs one operation per tick.
        CloudworksAPI.registerMachine(isotopic, new MachineExecutionProfile(1, 200D, 1), gasToGasLayout,
                new CloudworksGasGasRecipeAdapter(RecipeHandler.Recipe.ISOTOPIC_CENTRIFUGE.get()), MachineIdentityPolicy.none());
        ItemStack solar = MachineType.SOLAR_NEUTRON_ACTIVATOR.getStack();
        // TileEntitySolarNeutronActivator:88-90 runs one operation per tick and
        // burns no energy (sunlight only), like the MoreMachine tier family.
        CloudworksAPI.registerMachine(solar, new MachineExecutionProfile(1, 0D, 0D, 1, MachineUpgradePolicy.MEKANISM_SPEED),
                gasToGasLayout,
                new CloudworksSolarNeutronRecipeAdapter(RecipeHandler.Recipe.SOLAR_NEUTRON_ACTIVATOR.get()), MachineIdentityPolicy.none());
        MachineSlotLayout nutritionalLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 10000);
        ItemStack nutritional = MachineType.NUTRITIONAL_LIQUIFIER.getStack();
        CloudworksAPI.registerMachine(nutritional, new MachineExecutionProfile(1, 200D, 100), nutritionalLayout,
                new CloudworksNutritionalRecipeAdapter(RecipeHandler.Recipe.NUTRITIONAL_LIQUIFIER.get()), MachineIdentityPolicy.none());
        MachineSlotLayout nucleosynthLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        ItemStack nucleosynth = MachineType.ANTIPROTONIC_NUCLEOSYNTHESIZER.getStack();
        CloudworksAPI.registerMachine(nucleosynth, new MachineExecutionProfile(1, 200D, 100), nucleosynthLayout,
                new CloudworksNucleosynthesizerRecipeAdapter(RecipeHandler.Recipe.ANTIPROTONIC_NUCLEOSYNTHESIZER.get()), MachineIdentityPolicy.none());
        ItemStack farm = MachineType.ORGANIC_FARM.getStack();
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(farm, new MachineExecutionProfile(1, 200D, 200),
                CloudworksFarmRecipeAdapter.layout(1, true), MachineIdentityPolicy.none(),
                CloudworksFarmRecipeAdapter.recipeTypes(RecipeHandler.Recipe.ORGANIC_FARM.get(), 1)));
        registerChance(MachineType.CELL_EXTRACTOR.getStack(), RecipeHandler.Recipe.CELL_EXTRACTOR.get(), 200, itemLayout);
        registerChance(MachineType.CELL_SEPARATOR.getStack(), RecipeHandler.Recipe.CELL_SEPARATOR.get(), 200, itemLayout);
        ItemStack recycler = MachineType.RECYCLER.getStack();
        CloudworksAPI.registerMachine(recycler, new MachineExecutionProfile(1, 50D, 200), itemLayout,
                new CloudworksChance2ItemRecipeAdapter(RecipeHandler.Recipe.RECYCLER.get(), true), MachineIdentityPolicy.none());
        ItemStack waste = MachineType.RADIOACTIVE_WASTE_BARREL.getStack();
        CloudworksAPI.registerMachine(waste, new MachineExecutionProfile(1, 0, 0,
                        RadiationUtil.getWasteBarrelProcessTicks(), MachineUpgradePolicy.NONE),
                new MachineSlotLayout().add("gas_input", QIOResourceCodecs.GAS_STACK_ID,
                        MachineSlotLayout.PortRole.INPUT, 512_000), new CloudworksWasteBarrelRecipeAdapter(1), MachineIdentityPolicy.none());
        for (MachineType gasMachine : new MachineType[]{MachineType.PURIFICATION_CHAMBER, MachineType.OSMIUM_COMPRESSOR,
                MachineType.CHEMICAL_INJECTION_CHAMBER, MachineType.CHEMICAL_DISSOLUTION_CHAMBER, MachineType.ORGANIC_FARM})
            CloudworksAPI.acceptMachineUpgrade(gasMachine.getStack(), mekanism.cloudworks.common.integration.CloudworksGasUpgrade.type(),
                    mekanism.common.Upgrade.GAS.getMaxInstalled());
        registered = true;
    }

    static void registerHeaters() {
        ItemStack resistive = MachineType.RESISTIVE_HEATER.getStack();
        CloudworksThermalPlan resistivePlan =
                CloudworksThermalContracts.resistive();
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(resistive, new MachineExecutionProfile(1, 100D, 40000D, 1,
                MachineUpgradePolicy.NONE),
                new MachineSlotLayout(), MachineIdentityPolicy.none(), Collections.emptyList(), null, resistivePlan));
        ItemStack fuelwood = MachineType.FUELWOOD_HEATER.getStack();
        CloudworksThermalPlan fuelwoodPlan =
                CloudworksThermalContracts.fuelwood();
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(fuelwood, new MachineExecutionProfile(1, 0, 0, 1, MachineUpgradePolicy.NONE),
                new MachineSlotLayout()
                        .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                        .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64),
                MachineIdentityPolicy.none(), Collections.emptyList(), null, fuelwoodPlan));
    }

    static CloudworksThermalPlan evaporationThermalPlan() {
        return CloudworksThermalContracts.evaporation()
                .withEnvironment(0, 0, 4);
    }

    private static void register(ItemStack machine, Map recipes, int ticks, MachineSlotLayout layout) {
        CloudworksAPI.registerMachine(machine, new MachineExecutionProfile(1, 50D, ticks), layout,
                new CloudworksBasicItemRecipeAdapter(recipes), MachineIdentityPolicy.none());
    }

    private static void registerGas(ItemStack machine, Map recipes, int ticks, MachineSlotLayout layout) {
        CloudworksAPI.registerMachine(machine, new MachineExecutionProfile(1, 200D, ticks), layout,
                new CloudworksAdvancedGasItemRecipeAdapter(recipes), MachineIdentityPolicy.none());
    }

    private static void registerDouble(ItemStack machine, Map recipes, int ticks, MachineSlotLayout layout) {
        CloudworksAPI.registerMachine(machine, new MachineExecutionProfile(1, 50D, ticks), withExtraItemInput(layout),
                new CloudworksDoubleItemRecipeAdapter(recipes), MachineIdentityPolicy.none());
    }

    private static void registerGasOutput(ItemStack machine, Map recipes, int ticks, MachineSlotLayout layout) {
        CloudworksAPI.registerMachine(machine, new MachineExecutionProfile(1, 200D, ticks), layout,
                new CloudworksItemGasOutputRecipeAdapter(recipes), MachineIdentityPolicy.none());
    }

    private static void registerChance(ItemStack machine, Map recipes, int ticks, MachineSlotLayout layout) {
        CloudworksAPI.registerMachine(machine, new MachineExecutionProfile(1, 50D, ticks), chanceItemLayout(layout),
                new CloudworksChanceItemRecipeAdapter(recipes), MachineIdentityPolicy.none());
    }

    private static MachineSlotLayout chanceItemLayout(MachineSlotLayout layout) {
        MachineSlotLayout result = new MachineSlotLayout();
        for (MachineSlotLayout.Slot slot : layout.slots())
            result.add(slot.id(), slot.codecId(), slot.role(), slot.id().equals("item_output")
                            ? Math.multiplyExact(slot.capacity(), 2) : slot.capacity(),
                    slot.multipleConnections(), slot.recipeFilteredInput());
        return result;
    }

    static void registerAdditionalItemMachines(MachineSlotLayout layout) {
        registerNativeItemMachine(MachineType.STAMPING, RecipeHandler.Recipe.STAMPING.get(), layout, false);
        registerNativeItemMachine(MachineType.ROLLING, RecipeHandler.Recipe.ROLLING.get(), layout, false);
        registerNativeItemMachine(MachineType.BRUSHED, RecipeHandler.Recipe.BRUSHED.get(), layout, false);
        registerNativeItemMachine(MachineType.TURNING, RecipeHandler.Recipe.TURNING.get(), layout, false);
        registerNativeItemMachine(MachineType.ALLOY, RecipeHandler.Recipe.ALLOY.get(), layout, true);
    }

    private static void registerNativeItemMachine(MachineType type, Map recipes,
            MachineSlotLayout layout, boolean extraInput) {
        ItemStack machine = type.getStack();
        CloudworksAPI.registerMachine(machine,
                new MachineExecutionProfile(1, type.getUsage(), type.getStorage(), 200),
                extraInput ? withExtraItemInput(layout) : layout, extraInput
                ? new CloudworksDoubleItemRecipeAdapter(recipes) : new CloudworksBasicItemRecipeAdapter(recipes), MachineIdentityPolicy.none());
    }

    static void registerFactoryModes(MachineSlotLayout layout) {
        registerFactoryFamily(RecipeType.SMELTING, layout,
                new CloudworksBasicItemRecipeAdapter(RecipeHandler.Recipe.ENERGIZED_SMELTER.get()));
        registerFactoryFamily(RecipeType.ENRICHING, layout,
                new CloudworksBasicItemRecipeAdapter(RecipeHandler.Recipe.ENRICHMENT_CHAMBER.get()));
        registerFactoryFamily(RecipeType.CRUSHING, layout,
                new CloudworksBasicItemRecipeAdapter(RecipeHandler.Recipe.CRUSHER.get()));

        MachineSlotLayout factoryGasLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        MachineSlotLayout factoryInfuseLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("infuse_item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("infuse_input", CloudworksInfuseResourceCodec.ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);

        registerFactoryFamily(RecipeType.INFUSING, factoryInfuseLayout,
                new CloudworksInfuserRecipeAdapter(RecipeHandler.Recipe.METALLURGIC_INFUSER.get()));
        registerFactoryFamily(RecipeType.PURIFYING, factoryGasLayout,
                new CloudworksAdvancedGasItemRecipeAdapter(RecipeHandler.Recipe.PURIFICATION_CHAMBER.get()));
        registerFactoryFamily(RecipeType.INJECTING, factoryGasLayout,
                new CloudworksAdvancedGasItemRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_INJECTION_CHAMBER.get()));
        registerFactoryFamily(RecipeType.COMBINING, withExtraItemInput(layout),
                new CloudworksDoubleItemRecipeAdapter(RecipeHandler.Recipe.COMBINER.get()));
        registerFactoryFamily(RecipeType.AllOY, withExtraItemInput(layout),
                new CloudworksDoubleItemRecipeAdapter(RecipeHandler.Recipe.ALLOY.get()));
        registerFactoryFamily(RecipeType.COMPRESSING, factoryGasLayout,
                new CloudworksAdvancedGasItemRecipeAdapter(RecipeHandler.Recipe.OSMIUM_COMPRESSOR.get()));
        registerFactoryFamily(RecipeType.SAWING, layout,
                new CloudworksChanceItemRecipeAdapter(RecipeHandler.Recipe.PRECISION_SAWMILL.get()));
        registerFactoryFamily(RecipeType.EXTRACTOR, layout,
                new CloudworksChanceItemRecipeAdapter(RecipeHandler.Recipe.CELL_EXTRACTOR.get()));
        registerFactoryFamily(RecipeType.SEPARATOR, layout,
                new CloudworksChanceItemRecipeAdapter(RecipeHandler.Recipe.CELL_SEPARATOR.get()));
        registerFactoryFamily(RecipeType.RECYCLER, layout,
                new CloudworksChance2ItemRecipeAdapter(RecipeHandler.Recipe.RECYCLER.get(), true));
        registerFactoryFamily(RecipeType.STAMPING, layout,
                new CloudworksBasicItemRecipeAdapter(RecipeHandler.Recipe.STAMPING.get()));
        registerFactoryFamily(RecipeType.ROLLING, layout,
                new CloudworksBasicItemRecipeAdapter(RecipeHandler.Recipe.ROLLING.get()));
        registerFactoryFamily(RecipeType.BRUSHED, layout,
                new CloudworksBasicItemRecipeAdapter(RecipeHandler.Recipe.BRUSHED.get()));
        registerFactoryFamily(RecipeType.TURNING, layout,
                new CloudworksBasicItemRecipeAdapter(RecipeHandler.Recipe.TURNING.get()));

        MachineSlotLayout prcLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10000)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 10000);
        registerFactoryFamily(RecipeType.PRC, prcLayout,
                new CloudworksPressurizedRecipeAdapter(RecipeHandler.Recipe.PRESSURIZED_REACTION_CHAMBER.get()));
        MachineSlotLayout nucleosynthLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        registerFactoryFamily(RecipeType.NUCLEOSYNTHESIZER, nucleosynthLayout,
                new CloudworksNucleosynthesizerRecipeAdapter(
                        RecipeHandler.Recipe.ANTIPROTONIC_NUCLEOSYNTHESIZER.get()));
    }

    private static void registerFactoryFamily(RecipeType type, MachineSlotLayout layout,
                                              CloudworksMachineAdapter adapter) {
        if (type == RecipeType.SAWING || type == RecipeType.EXTRACTOR || type == RecipeType.SEPARATOR)
            layout = chanceItemLayout(layout);
        for (FactoryTier tier : FactoryTier.values()) {
            ItemStack machine = MekanismUtils.getFactory(tier, type);
            CloudworksAPI.registerMachine(machine, factoryProfile(tier, type),
                    scaleFactoryLayout(layout, tier.processes), adapter, FACTORY_IDENTITY);
            if (type == RecipeType.PURIFYING || type == RecipeType.INJECTING)
                CloudworksAPI.acceptMachineUpgrade(machine, mekanism.cloudworks.common.integration.CloudworksGasUpgrade.type(),
                        mekanism.common.Upgrade.GAS.getMaxInstalled());
        }
    }

    private static MachineSlotLayout scaleFactoryLayout(MachineSlotLayout layout, int processes) {
        MachineSlotLayout scaled = new MachineSlotLayout();
        for (MachineSlotLayout.Slot slot : layout.slots()) {
            // Infusion material shares one physical conversion slot at every tier.
            scaled.add(slot.id(), slot.codecId(), slot.role(),
                    slot.id().equals("infuse_item_input") ? slot.capacity() : Math.multiplyExact(slot.capacity(), processes),
                    slot.multipleConnections(), slot.recipeFilteredInput());
        }
        return scaled;
    }

    private static MachineSlotLayout withExtraItemInput(MachineSlotLayout layout) {
        MachineSlotLayout result = new MachineSlotLayout();
        for (MachineSlotLayout.Slot slot : layout.slots()) {
            result.add(slot.id(), slot.codecId(), slot.role(), slot.capacity(), slot.multipleConnections(), slot.recipeFilteredInput());
            if (slot.id().equals("item_input"))
                result.add("extra_item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64);
        }
        return result;
    }

    private static void registerPressurized(ItemStack machine, Map recipes, int ticks, MachineSlotLayout layout) {
        CloudworksAPI.registerMachine(machine, new MachineExecutionProfile(1, 200D, ticks), layout,
                new CloudworksPressurizedRecipeAdapter(recipes), MachineIdentityPolicy.none());
    }

    private static void registerInfuser(ItemStack machine, Map recipes, int ticks, MachineSlotLayout layout) {
        CloudworksAPI.registerMachine(machine, new MachineExecutionProfile(1, 50D, ticks), layout,
                new CloudworksInfuserRecipeAdapter(recipes), MachineIdentityPolicy.none());
    }

    private static void registerAmbient(ItemStack machine, Map recipes, double energyPerTick,
            MachineSlotLayout layout, MachineUpgradePolicy policy) {
        CloudworksAPI.registerMachine(machine, new MachineExecutionProfile(1, energyPerTick, energyPerTick * 10D, 1)
                .withUpgradePolicy(policy),
                layout, new CloudworksAmbientGasRecipeAdapter(recipes), MachineIdentityPolicy.none());
    }

    private static MachineExecutionProfile factoryProfile(FactoryTier tier, RecipeType type) {
        double storageFactor = type == RecipeType.PRC || type == RecipeType.NUCLEOSYNTHESIZER ? 1D : 0.5D;
        return new MachineExecutionProfile(tier.processes, type.getEnergyUsage(),
                tier.processes * Math.max(storageFactor * type.getEnergyStorage(), type.getEnergyUsage()),
                factoryTicks(type));
    }

    /** Native base ticks per factory family: PRC and the nucleosynthesizer run 100, every other family 200. */
    private static int factoryTicks(RecipeType type) {
        return type == RecipeType.PRC || type == RecipeType.NUCLEOSYNTHESIZER ? 100 : 200;
    }
}
