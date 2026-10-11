package mekanism.cloudworks.common.registry;

import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.common.recipe.*;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import mekanism.common.MekanismBlocks;
import mekanism.common.base.FactoryRecipeTypeCodec;
import mekanism.common.base.IFactory.RecipeType;
import mekanism.common.block.states.BlockStateMachine.MachineType;
import mekanism.common.recipe.RecipeHandler;
import mekanism.common.config.MekanismConfig;
import net.minecraft.item.ItemStack;

import java.util.Collections;

/**
 * First built-in Mekanism adapters; additions stay behind the same registration API.
 */
public final class CloudworksMekanismBuiltins {
    private static boolean registered;

    private CloudworksMekanismBuiltins() {
    }

    public static synchronized void register() {
        if (registered) return;
        CloudworksInfuseResourceCodec.register();
        MachineSlotLayout itemLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        register(new ItemStack(MekanismBlocks.MachineBlock, 1, 3), RecipeHandler.Recipe.CRUSHER.get(), 6, itemLayout);
        register(new ItemStack(MekanismBlocks.MachineBlock, 1, 0), RecipeHandler.Recipe.ENRICHMENT_CHAMBER.get(), 200, itemLayout);
        registerDouble(new ItemStack(MekanismBlocks.MachineBlock, 1, 2), RecipeHandler.Recipe.COMBINER.get(), 100, itemLayout);
        register(new ItemStack(MekanismBlocks.MachineBlock, 1, 10), RecipeHandler.Recipe.ENERGIZED_SMELTER.get(), 16, itemLayout);
        registerAdditionalItemMachines(itemLayout);
        MachineSlotLayout infuserLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("infuse_item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("infuse_input", CloudworksInfuseResourceCodec.ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        registerInfuser(new ItemStack(MekanismBlocks.MachineBlock, 1, 8), RecipeHandler.Recipe.METALLURGIC_INFUSER.get(), 100, infuserLayout);
        registerFactoryModes(itemLayout);
        registerChance(new ItemStack(MekanismBlocks.MachineBlock2, 1, 5), RecipeHandler.Recipe.PRECISION_SAWMILL.get(), 100, itemLayout);
        MachineSlotLayout gasLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        registerGas(new ItemStack(MekanismBlocks.MachineBlock, 1, 9), RecipeHandler.Recipe.PURIFICATION_CHAMBER.get(), 200, gasLayout);
        registerGas(new ItemStack(MekanismBlocks.MachineBlock, 1, 1), RecipeHandler.Recipe.OSMIUM_COMPRESSOR.get(), 200, gasLayout);
        registerGas(new ItemStack(MekanismBlocks.MachineBlock2, 1, 3), RecipeHandler.Recipe.CHEMICAL_INJECTION_CHAMBER.get(), 200, gasLayout);
        registerGasOutput(new ItemStack(MekanismBlocks.MachineBlock2, 1, 1), RecipeHandler.Recipe.CHEMICAL_OXIDIZER.get(), 100,
                new MachineSlotLayout().add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                        .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 1000));
        MachineSlotLayout ambientLayout = new MachineSlotLayout()
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 10000);
        registerAmbient(new ItemStack(MekanismBlocks.MachineBlock3, 1, 2), RecipeHandler.Recipe.AMBIENT_ACCUMULATOR.get(), 20, ambientLayout);
        registerAmbient(new ItemStack(MekanismBlocks.MachineBlock3, 1, 14), RecipeHandler.Recipe.AMBIENT_ACCUMULATOR_ENERGY.get(), 20, ambientLayout);
        MachineSlotLayout fluidLayout = new MachineSlotLayout()
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 1000);
        ItemStack thermal = new ItemStack(MekanismBlocks.BasicBlock, 1, 14);
        CloudworksRegistries.registerMachine(thermal, new MachineExecutionProfile(1, 0D, 0D, 100), fluidLayout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(thermal),
                new CloudworksThermalEvaporationRecipeAdapter(RecipeHandler.Recipe.THERMAL_EVAPORATION_PLANT.get()));
        CloudworksRegistries.registerThermal(thermal, evaporationThermalPlan());
        registerHeaters();
        registerGasGeneratorIfPresent();
        registerAmbientGeneratorsIfPresent();
        MachineSlotLayout basePrcLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 1000);
        registerPressurized(new ItemStack(MekanismBlocks.MachineBlock2, 1, 10),
                RecipeHandler.Recipe.PRESSURIZED_REACTION_CHAMBER.get(), 200, basePrcLayout);
        MachineSlotLayout gasPairLayout = new MachineSlotLayout()
                .add("gas_input_left", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("gas_input_right", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 1000);
        ItemStack chemicalInfuser = new ItemStack(MekanismBlocks.MachineBlock2, 1, 2);
        CloudworksRegistries.registerMachine(chemicalInfuser, new MachineExecutionProfile(1, 200D, 100), gasPairLayout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(chemicalInfuser),
                new CloudworksChemicalPairRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_INFUSER.get()));
        MachineSlotLayout crystallizerLayout = new MachineSlotLayout()
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        ItemStack crystallizer = new ItemStack(MekanismBlocks.MachineBlock2, 1, 8);
        CloudworksRegistries.registerMachine(crystallizer, new MachineExecutionProfile(1, 200D, 100), crystallizerLayout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(crystallizer),
                new CloudworksGasItemRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_CRYSTALLIZER.get()));
        MachineSlotLayout separatorLayout = new MachineSlotLayout()
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("gas_output_left", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 1000)
                .add("gas_output_right", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 1000);
        ItemStack separator = new ItemStack(MekanismBlocks.MachineBlock2, 1, 4);
        CloudworksRegistries.registerMachine(separator, new MachineExecutionProfile(1, 200D, 100), separatorLayout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(separator),
                new CloudworksSeparatorRecipeAdapter(RecipeHandler.Recipe.ELECTROLYTIC_SEPARATOR.get()));
        MachineSlotLayout rotaryLayout = new MachineSlotLayout()
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 1000)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 1000);
        ItemStack rotary = new ItemStack(MekanismBlocks.MachineBlock2, 1, 0);
        CloudworksRegistries.registerMachine(rotary, new MachineExecutionProfile(1, 200D, 100), rotaryLayout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(rotary),
                new CloudworksRotaryRecipeAdapter(RecipeHandler.Recipe.ROTARY_CONDENSENTRATOR.get()));
        MachineSlotLayout dissolutionLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 1000);
        ItemStack dissolution = new ItemStack(MekanismBlocks.MachineBlock2, 1, 6);
        CloudworksRegistries.registerMachine(dissolution, new MachineExecutionProfile(1, 400D, 100), dissolutionLayout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(dissolution), new CloudworksDissolutionRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_DISSOLUTION_CHAMBER.get()));
        MachineSlotLayout washerLayout = new MachineSlotLayout()
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 1000);
        ItemStack washer = new ItemStack(MekanismBlocks.MachineBlock2, 1, 7);
        CloudworksRegistries.registerMachine(washer, new MachineExecutionProfile(1, 200D, 100), washerLayout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(washer), new CloudworksGasFluidRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_WASHER.get()));
        MachineSlotLayout gasToGasLayout = new MachineSlotLayout()
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 1000);
        ItemStack isotopic = new ItemStack(MekanismBlocks.MachineBlock3, 1, 9);
        CloudworksRegistries.registerMachine(isotopic, new MachineExecutionProfile(1, 200D, 100), gasToGasLayout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(isotopic),
                new CloudworksGasGasRecipeAdapter(RecipeHandler.Recipe.ISOTOPIC_CENTRIFUGE.get()));
        ItemStack solar = new ItemStack(MekanismBlocks.MachineBlock3, 1, 1);
        CloudworksRegistries.registerMachine(solar, new MachineExecutionProfile(1, 200D, 100), gasToGasLayout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(solar),
                new CloudworksSolarNeutronRecipeAdapter(RecipeHandler.Recipe.SOLAR_NEUTRON_ACTIVATOR.get()));
        MachineSlotLayout nutritionalLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 1000);
        ItemStack nutritional = new ItemStack(MekanismBlocks.MachineBlock3, 1, 10);
        CloudworksRegistries.registerMachine(nutritional, new MachineExecutionProfile(1, 200D, 100), nutritionalLayout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(nutritional),
                new CloudworksNutritionalRecipeAdapter(RecipeHandler.Recipe.NUTRITIONAL_LIQUIFIER.get()));
        MachineSlotLayout nucleosynthLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        ItemStack nucleosynth = new ItemStack(MekanismBlocks.MachineBlock3, 1, 13);
        CloudworksRegistries.registerMachine(nucleosynth, new MachineExecutionProfile(1, 200D, 100), nucleosynthLayout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(nucleosynth),
                new CloudworksNucleosynthesizerRecipeAdapter(RecipeHandler.Recipe.ANTIPROTONIC_NUCLEOSYNTHESIZER.get()));
        MachineSlotLayout farmLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        ItemStack farm = new ItemStack(MekanismBlocks.MachineBlock3, 1, 12);
        CloudworksRegistries.registerMachine(farm, new MachineExecutionProfile(1, 200D, 200), farmLayout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(farm),
                new CloudworksFarmRecipeAdapter(RecipeHandler.Recipe.ORGANIC_FARM.get()));
        MachineSlotLayout chance2Layout = itemLayout;
        registerChance(new ItemStack(MekanismBlocks.MachineBlock4, 1, 6), RecipeHandler.Recipe.CELL_EXTRACTOR.get(), 200, chance2Layout);
        registerChance(new ItemStack(MekanismBlocks.MachineBlock4, 1, 7), RecipeHandler.Recipe.CELL_SEPARATOR.get(), 200, chance2Layout);
        ItemStack recycler = new ItemStack(MekanismBlocks.MachineBlock4, 1, 8);
        CloudworksRegistries.registerMachine(recycler, new MachineExecutionProfile(1, 50D, 200), chance2Layout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(recycler),
                new CloudworksChance2ItemRecipeAdapter(RecipeHandler.Recipe.RECYCLER.get()));
        ItemStack waste = mekanism.common.block.states.BlockStateMachine.MachineType.RADIOACTIVE_WASTE_BARREL.getStack();
        CloudworksRegistries.registerMachine(waste, new MachineExecutionProfile(1, 0, 0,
                        mekanism.common.lib.radiation.RadiationUtil.getWasteBarrelProcessTicks()),
                new MachineSlotLayout().add("gas_input", QIOResourceCodecs.GAS_STACK_ID,
                        MachineSlotLayout.PortRole.INPUT, 512_000), Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(waste), new CloudworksWasteBarrelRecipeAdapter(1));
        registered = true;
    }

    private static void registerAmbientGeneratorsIfPresent() {
        if (!net.minecraftforge.fml.common.Loader.isModLoaded("mekanismgenerators")) return;
        try {
            Class<?> blocksClass = Class.forName("mekanism.generators.common.GeneratorsBlocks");
            net.minecraft.block.Block generator = (net.minecraft.block.Block) blocksClass.getField("Generator").get(null);
            MachineSlotLayout empty = new MachineSlotLayout();
            registerAmbientGenerator(new ItemStack(generator, 1, 6), MekanismConfig.current().generators.windGeneratorStorage.val(),
                    new CloudworksWindGeneratorAdapter(
                            MekanismConfig.current().generators.windGenerationMin.val(),
                            MekanismConfig.current().generators.windGenerationMax.val(),
                            MekanismConfig.current().generators.windGenerationMinY.val(),
                            MekanismConfig.current().generators.windGenerationMaxY.val(), 0), empty);
            registerAmbientGenerator(new ItemStack(generator, 1, 1), MekanismConfig.current().generators.solarGeneratorStorage.val(),
                    new CloudworksSolarGeneratorAdapter(false), empty);
            registerAmbientGenerator(new ItemStack(generator, 1, 5), MekanismConfig.current().generators.advancedSolarGeneratorStorage.val(),
                    new CloudworksSolarGeneratorAdapter(true), empty);
            registerAmbientGenerator(new ItemStack(generator, 1, 0),
                    MekanismConfig.current().generators.heatGeneratorStorage.val(),
                    new CloudworksHeatGeneratorAdapter(),
                    new MachineSlotLayout()
                            .add("item_input", QIOResourceCodecs.ITEM_STACK_ID,
                                    MachineSlotLayout.PortRole.INPUT, 64)
                            .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID,
                                    MachineSlotLayout.PortRole.INPUT, Math.max(1, MekanismConfig.current().generators.heatTankCapacity.val()))
                            .add("item_output", QIOResourceCodecs.ITEM_STACK_ID,
                                    MachineSlotLayout.PortRole.OUTPUT, 64));
            ItemStack heat = new ItemStack(generator, 1, 0);
            CloudworksRegistries.registerSpecial(heat, new CloudworksHeatFuelAdapter());
            CloudworksRegistries.registerThermal(heat,
                    mekanism.cloudworks.common.integration.CloudworksThermalContracts.heatGenerator());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Optional generators module.
        }
    }

    private static void registerAmbientGenerator(ItemStack machine, double capacity,
                                                  mekanism.cloudworks.api.CloudworksEnergyGenerator generator,
                                                  MachineSlotLayout layout) {
        CloudworksRegistries.registerMachine(machine, new MachineExecutionProfile(1, 0D, capacity, 1),
                layout, Collections.emptyList());
        CloudworksRegistries.registerEnergyGenerator(machine, generator);
    }

    static void registerHeaters() {
        ItemStack resistive = MachineType.RESISTIVE_HEATER.getStack();
        try {
            if (!CloudworksRegistries.isRegistered(resistive)) {
                mekanism.cloudworks.api.CloudworksThermalPlan plan =
                        mekanism.cloudworks.common.integration.CloudworksThermalContracts.resistive();
                CloudworksRegistries.registerMachine(resistive, new MachineExecutionProfile(1, 100D, 40000D, 1),
                        new MachineSlotLayout(), Collections.emptyList());
                CloudworksRegistries.registerThermal(resistive, plan);
            }
        } catch (RuntimeException failure) {
            mekanism.common.Mekanism.logger.error("Could not register Cloudworks resistive heater", failure);
        }
        ItemStack fuelwood = MachineType.FUELWOOD_HEATER.getStack();
        try {
            if (!CloudworksRegistries.isRegistered(fuelwood)) {
                mekanism.cloudworks.api.CloudworksThermalPlan plan =
                        mekanism.cloudworks.common.integration.CloudworksThermalContracts.fuelwood();
                CloudworksRegistries.registerMachine(fuelwood, new MachineExecutionProfile(1, 0, 0, 1),
                        new MachineSlotLayout()
                                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64),
                        Collections.emptyList());
                CloudworksRegistries.registerThermal(fuelwood, plan);
            }
        } catch (RuntimeException failure) {
            mekanism.common.Mekanism.logger.error("Could not register Cloudworks fuelwood heater", failure);
        }
    }

    static mekanism.cloudworks.api.CloudworksThermalPlan evaporationThermalPlan() {
        return mekanism.cloudworks.common.integration.CloudworksThermalContracts.evaporation()
                .withEnvironment(0, 0, 4);
    }

    private static void register(ItemStack machine, java.util.Map recipes, int ticks, MachineSlotLayout layout) {
        CloudworksRegistries.registerMachine(machine, new MachineExecutionProfile(1, 50D, ticks), layout,
                Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksBasicItemRecipeAdapter(recipes));
    }

    private static void registerGas(ItemStack machine, java.util.Map recipes, int ticks, MachineSlotLayout layout) {
        CloudworksRegistries.registerMachine(machine, new MachineExecutionProfile(1, 200D, ticks), layout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksAdvancedGasItemRecipeAdapter(recipes));
    }

    private static void registerDouble(ItemStack machine, java.util.Map recipes, int ticks, MachineSlotLayout layout) {
        CloudworksRegistries.registerMachine(machine, new MachineExecutionProfile(1, 50D, ticks), withExtraItemInput(layout), Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksDoubleItemRecipeAdapter(recipes));
    }

    private static void registerGasOutput(ItemStack machine, java.util.Map recipes, int ticks, MachineSlotLayout layout) {
        MachineSlotLayout outputLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 1000);
        CloudworksRegistries.registerMachine(machine, new MachineExecutionProfile(1, 200D, ticks), outputLayout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksItemGasOutputRecipeAdapter(recipes));
    }

    private static void registerChance(ItemStack machine, java.util.Map recipes, int ticks, MachineSlotLayout layout) {
        CloudworksRegistries.registerMachine(machine, new MachineExecutionProfile(1, 50D, ticks), layout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksChanceItemRecipeAdapter(recipes));
    }

    static void registerAdditionalItemMachines(MachineSlotLayout layout) {
        registerNativeItemMachine(MachineType.STAMPING, RecipeHandler.Recipe.STAMPING.get(), layout, false);
        registerNativeItemMachine(MachineType.ROLLING, RecipeHandler.Recipe.ROLLING.get(), layout, false);
        registerNativeItemMachine(MachineType.BRUSHED, RecipeHandler.Recipe.BRUSHED.get(), layout, false);
        registerNativeItemMachine(MachineType.TURNING, RecipeHandler.Recipe.TURNING.get(), layout, false);
        registerNativeItemMachine(MachineType.ALLOY, RecipeHandler.Recipe.ALLOY.get(), layout, true);
    }

    private static void registerNativeItemMachine(MachineType type, java.util.Map recipes,
            MachineSlotLayout layout, boolean extraInput) {
        ItemStack machine = type.getStack();
        CloudworksRegistries.registerMachine(machine,
                new MachineExecutionProfile(1, type.getUsage(), type.getStorage(), 200),
                extraInput ? withExtraItemInput(layout) : layout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), extraInput
                ? new CloudworksDoubleItemRecipeAdapter(recipes) : new CloudworksBasicItemRecipeAdapter(recipes));
    }

    static void registerFactoryModes(MachineSlotLayout layout) {
        registerFactoryMode(5, 3, RecipeType.SMELTING, RecipeHandler.Recipe.ENERGIZED_SMELTER.get(), layout);
        registerFactoryMode(5, 3, RecipeType.ENRICHING, RecipeHandler.Recipe.ENRICHMENT_CHAMBER.get(), layout);
        registerFactoryMode(5, 3, RecipeType.CRUSHING, RecipeHandler.Recipe.CRUSHER.get(), layout);
        registerFactoryMode(6, 5, RecipeType.SMELTING, RecipeHandler.Recipe.ENERGIZED_SMELTER.get(), layout);
        registerFactoryMode(7, 7, RecipeType.SMELTING, RecipeHandler.Recipe.ENERGIZED_SMELTER.get(), layout);
        registerFactoryMode(8, 9, RecipeType.SMELTING, RecipeHandler.Recipe.ENERGIZED_SMELTER.get(), layout);
        registerFactoryMode(6, 5, RecipeType.ENRICHING, RecipeHandler.Recipe.ENRICHMENT_CHAMBER.get(), layout);
        registerFactoryMode(7, 7, RecipeType.ENRICHING, RecipeHandler.Recipe.ENRICHMENT_CHAMBER.get(), layout);
        registerFactoryMode(8, 9, RecipeType.ENRICHING, RecipeHandler.Recipe.ENRICHMENT_CHAMBER.get(), layout);
        registerFactoryMode(6, 5, RecipeType.CRUSHING, RecipeHandler.Recipe.CRUSHER.get(), layout);
        registerFactoryMode(7, 7, RecipeType.CRUSHING, RecipeHandler.Recipe.CRUSHER.get(), layout);
        registerFactoryMode(8, 9, RecipeType.CRUSHING, RecipeHandler.Recipe.CRUSHER.get(), layout);
        MachineSlotLayout factoryGasLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        MachineSlotLayout factoryInfuseLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("infuse_item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("infuse_input", CloudworksInfuseResourceCodec.ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        registerFactoryInfuserMode(5, 3, RecipeType.INFUSING, RecipeHandler.Recipe.METALLURGIC_INFUSER.get(), factoryInfuseLayout);
        registerFactoryInfuserMode(6, 5, RecipeType.INFUSING, RecipeHandler.Recipe.METALLURGIC_INFUSER.get(), factoryInfuseLayout);
        registerFactoryInfuserMode(7, 7, RecipeType.INFUSING, RecipeHandler.Recipe.METALLURGIC_INFUSER.get(), factoryInfuseLayout);
        registerFactoryInfuserMode(8, 9, RecipeType.INFUSING, RecipeHandler.Recipe.METALLURGIC_INFUSER.get(), factoryInfuseLayout);
        registerFactoryGasMode(5, 3, RecipeType.PURIFYING, RecipeHandler.Recipe.PURIFICATION_CHAMBER.get(), factoryGasLayout);
        registerFactoryGasMode(6, 5, RecipeType.PURIFYING, RecipeHandler.Recipe.PURIFICATION_CHAMBER.get(), factoryGasLayout);
        registerFactoryGasMode(7, 7, RecipeType.PURIFYING, RecipeHandler.Recipe.PURIFICATION_CHAMBER.get(), factoryGasLayout);
        registerFactoryGasMode(8, 9, RecipeType.PURIFYING, RecipeHandler.Recipe.PURIFICATION_CHAMBER.get(), factoryGasLayout);
        registerFactoryGasMode(5, 3, RecipeType.INJECTING, RecipeHandler.Recipe.CHEMICAL_INJECTION_CHAMBER.get(), factoryGasLayout);
        registerFactoryGasMode(6, 5, RecipeType.INJECTING, RecipeHandler.Recipe.CHEMICAL_INJECTION_CHAMBER.get(), factoryGasLayout);
        registerFactoryGasMode(7, 7, RecipeType.INJECTING, RecipeHandler.Recipe.CHEMICAL_INJECTION_CHAMBER.get(), factoryGasLayout);
        registerFactoryGasMode(8, 9, RecipeType.INJECTING, RecipeHandler.Recipe.CHEMICAL_INJECTION_CHAMBER.get(), factoryGasLayout);
        registerFactoryDoubleMode(5, 3, RecipeType.COMBINING, RecipeHandler.Recipe.COMBINER.get(), layout);
        registerFactoryDoubleMode(6, 5, RecipeType.COMBINING, RecipeHandler.Recipe.COMBINER.get(), layout);
        registerFactoryDoubleMode(7, 7, RecipeType.COMBINING, RecipeHandler.Recipe.COMBINER.get(), layout);
        registerFactoryDoubleMode(8, 9, RecipeType.COMBINING, RecipeHandler.Recipe.COMBINER.get(), layout);
        registerFactoryDoubleMode(5, 3, RecipeType.AllOY, RecipeHandler.Recipe.ALLOY.get(), layout);
        registerFactoryDoubleMode(6, 5, RecipeType.AllOY, RecipeHandler.Recipe.ALLOY.get(), layout);
        registerFactoryDoubleMode(7, 7, RecipeType.AllOY, RecipeHandler.Recipe.ALLOY.get(), layout);
        registerFactoryDoubleMode(8, 9, RecipeType.AllOY, RecipeHandler.Recipe.ALLOY.get(), layout);
        registerFactoryAdvancedMode(5, 3, RecipeType.COMPRESSING, RecipeHandler.Recipe.OSMIUM_COMPRESSOR.get(), factoryGasLayout);
        registerFactoryAdvancedMode(6, 5, RecipeType.COMPRESSING, RecipeHandler.Recipe.OSMIUM_COMPRESSOR.get(), factoryGasLayout);
        registerFactoryAdvancedMode(7, 7, RecipeType.COMPRESSING, RecipeHandler.Recipe.OSMIUM_COMPRESSOR.get(), factoryGasLayout);
        registerFactoryAdvancedMode(8, 9, RecipeType.COMPRESSING, RecipeHandler.Recipe.OSMIUM_COMPRESSOR.get(), factoryGasLayout);
        registerFactoryChanceMode(5, 3, RecipeType.SAWING, RecipeHandler.Recipe.PRECISION_SAWMILL.get(), layout);
        registerFactoryChanceMode(6, 5, RecipeType.SAWING, RecipeHandler.Recipe.PRECISION_SAWMILL.get(), layout);
        registerFactoryChanceMode(7, 7, RecipeType.SAWING, RecipeHandler.Recipe.PRECISION_SAWMILL.get(), layout);
        registerFactoryChanceMode(8, 9, RecipeType.SAWING, RecipeHandler.Recipe.PRECISION_SAWMILL.get(), layout);
        registerFactoryChanceMode(5, 3, RecipeType.EXTRACTOR, RecipeHandler.Recipe.CELL_EXTRACTOR.get(), layout);
        registerFactoryChanceMode(6, 5, RecipeType.EXTRACTOR, RecipeHandler.Recipe.CELL_EXTRACTOR.get(), layout);
        registerFactoryChanceMode(7, 7, RecipeType.EXTRACTOR, RecipeHandler.Recipe.CELL_EXTRACTOR.get(), layout);
        registerFactoryChanceMode(8, 9, RecipeType.EXTRACTOR, RecipeHandler.Recipe.CELL_EXTRACTOR.get(), layout);
        registerFactoryChanceMode(5, 3, RecipeType.SEPARATOR, RecipeHandler.Recipe.CELL_SEPARATOR.get(), layout);
        registerFactoryChanceMode(6, 5, RecipeType.SEPARATOR, RecipeHandler.Recipe.CELL_SEPARATOR.get(), layout);
        registerFactoryChanceMode(7, 7, RecipeType.SEPARATOR, RecipeHandler.Recipe.CELL_SEPARATOR.get(), layout);
        registerFactoryChanceMode(8, 9, RecipeType.SEPARATOR, RecipeHandler.Recipe.CELL_SEPARATOR.get(), layout);
        registerFactoryChance2Mode(5, 3, RecipeType.RECYCLER, RecipeHandler.Recipe.RECYCLER.get(), layout);
        registerFactoryChance2Mode(6, 5, RecipeType.RECYCLER, RecipeHandler.Recipe.RECYCLER.get(), layout);
        registerFactoryChance2Mode(7, 7, RecipeType.RECYCLER, RecipeHandler.Recipe.RECYCLER.get(), layout);
        registerFactoryChance2Mode(8, 9, RecipeType.RECYCLER, RecipeHandler.Recipe.RECYCLER.get(), layout);
        registerFactoryMode(5, 3, RecipeType.STAMPING, RecipeHandler.Recipe.STAMPING.get(), layout);
        registerFactoryMode(6, 5, RecipeType.STAMPING, RecipeHandler.Recipe.STAMPING.get(), layout);
        registerFactoryMode(7, 7, RecipeType.STAMPING, RecipeHandler.Recipe.STAMPING.get(), layout);
        registerFactoryMode(8, 9, RecipeType.STAMPING, RecipeHandler.Recipe.STAMPING.get(), layout);
        registerFactoryMode(5, 3, RecipeType.ROLLING, RecipeHandler.Recipe.ROLLING.get(), layout);
        registerFactoryMode(6, 5, RecipeType.ROLLING, RecipeHandler.Recipe.ROLLING.get(), layout);
        registerFactoryMode(7, 7, RecipeType.ROLLING, RecipeHandler.Recipe.ROLLING.get(), layout);
        registerFactoryMode(8, 9, RecipeType.ROLLING, RecipeHandler.Recipe.ROLLING.get(), layout);
        registerFactoryMode(5, 3, RecipeType.BRUSHED, RecipeHandler.Recipe.BRUSHED.get(), layout);
        registerFactoryMode(6, 5, RecipeType.BRUSHED, RecipeHandler.Recipe.BRUSHED.get(), layout);
        registerFactoryMode(7, 7, RecipeType.BRUSHED, RecipeHandler.Recipe.BRUSHED.get(), layout);
        registerFactoryMode(8, 9, RecipeType.BRUSHED, RecipeHandler.Recipe.BRUSHED.get(), layout);
        registerFactoryMode(5, 3, RecipeType.TURNING, RecipeHandler.Recipe.TURNING.get(), layout);
        registerFactoryMode(6, 5, RecipeType.TURNING, RecipeHandler.Recipe.TURNING.get(), layout);
        registerFactoryMode(7, 7, RecipeType.TURNING, RecipeHandler.Recipe.TURNING.get(), layout);
        registerFactoryMode(8, 9, RecipeType.TURNING, RecipeHandler.Recipe.TURNING.get(), layout);
        MachineSlotLayout prcLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 1000);
        registerFactoryPressurizedMode(5, 3, RecipeType.PRC, RecipeHandler.Recipe.PRESSURIZED_REACTION_CHAMBER.get(), prcLayout);
        registerFactoryPressurizedMode(6, 5, RecipeType.PRC, RecipeHandler.Recipe.PRESSURIZED_REACTION_CHAMBER.get(), prcLayout);
        registerFactoryPressurizedMode(7, 7, RecipeType.PRC, RecipeHandler.Recipe.PRESSURIZED_REACTION_CHAMBER.get(), prcLayout);
        registerFactoryPressurizedMode(8, 9, RecipeType.PRC, RecipeHandler.Recipe.PRESSURIZED_REACTION_CHAMBER.get(), prcLayout);
        registerFactoryNucleosynthesizerMode(5, 3);
        registerFactoryNucleosynthesizerMode(6, 5);
        registerFactoryNucleosynthesizerMode(7, 7);
        registerFactoryNucleosynthesizerMode(8, 9);
    }

    private static void registerFactoryMode(int metadata, int threads, RecipeType type, java.util.Map recipes, MachineSlotLayout layout) {
        ItemStack machine = factoryStack(metadata, type);
        CloudworksRegistries.registerMachine(machine, factoryProfile(threads, type), layout, Collections.emptyList(), MachineIdentityPolicy.keys("recipeTypeName", "recipeType", "recipeTypeVersion"));
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksBasicItemRecipeAdapter(recipes));
    }

    private static void registerFactoryGasMode(int metadata, int threads, RecipeType type, java.util.Map recipes, MachineSlotLayout layout) {
        ItemStack machine = factoryStack(metadata, type);
        CloudworksRegistries.registerMachine(machine, factoryProfile(threads, type), layout, Collections.emptyList(), MachineIdentityPolicy.keys("recipeTypeName", "recipeType", "recipeTypeVersion"));
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksAdvancedGasItemRecipeAdapter(recipes));
    }

    private static void registerFactoryDoubleMode(int metadata, int threads, RecipeType type, java.util.Map recipes, MachineSlotLayout layout) {
        ItemStack machine = factoryStack(metadata, type);
        CloudworksRegistries.registerMachine(machine, factoryProfile(threads, type), withExtraItemInput(layout), Collections.emptyList(), MachineIdentityPolicy.keys("recipeTypeName", "recipeType", "recipeTypeVersion"));
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksDoubleItemRecipeAdapter(recipes));
    }

    private static MachineSlotLayout withExtraItemInput(MachineSlotLayout layout) {
        MachineSlotLayout result = new MachineSlotLayout();
        for (MachineSlotLayout.Slot slot : layout.slots()) {
            result.add(slot.id(), slot.codecId(), slot.role(), slot.capacity(), slot.multipleConnections());
            if (slot.id().equals("item_input"))
                result.add("extra_item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64);
        }
        return result;
    }

    private static void registerPressurized(ItemStack machine, java.util.Map recipes, int ticks, MachineSlotLayout layout) {
        CloudworksRegistries.registerMachine(machine, new MachineExecutionProfile(1, 200D, ticks), layout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksPressurizedRecipeAdapter(recipes));
    }

    private static void registerInfuser(ItemStack machine, java.util.Map recipes, int ticks, MachineSlotLayout layout) {
        CloudworksRegistries.registerMachine(machine, new MachineExecutionProfile(1, 50D, ticks), layout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksInfuserRecipeAdapter(recipes));
    }

    private static void registerAmbient(ItemStack machine, java.util.Map recipes, int ticks, MachineSlotLayout layout) {
        CloudworksRegistries.registerMachine(machine, new MachineExecutionProfile(1, 50D, ticks), layout, Collections.emptyList());
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksAmbientGasRecipeAdapter(recipes));
    }

    private static void registerGasGeneratorIfPresent() {
        if (!net.minecraftforge.fml.common.Loader.isModLoaded("mekanismgenerators")) return;
        try {
            Class<?> blocksClass = Class.forName("mekanism.generators.common.GeneratorsBlocks");
            net.minecraft.block.Block generator = (net.minecraft.block.Block) blocksClass.getField("Generator").get(null);
            ItemStack machine = new ItemStack(generator, 1, 3);
            MachineSlotLayout layout = new MachineSlotLayout()
                    .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 18000);
            CloudworksGasGeneratorAdapter adapter = new CloudworksGasGeneratorAdapter(RecipeHandler.Recipe.GAS_FUEL_TO_ENERGY_RECIPE.get());
            CloudworksRegistries.registerMachine(machine, new MachineExecutionProfile(1, 0D, 100000D, 1), layout, Collections.emptyList());
            CloudworksRegistries.registerSpecial(machine, adapter);
            CloudworksRegistries.registerEnergyGenerator(machine, adapter);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Generators is optional; its absence or incomplete registry must not
            // prevent the core Cloudworks module from loading.
        }
    }


    private static void registerFactoryAdvancedMode(int metadata, int threads, RecipeType type, java.util.Map recipes, MachineSlotLayout layout) {
        ItemStack machine = factoryStack(metadata, type);
        CloudworksRegistries.registerMachine(machine, factoryProfile(threads, type), layout,
                Collections.emptyList(), MachineIdentityPolicy.keys("recipeTypeName", "recipeType", "recipeTypeVersion"));
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksAdvancedGasItemRecipeAdapter(recipes));
    }

    private static void registerFactoryChanceMode(int metadata, int threads, RecipeType type, java.util.Map recipes, MachineSlotLayout layout) {
        ItemStack machine = factoryStack(metadata, type);
        CloudworksRegistries.registerMachine(machine, factoryProfile(threads, type), layout,
                Collections.emptyList(), MachineIdentityPolicy.keys("recipeTypeName", "recipeType", "recipeTypeVersion"));
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksChanceItemRecipeAdapter(recipes));
    }

    private static void registerFactoryPressurizedMode(int metadata, int threads, RecipeType type, java.util.Map recipes, MachineSlotLayout layout) {
        ItemStack machine = factoryStack(metadata, type);
        CloudworksRegistries.registerMachine(machine, factoryProfile(threads, type), layout,
                Collections.emptyList(), MachineIdentityPolicy.keys("recipeTypeName", "recipeType", "recipeTypeVersion"));
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksPressurizedRecipeAdapter(recipes));
    }

    private static void registerFactoryInfuserMode(int metadata, int threads, RecipeType type, java.util.Map recipes, MachineSlotLayout layout) {
        ItemStack machine = factoryStack(metadata, type);
        CloudworksRegistries.registerMachine(machine, factoryProfile(threads, type), layout,
                Collections.emptyList(), MachineIdentityPolicy.keys("recipeTypeName", "recipeType", "recipeTypeVersion"));
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksInfuserRecipeAdapter(recipes));
    }

    private static void registerFactoryChance2Mode(int metadata, int threads, RecipeType type, java.util.Map recipes, MachineSlotLayout layout) {
        ItemStack machine = factoryStack(metadata, type);
        CloudworksRegistries.registerMachine(machine, factoryProfile(threads, type), layout,
                Collections.emptyList(), MachineIdentityPolicy.keys("recipeTypeName", "recipeType", "recipeTypeVersion"));
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), new CloudworksChance2ItemRecipeAdapter(recipes));
    }

    private static void registerFactoryNucleosynthesizerMode(int metadata, int threads) {
        ItemStack machine = factoryStack(metadata, RecipeType.NUCLEOSYNTHESIZER);
        MachineSlotLayout nucleosynthLayout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1000)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        CloudworksRegistries.registerMachine(machine, factoryProfile(threads, RecipeType.NUCLEOSYNTHESIZER),
                nucleosynthLayout, Collections.emptyList(),
                MachineIdentityPolicy.keys("recipeTypeName", "recipeType", "recipeTypeVersion"));
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine),
                new CloudworksNucleosynthesizerRecipeAdapter(RecipeHandler.Recipe.ANTIPROTONIC_NUCLEOSYNTHESIZER.get()));
    }

    private static ItemStack factoryStack(int metadata, RecipeType type) {
        mekanism.common.block.states.BlockStateMachine.MachineType machineType = factoryMachineType(metadata);
        ItemStack machine = new ItemStack(machineType.typeBlock.getBlock(), 1, machineType.meta);
        net.minecraft.nbt.NBTTagCompound data = new net.minecraft.nbt.NBTTagCompound();
        FactoryRecipeTypeCodec.write(data, type);
        machine.setTagCompound(data);
        return machine;
    }

    static mekanism.common.block.states.BlockStateMachine.MachineType factoryMachineType(int legacyTier) {
        return switch (legacyTier) {
            case 5 -> mekanism.common.block.states.BlockStateMachine.MachineType.BASIC_FACTORY;
            case 6 -> mekanism.common.block.states.BlockStateMachine.MachineType.ADVANCED_FACTORY;
            case 7 -> mekanism.common.block.states.BlockStateMachine.MachineType.ELITE_FACTORY;
            case 8 -> mekanism.common.block.states.BlockStateMachine.MachineType.ULTIMATE_FACTORY;
            default -> throw new IllegalArgumentException("Unknown built-in factory tier");
        };
    }

    private static MachineExecutionProfile factoryProfile(int threads, RecipeType type) {
        return new MachineExecutionProfile(threads, type.getEnergyUsage(), threads * type.getEnergyStorage(), 200);
    }
}
