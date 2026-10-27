package mekanism.cloudworks.common.integration.moremachine;

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
import mekanism.cloudworks.common.recipe.*;
import mekanism.common.base.ITierItem;
import mekanism.common.block.states.BlockStateMachine.MachineType;
import mekanism.common.config.MekanismConfig;
import mekanism.common.recipe.RecipeHandler;
import mekceumoremachine.common.config.MoreMachineConfig;
import mekceumoremachine.common.registries.MEKCeuMoreMachineBlocks;
import mekceumoremachine.common.tier.MachineTier;
import mekceumoremachine.common.util.VoidMineralGeneratorUitls;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;
import java.util.List;
import mekanism.common.lib.radiation.RadiationUtil;
import mekanism.common.tier.GasTankTier;
import mekceumoremachine.common.block.states.BlockStateTierChemicalCrystallizer;
import mekceumoremachine.common.block.states.BlockStateTierChemicalDissolutionChamber;
import mekceumoremachine.common.block.states.BlockStateTierChemicalOxidizer;
import mekceumoremachine.common.block.states.BlockStateTierNutritionalLiquifier;
import mekceumoremachine.common.block.states.BlockStateTierOrganicFarm;

/** MoreMachine references stay behind CloudworksHooks' optional-mod boundary. No tiles or Worlds are created. */
public final class CloudworksMoreMachineIntegration {
    static final MachineIdentityPolicy TIER = MachineIdentityPolicy.tierItem();
    private static final CloudworksMachineAdapter CHEMICAL_INFUSER_ADAPTER =
            new CloudworksChemicalPairRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_INFUSER.get());
    private static final CloudworksMachineAdapter CHEMICAL_WASHER_ADAPTER =
            new CloudworksGasFluidRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_WASHER.get());
    // TileEntityElectrolyticSeparator:188-197 replaces the base rate with each
    // recipe's energyUsage — per-recipe accounting like the Builtins and
    // MultiblockMachine separator registrations.
    private static final CloudworksMachineAdapter ELECTROLYTIC_SEPARATOR_ADAPTER =
            new CloudworksSeparatorRecipeAdapter(RecipeHandler.Recipe.ELECTROLYTIC_SEPARATOR.get(), true);
    private static final CloudworksMachineAdapter ISOTOPIC_CENTRIFUGE_ADAPTER =
            new CloudworksGasGasRecipeAdapter(RecipeHandler.Recipe.ISOTOPIC_CENTRIFUGE.get());
    private static final CloudworksMachineAdapter AMBIENT_ACCUMULATOR_ADAPTER =
            new CloudworksAmbientGasRecipeAdapter(RecipeHandler.Recipe.AMBIENT_ACCUMULATOR.get());
    private static final CloudworksMachineAdapter SOLAR_NEUTRON_ACTIVATOR_ADAPTER =
            new CloudworksSolarNeutronRecipeAdapter(RecipeHandler.Recipe.SOLAR_NEUTRON_ACTIVATOR.get());
    private static final CloudworksMachineAdapter CHEMICAL_CRYSTALLIZER_ADAPTER =
            new CloudworksGasItemRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_CRYSTALLIZER.get());
    private static final CloudworksMachineAdapter CHEMICAL_OXIDIZER_ADAPTER =
            new CloudworksItemGasOutputRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_OXIDIZER.get());
    private static final CloudworksMachineAdapter NUTRITIONAL_LIQUIFIER_ADAPTER =
            new CloudworksNutritionalRecipeAdapter(RecipeHandler.Recipe.NUTRITIONAL_LIQUIFIER.get());
    private static final CloudworksMachineAdapter CHEMICAL_DISSOLUTION_CHAMBER_ADAPTER =
            new CloudworksDissolutionRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_DISSOLUTION_CHAMBER.get());
    private static final CloudworksMachineAdapter ORGANIC_FARM_ADAPTER =
            new CloudworksFarmRecipeAdapter(RecipeHandler.Recipe.ORGANIC_FARM.get(), 200);
    private static boolean registered;

    private CloudworksMoreMachineIntegration() {}

    public static synchronized void register() {
        if (registered) return;
        TierGeneratorAdapters generators = tierGeneratorAdapters();
        for (MachineTier tier : MachineTier.values()) {
            int processes = tier.processes;
            mekanism.cloudworks.common.integration.CloudworksPumpIntegration.register(
                    tierStack(MEKCeuMoreMachineBlocks.TierElectricPump, tier), processes, TIER);
            int gas = 10_000 * processes;
            processing(tierStack(MEKCeuMoreMachineBlocks.TierChemicalInfuser, tier), MachineType.CHEMICAL_INFUSER,
                    processes, 1, gases(gas, true), CHEMICAL_INFUSER_ADAPTER, TIER);
            processing(tierStack(MEKCeuMoreMachineBlocks.TierChemicalWasher, tier), MachineType.CHEMICAL_WASHER,
                    processes, 1, new MachineSlotLayout()
                            .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, gas)
                            .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, gas)
                            .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, gas),
                    CHEMICAL_WASHER_ADAPTER, TIER);
            // One operation per tick with a zero base energy rate: the whole cost
            // rides the per-recipe axis (same registration shape as the Builtins
            // single machine and the MultiblockMachine large separator).
            register(tierStack(MEKCeuMoreMachineBlocks.TierElectrolyticSeparator, tier),
                    new MachineExecutionProfile(processes, 0D,
                            MachineType.ELECTROLYTIC_SEPARATOR.getStorage() * processes, 1),
                    new MachineSlotLayout()
                            .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 24_000 * processes)
                            .add("gas_output_left", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 2_400 * processes)
                            .add("gas_output_right", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 2_400 * processes),
                    ELECTROLYTIC_SEPARATOR_ADAPTER, TIER);
            processing(tierStack(MEKCeuMoreMachineBlocks.TierIsotopicCentrifuge, tier), MachineType.ISOTOPIC_CENTRIFUGE,
                    processes, 1, gases(gas, false), ISOTOPIC_CENTRIFUGE_ADAPTER, TIER);
            processing(tierStack(MEKCeuMoreMachineBlocks.TierAmbientAccumulator, tier), MachineType.AMBIENT_ACCUMULATOR_ENERGY,
                    processes, 1, new MachineSlotLayout().add("gas_output", QIOResourceCodecs.GAS_STACK_ID,
                            MachineSlotLayout.PortRole.OUTPUT, GasTankTier.BASIC.getBaseStorage() * processes),
                    AMBIENT_ACCUMULATOR_ADAPTER, TIER);
            ItemStack solar = tierStack(MEKCeuMoreMachineBlocks.TierSolarNeutronActivator, tier);
            register(solar, new MachineExecutionProfile(processes, 0, 0, 1, MachineUpgradePolicy.MEKANISM_SPEED), gases(gas, false),
                    SOLAR_NEUTRON_ACTIVATOR_ADAPTER, TIER);
            processing(tierStack(MEKCeuMoreMachineBlocks.TierRotaryCondensentrator, tier),
                    MachineType.ROTARY_CONDENSENTRATOR, processes, 1,
                    CloudworksRotaryRecipeAdapter.fluidToGasLayout(gas),
                    CloudworksRotaryRecipeAdapter.recipeTypes(RecipeHandler.Recipe.ROTARY_CONDENSENTRATOR.get(), gas), TIER);
            ItemStack mineral = tierStack(MEKCeuMoreMachineBlocks.VoidMineralGenerator, tier);
            register(mineral, new MachineExecutionProfile(1, MoreMachineConfig.current().config.VoidMineralGeneratorEnergyUsage.val(),
                            MoreMachineConfig.current().config.VoidMineralGeneratorEnergyStorge.val() * processes,
                            MoreMachineConfig.current().config.VoidMineralGeneratorTick.val()),
                    new MachineSlotLayout().add("item_output", QIOResourceCodecs.ITEM_STACK_ID,
                            MachineSlotLayout.PortRole.OUTPUT, 81 * 64),
                    new CloudworksVoidMineralRecipeAdapter(VoidMineralGeneratorUitls.getCanOre(), processes), TIER);
            CloudworksAPI.acceptMachineUpgrade(mineral,
                    mekanism.cloudworks.common.integration.multiblockmachine.CloudworksThreadModule.type(),
                    mekanism.multiblockmachine.common.MultiblockMachineUpgrades.THREAD.getMaxInstalled());
            register(tierStack(MEKCeuMoreMachineBlocks.TierRadioactiveWasteBarrel, tier),
                    new MachineExecutionProfile(1, 0, 0, RadiationUtil.getWasteBarrelProcessTicks(),
                            MachineUpgradePolicy.NONE),
                    new MachineSlotLayout().add("gas_input", QIOResourceCodecs.GAS_STACK_ID,
                            MachineSlotLayout.PortRole.INPUT, 512_000 * processes),
                    new CloudworksWasteBarrelRecipeAdapter(processes * 10), TIER);
            if (generators != null) registerTierGenerators(tier, generators);
        }
        for (BlockStateTierChemicalCrystallizer.MachineType type :
                BlockStateTierChemicalCrystallizer.MachineType.getValidMachines())
            processing(type.getStack(), MachineType.CHEMICAL_CRYSTALLIZER, type.tier.processes, 200,
                    new MachineSlotLayout()
                            .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10_000 * type.tier.processes)
                            .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64 * type.tier.processes),
                    CHEMICAL_CRYSTALLIZER_ADAPTER, MachineIdentityPolicy.none());
        for (BlockStateTierChemicalOxidizer.MachineType type :
                BlockStateTierChemicalOxidizer.MachineType.getValidMachines())
            processing(type.getStack(), MachineType.CHEMICAL_OXIDIZER, type.tier.processes, 100,
                    itemToGas(type.tier.processes, false),
                    CHEMICAL_OXIDIZER_ADAPTER, MachineIdentityPolicy.none());
        for (BlockStateTierNutritionalLiquifier.MachineType type :
                BlockStateTierNutritionalLiquifier.MachineType.getValidMachines())
            processing(type.getStack(), MachineType.NUTRITIONAL_LIQUIFIER, type.tier.processes, 100,
                    itemToGas(type.tier.processes, false),
                    NUTRITIONAL_LIQUIFIER_ADAPTER, MachineIdentityPolicy.none());
        for (BlockStateTierChemicalDissolutionChamber.MachineType type :
                BlockStateTierChemicalDissolutionChamber.MachineType.getValidMachines())
            processing(type.getStack(), MachineType.CHEMICAL_DISSOLUTION_CHAMBER, type.tier.processes, 100,
                    itemToGas(type.tier.processes, true),
                    CHEMICAL_DISSOLUTION_CHAMBER_ADAPTER, MachineIdentityPolicy.none());
        registerOrganicFarms();
        register(new ItemStack(MEKCeuMoreMachineBlocks.ReplicatorItemStack),
                new MachineExecutionProfile(1, MoreMachineConfig.current().config.ReplicatorItemStackEnergyUsage.val(),
                        MoreMachineConfig.current().config.ReplicatorItemStackEnergyStorge.val(), 200),
                new MachineSlotLayout()
                        .add("item_template", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.CONFIGURATION, 64, false)
                        .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10_000)
                        .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64),
                new CloudworksReplicatorItemRecipeAdapter(RecipeHandler.Recipe.REPLICATOR_ITEMSTACK_RECIPE.get()),
                MachineIdentityPolicy.none());
        register(new ItemStack(MEKCeuMoreMachineBlocks.ReplicatorGases),
                new MachineExecutionProfile(1, MoreMachineConfig.current().config.ReplicatorGasesEnergyUsage.val(),
                        MoreMachineConfig.current().config.ReplicatorGasesEnergyStorge.val(), 200),
                new MachineSlotLayout()
                        .add("gas_template", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.CONFIGURATION, 10_000, false)
                        .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10_000)
                        .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 10_000),
                new CloudworksReplicatorGasRecipeAdapter(RecipeHandler.Recipe.REPLICATOR_GASES_RECIPE.get()),
                MachineIdentityPolicy.none());
        register(new ItemStack(MEKCeuMoreMachineBlocks.ReplicatorFluidStack),
                new MachineExecutionProfile(1, MoreMachineConfig.current().config.ReplicatorFluidStackEnergyUsage.val(),
                        MoreMachineConfig.current().config.ReplicatorFluidStackEnergyStorge.val(), 200),
                new MachineSlotLayout()
                        .add("fluid_template", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.CONFIGURATION, 10_000, false)
                        .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10_000)
                        .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 10_000),
                new CloudworksReplicatorFluidRecipeAdapter(RecipeHandler.Recipe.REPLICATOR_FLUIDSTACK_RECIPE.get()),
                MachineIdentityPolicy.none());
        registered = true;
    }

    private static TierGeneratorAdapters tierGeneratorAdapters() {
        if (!Loader.isModLoaded("mekanismgenerators")) return null;
        return new TierGeneratorAdapters(
                new CloudworksGasGeneratorAdapter(RecipeHandler.Recipe.GAS_FUEL_TO_ENERGY_RECIPE.get()),
                new CloudworksWindGeneratorAdapter(
                        MekanismConfig.current().generators.windGenerationMin.val(),
                        MekanismConfig.current().generators.windGenerationMax.val(),
                        MekanismConfig.current().generators.windGenerationMinY.val(),
                        MekanismConfig.current().generators.windGenerationMaxY.val(), 0),
                new CloudworksSolarGeneratorAdapter(false), new CloudworksSolarGeneratorAdapter(true));
    }

    private static void registerTierGenerators(MachineTier tier, TierGeneratorAdapters adapters) {
        int processes = tier.processes;
        ItemStack gasGenerator = tierStack(MEKCeuMoreMachineBlocks.TierGasGenerator, tier);
        CloudworksAPI.registerMachine(gasGenerator, new MachineExecutionProfile(processes, 0,
                        MekanismConfig.current().general.FROM_H2.val() * 1000 * processes, 1, MachineUpgradePolicy.NONE),
                new MachineSlotLayout().add("gas_input", QIOResourceCodecs.GAS_STACK_ID,
                        MachineSlotLayout.PortRole.INPUT, 18_000 * processes), adapters.gasFuel, TIER);
        ambientGenerator(tierStack(MEKCeuMoreMachineBlocks.TierWindGenerator, tier), processes,
                MekanismConfig.current().generators.windGeneratorStorage.val() * processes, adapters.wind);
        ambientGenerator(tierStack(MEKCeuMoreMachineBlocks.TierSolarGenerator, tier), processes,
                MekanismConfig.current().generators.solarGeneratorStorage.val() * processes, adapters.solar);
        ambientGenerator(tierStack(MEKCeuMoreMachineBlocks.TierAdvancedSolarGenerator, tier), processes,
                MekanismConfig.current().generators.advancedSolarGeneratorStorage.val() * processes,
                adapters.advancedSolar);
    }

    private static final class TierGeneratorAdapters {
        private final CloudworksGasGeneratorAdapter gasFuel;
        private final CloudworksWindGeneratorAdapter wind;
        private final CloudworksSolarGeneratorAdapter solar, advancedSolar;

        private TierGeneratorAdapters(CloudworksGasGeneratorAdapter gasFuel, CloudworksWindGeneratorAdapter wind,
                CloudworksSolarGeneratorAdapter solar, CloudworksSolarGeneratorAdapter advancedSolar) {
            this.gasFuel = gasFuel;
            this.wind = wind;
            this.solar = solar;
            this.advancedSolar = advancedSolar;
        }
    }

    static void registerOrganicFarms() {
        for (BlockStateTierOrganicFarm.MachineType type :
                BlockStateTierOrganicFarm.MachineType.getValidMachines()) {
            processing(type.getStack(), MachineType.ORGANIC_FARM, type.tier.processes, 200,
                    CloudworksFarmRecipeAdapter.layout(type.tier.processes, true),
                    CloudworksFarmRecipeAdapter.recipeTypes(RecipeHandler.Recipe.ORGANIC_FARM.get(), type.tier.processes),
                    MachineIdentityPolicy.none());
            CloudworksAPI.acceptMachineUpgrade(type.getStack(), mekanism.cloudworks.common.integration.CloudworksGasUpgrade.type(),
                    mekanism.common.Upgrade.GAS.getMaxInstalled());
        }
    }

    static ItemStack tierStack(Block block, MachineTier tier) {
        ItemStack stack = new ItemStack(block);
        if (!(stack.getItem() instanceof ITierItem)) {
            throw new IllegalStateException("MoreMachine tier block does not expose Mekanism's tier item API: " + block);
        }
        ((ITierItem) stack.getItem()).setBaseTier(stack, tier.getBaseTier());
        return stack;
    }

    private static MachineSlotLayout gases(int capacity, boolean pairInput) {
        MachineSlotLayout layout = new MachineSlotLayout();
        layout.add(pairInput ? "gas_input_left" : "gas_input", QIOResourceCodecs.GAS_STACK_ID,
                MachineSlotLayout.PortRole.INPUT, capacity);
        if (pairInput) layout.add("gas_input_right", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, capacity);
        layout.add("gas_output", QIOResourceCodecs.GAS_STACK_ID,
                MachineSlotLayout.PortRole.OUTPUT, capacity);
        return layout;
    }

    private static MachineSlotLayout itemToGas(int processes, boolean fuelInput) {
        MachineSlotLayout layout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64 * processes);
        if (fuelInput) layout.add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10_000 * processes);
        return layout.add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 10_000 * processes);
    }

    private static void processing(ItemStack stack, MachineType type, int processes, int ticks,
            MachineSlotLayout layout, CloudworksMachineAdapter adapter, MachineIdentityPolicy identity) {
        register(stack, new MachineExecutionProfile(processes, type.getUsage(), type.getStorage() * processes, ticks),
                layout, adapter, identity);
        if (type == MachineType.CHEMICAL_DISSOLUTION_CHAMBER || type == MachineType.ORGANIC_FARM)
            CloudworksAPI.acceptMachineUpgrade(stack, mekanism.cloudworks.common.integration.CloudworksGasUpgrade.type(),
                    mekanism.common.Upgrade.GAS.getMaxInstalled());
    }

    private static void processing(ItemStack stack, MachineType type, int processes, int ticks,
            MachineSlotLayout layout, List<CloudworksRecipeMode> recipes, MachineIdentityPolicy identity) {
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(stack,
                new MachineExecutionProfile(processes, type.getUsage(), type.getStorage() * processes, ticks),
                layout, identity, recipes));
    }

    static void register(ItemStack stack, MachineExecutionProfile profile, MachineSlotLayout layout,
            CloudworksMachineAdapter adapter, MachineIdentityPolicy identity) {
        CloudworksAPI.registerMachine(stack, profile, layout, adapter, identity);
    }

    private static void ambientGenerator(ItemStack stack, int processes, double storage, CloudworksEnergyGenerator generator) {
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(stack,
                new MachineExecutionProfile(processes, 0, storage, 1, MachineUpgradePolicy.NONE),
                new MachineSlotLayout(), TIER, Collections.emptyList(), generator, null));
        if (generator instanceof CloudworksWindGeneratorAdapter)
            CloudworksAPI.acceptMachineUpgrade(stack, MachineModuleType.height(),
                    ((CloudworksWindGeneratorAdapter) generator).heightLimit());
    }
}
