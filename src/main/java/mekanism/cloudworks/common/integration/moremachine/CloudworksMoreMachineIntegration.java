package mekanism.cloudworks.common.integration.moremachine;

import java.util.Collections;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.CloudworksMachineAdapter;
import mekanism.cloudworks.common.recipe.*;
import mekanism.cloudworks.common.registry.CloudworksMachineAdapters;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.registry.MachineExecutionProfile;
import mekanism.cloudworks.common.registry.MachineIdentityPolicy;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import mekanism.common.block.states.BlockStateMachine.MachineType;
import mekanism.common.config.MekanismConfig;
import mekanism.common.recipe.RecipeHandler;
import mekceumoremachine.common.config.MoreMachineConfig;
import mekceumoremachine.common.registries.MEKCeuMoreMachineBlocks;
import mekceumoremachine.common.tier.MachineTier;
import mekceumoremachine.common.util.VoidMineralGeneratorUitls;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.Loader;

/** MoreMachine references stay behind CloudworksHooks' optional-mod boundary. No tiles or Worlds are created. */
public final class CloudworksMoreMachineIntegration {
    static final MachineIdentityPolicy TIER = MachineIdentityPolicy.keys("tier").defaultInt("tier", 0);
    private static boolean registered;

    private CloudworksMoreMachineIntegration() {}

    public static synchronized void register() {
        if (registered) return;
        for (MachineTier tier : MachineTier.values()) {
            int processes = tier.processes;
            int gas = 10_000 * processes;
            processing(tierStack(MEKCeuMoreMachineBlocks.TierChemicalInfuser, tier), MachineType.CHEMICAL_INFUSER,
                    processes, 1, gases(gas, true), new CloudworksChemicalPairRecipeAdapter(
                            RecipeHandler.Recipe.CHEMICAL_INFUSER.get()), TIER);
            processing(tierStack(MEKCeuMoreMachineBlocks.TierChemicalWasher, tier), MachineType.CHEMICAL_WASHER,
                    processes, 1, new MachineSlotLayout()
                            .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, gas)
                            .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, gas)
                            .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, gas),
                    new CloudworksGasFluidRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_WASHER.get()), TIER);
            processing(tierStack(MEKCeuMoreMachineBlocks.TierElectrolyticSeparator, tier), MachineType.ELECTROLYTIC_SEPARATOR,
                    processes, 1, new MachineSlotLayout()
                            .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 24_000 * processes)
                            .add("gas_output_left", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 2_400 * processes)
                            .add("gas_output_right", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 2_400 * processes),
                    new CloudworksSeparatorRecipeAdapter(RecipeHandler.Recipe.ELECTROLYTIC_SEPARATOR.get()), TIER);
            processing(tierStack(MEKCeuMoreMachineBlocks.TierIsotopicCentrifuge, tier), MachineType.ISOTOPIC_CENTRIFUGE,
                    processes, 1, gases(gas, false),
                    new CloudworksGasGasRecipeAdapter(RecipeHandler.Recipe.ISOTOPIC_CENTRIFUGE.get()), TIER);
            processing(tierStack(MEKCeuMoreMachineBlocks.TierAmbientAccumulator, tier), MachineType.AMBIENT_ACCUMULATOR_ENERGY,
                    processes, 1, new MachineSlotLayout().add("gas_output", QIOResourceCodecs.GAS_STACK_ID,
                            MachineSlotLayout.PortRole.OUTPUT, mekanism.common.tier.GasTankTier.BASIC.getBaseStorage() * processes),
                    new CloudworksAmbientGasRecipeAdapter(RecipeHandler.Recipe.AMBIENT_ACCUMULATOR.get()), TIER);
            ItemStack solar = tierStack(MEKCeuMoreMachineBlocks.TierSolarNeutronActivator, tier);
            register(solar, new MachineExecutionProfile(processes, 0, 0, 1), gases(gas, false),
                    new CloudworksSolarNeutronRecipeAdapter(RecipeHandler.Recipe.SOLAR_NEUTRON_ACTIVATOR.get()), TIER);
            processing(tierStack(MEKCeuMoreMachineBlocks.TierRotaryCondensentrator, tier),
                    MachineType.ROTARY_CONDENSENTRATOR, processes, 1, new MachineSlotLayout()
                            .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, gas)
                            .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, gas)
                            .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, gas)
                            .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, gas),
                    new CloudworksRotaryRecipeAdapter(RecipeHandler.Recipe.ROTARY_CONDENSENTRATOR.get()), TIER);
            ItemStack mineral = tierStack(MEKCeuMoreMachineBlocks.VoidMineralGenerator, tier);
            register(mineral, new MachineExecutionProfile(1, MoreMachineConfig.current().config.VoidMineralGeneratorEnergyUsage.val(),
                            MoreMachineConfig.current().config.VoidMineralGeneratorEnergyStorge.val() * processes,
                            MoreMachineConfig.current().config.VoidMineralGeneratorTick.val()),
                    new MachineSlotLayout().add("item_output", QIOResourceCodecs.ITEM_STACK_ID,
                            MachineSlotLayout.PortRole.OUTPUT, 81 * 64),
                    new CloudworksVoidMineralRecipeAdapter(VoidMineralGeneratorUitls.getCanOre(), processes), TIER);
            register(tierStack(MEKCeuMoreMachineBlocks.TierRadioactiveWasteBarrel, tier),
                    new MachineExecutionProfile(1, 0, 0, mekanism.common.lib.radiation.RadiationUtil.getWasteBarrelProcessTicks()),
                    new MachineSlotLayout().add("gas_input", QIOResourceCodecs.GAS_STACK_ID,
                            MachineSlotLayout.PortRole.INPUT, 512_000 * processes),
                    new CloudworksWasteBarrelRecipeAdapter(processes * 10), TIER);
            if (Loader.isModLoaded("mekanismgenerators")) {
                ItemStack gasGenerator = tierStack(MEKCeuMoreMachineBlocks.TierGasGenerator, tier);
                CloudworksGasGeneratorAdapter fuel = new CloudworksGasGeneratorAdapter(RecipeHandler.Recipe.GAS_FUEL_TO_ENERGY_RECIPE.get());
                CloudworksRegistries.registerMachine(gasGenerator, new MachineExecutionProfile(processes, 0,
                                MekanismConfig.current().general.FROM_H2.val() * 1000 * processes, 1),
                        new MachineSlotLayout().add("gas_input", QIOResourceCodecs.GAS_STACK_ID,
                                MachineSlotLayout.PortRole.INPUT, 18_000 * processes), Collections.emptyList(), TIER);
                CloudworksRegistries.registerSpecial(gasGenerator, fuel);
                CloudworksRegistries.registerEnergyGenerator(gasGenerator, fuel);
                ambientGenerator(tierStack(MEKCeuMoreMachineBlocks.TierWindGenerator, tier), processes,
                        MekanismConfig.current().generators.windGeneratorStorage.val() * processes,
                        new CloudworksWindGeneratorAdapter(
                                MekanismConfig.current().generators.windGenerationMin.val(),
                                MekanismConfig.current().generators.windGenerationMax.val(),
                                MekanismConfig.current().generators.windGenerationMinY.val(),
                                MekanismConfig.current().generators.windGenerationMaxY.val(), 0));
                ambientGenerator(tierStack(MEKCeuMoreMachineBlocks.TierSolarGenerator, tier), processes,
                        MekanismConfig.current().generators.solarGeneratorStorage.val() * processes,
                        new CloudworksSolarGeneratorAdapter(false));
                ambientGenerator(tierStack(MEKCeuMoreMachineBlocks.TierAdvancedSolarGenerator, tier), processes,
                        MekanismConfig.current().generators.advancedSolarGeneratorStorage.val() * processes,
                        new CloudworksSolarGeneratorAdapter(true));
            }
        }
        for (mekceumoremachine.common.block.states.BlockStateTierChemicalCrystallizer.MachineType type :
                mekceumoremachine.common.block.states.BlockStateTierChemicalCrystallizer.MachineType.getValidMachines())
            processing(type.getStack(), MachineType.CHEMICAL_CRYSTALLIZER, type.tier.processes, 200,
                    new MachineSlotLayout()
                            .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 10_000 * type.tier.processes)
                            .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64 * type.tier.processes),
                    new CloudworksGasItemRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_CRYSTALLIZER.get()), MachineIdentityPolicy.none());
        for (mekceumoremachine.common.block.states.BlockStateTierChemicalOxidizer.MachineType type :
                mekceumoremachine.common.block.states.BlockStateTierChemicalOxidizer.MachineType.getValidMachines())
            processing(type.getStack(), MachineType.CHEMICAL_OXIDIZER, type.tier.processes, 100,
                    itemToGas(type.tier.processes, false),
                    new CloudworksItemGasOutputRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_OXIDIZER.get()), MachineIdentityPolicy.none());
        for (mekceumoremachine.common.block.states.BlockStateTierNutritionalLiquifier.MachineType type :
                mekceumoremachine.common.block.states.BlockStateTierNutritionalLiquifier.MachineType.getValidMachines())
            processing(type.getStack(), MachineType.NUTRITIONAL_LIQUIFIER, type.tier.processes, 100,
                    itemToGas(type.tier.processes, false),
                    new CloudworksNutritionalRecipeAdapter(RecipeHandler.Recipe.NUTRITIONAL_LIQUIFIER.get()), MachineIdentityPolicy.none());
        for (mekceumoremachine.common.block.states.BlockStateTierChemicalDissolutionChamber.MachineType type :
                mekceumoremachine.common.block.states.BlockStateTierChemicalDissolutionChamber.MachineType.getValidMachines())
            processing(type.getStack(), MachineType.CHEMICAL_DISSOLUTION_CHAMBER, type.tier.processes, 100,
                    itemToGas(type.tier.processes, true),
                    new CloudworksDissolutionRecipeAdapter(RecipeHandler.Recipe.CHEMICAL_DISSOLUTION_CHAMBER.get()), MachineIdentityPolicy.none());
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

    static void registerOrganicFarms() {
        for (mekceumoremachine.common.block.states.BlockStateTierOrganicFarm.MachineType type :
                mekceumoremachine.common.block.states.BlockStateTierOrganicFarm.MachineType.getValidMachines())
            processing(type.getStack(), MachineType.ORGANIC_FARM, type.tier.processes, 200,
                    new MachineSlotLayout()
                            .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64 * type.tier.processes)
                            .add("gas_input", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1_000 * type.tier.processes)
                            .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 1_000 * type.tier.processes)
                            .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64 * 64),
                    new CloudworksFarmRecipeAdapter(RecipeHandler.Recipe.ORGANIC_FARM.get(), 200), MachineIdentityPolicy.none());
    }

    static ItemStack tierStack(Block block, MachineTier tier) {
        ItemStack stack = new ItemStack(block);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("tier", tier.ordinal());
        stack.setTagCompound(tag);
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
    }

    static void register(ItemStack stack, MachineExecutionProfile profile, MachineSlotLayout layout,
            CloudworksMachineAdapter adapter, MachineIdentityPolicy identity) {
        CloudworksRegistries.registerMachine(stack, profile, layout, Collections.emptyList(), identity);
        CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(stack), adapter);
    }

    private static void ambientGenerator(ItemStack stack, int processes, double storage, CloudworksEnergyGenerator generator) {
        CloudworksRegistries.registerMachine(stack, new MachineExecutionProfile(processes, 0, storage, 1),
                new MachineSlotLayout(), Collections.emptyList(), TIER);
        CloudworksRegistries.registerEnergyGenerator(stack, generator);
    }
}
