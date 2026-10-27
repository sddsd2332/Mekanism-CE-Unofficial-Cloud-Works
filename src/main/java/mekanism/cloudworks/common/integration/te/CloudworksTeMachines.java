package mekanism.cloudworks.common.integration.te;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import cofh.thermalexpansion.block.machine.TileMachineBase;
import cofh.thermalexpansion.init.TEBlocks;
import cofh.thermalexpansion.util.managers.machine.BrewerManager;
import cofh.thermalexpansion.util.managers.machine.CentrifugeManager;
import cofh.thermalexpansion.util.managers.machine.ChargerManager;
import cofh.thermalexpansion.util.managers.machine.CompactorManager;
import cofh.thermalexpansion.util.managers.machine.CrucibleManager;
import cofh.thermalexpansion.util.managers.machine.EnchanterManager;
import cofh.thermalexpansion.util.managers.machine.ExtruderManager;
import cofh.thermalexpansion.util.managers.machine.FurnaceManager;
import cofh.thermalexpansion.util.managers.machine.InsolatorManager;
import cofh.thermalexpansion.util.managers.machine.PrecipitatorManager;
import cofh.thermalexpansion.util.managers.machine.PulverizerManager;
import cofh.thermalexpansion.util.managers.machine.RefineryManager;
import cofh.thermalexpansion.util.managers.machine.SawmillManager;
import cofh.thermalexpansion.util.managers.machine.SmelterManager;
import cofh.thermalexpansion.util.managers.machine.TransposerManager;
import cofh.thermalfoundation.init.TFFluids;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.CloudworksMachineDefinition;
import mekanism.cloudworks.api.machine.CloudworksMachineRegistration;
import mekanism.cloudworks.api.recipe.CloudworksRecipeMode;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineIdentityPolicy;
import mekanism.cloudworks.api.machine.MachineLineUpgradePolicy;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.api.module.MachineUpgradePolicy;
import mekanism.cloudworks.common.recipe.CloudworksRecipeBatch;
import mekanism.common.config.MekanismConfig;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import cofh.thermalexpansion.init.TEItems;
import java.util.function.IntFunction;
import mekanism.cloudworks.common.integration.CloudworksUpgradeCurves;
import net.minecraftforge.fml.common.Loader;

/**
 * Thermal Expansion machine adapters. TE types appear only inside this package
 * and only behind {@link mekanism.cloudworks.common.integration.CloudworksHooks}
 * optional-mod boundary; deleting this directory removes the integration.
 *
 * <p>All fifteen processing machines register under the one-per-type
 * {@code thermalexpansion:machine} item with the {@code Level} NBT byte as the
 * tier identity (0-4). Recipes freeze from the live TE managers through their
 * exact representative stacks. Augment-gated recipe sets and the speed coil
 * ride the mechanism/numeric upgrade channels declared in {@link TeAugments};
 * the per-machine {@code BasePower} config override stays deferred.</p>
 */
public final class CloudworksTeMachines {

    /** All TE machines share the one-item-per-type {@code machine} item; tiers live in NBT. */
    static final MachineIdentityPolicy LEVEL = MachineIdentityPolicy.keysWithDefaultByte("Level", (byte) 0);

    // BlockMachine.Type metadata: one value per machine, 11 is the excluded crafter.
    static final int FURNACE_META = 0;
    static final int PULVERIZER_META = 1;
    static final int SAWMILL_META = 2;
    static final int SMELTER_META = 3;
    static final int INSOLATOR_META = 4;
    static final int COMPACTOR_META = 5;
    static final int CRUCIBLE_META = 6;
    static final int REFINERY_META = 7;
    static final int TRANSPOSER_META = 8;
    static final int CHARGER_META = 9;
    static final int CENTRIFUGE_META = 10;
    static final int BREWER_META = 12;
    static final int ENCHANTER_META = 13;
    static final int PRECIPITATOR_META = 14;
    static final int EXTRUDER_META = 15;

    /** All fifteen machine types, meta 11 (cyclic fabricator) excluded by ruling. */
    static final int[] MACHINE_METAS = {FURNACE_META, PULVERIZER_META, SAWMILL_META, SMELTER_META,
            INSOLATOR_META, COMPACTOR_META, CRUCIBLE_META, REFINERY_META, TRANSPOSER_META, CHARGER_META,
            CENTRIFUGE_META, BREWER_META, ENCHANTER_META, PRECIPITATOR_META, EXTRUDER_META};

    /** Chance-output variant applied to a secondary-bearing recipe family. */
    static final class SecondaryVariant {
        static final SecondaryVariant NORMAL = new SecondaryVariant(1D, false);
        final double boost;
        final boolean suppress;

        SecondaryVariant(double boost, boolean suppress) {
            this.boost = boost;
            this.suppress = suppress;
        }

        double chance(int base) {
            return Math.min(1D, base / 100D * boost);
        }
    }

    /** TileMachineBase defaults; the TE per-machine config override is a later refinement. */
    static final double STANDARD_POWER_RF = 20D;
    static final double HEAVY_POWER_RF = 40D;
    /** TE machines carry a 480k RF internal buffer at every tier. */
    static final double MACHINE_ENERGY_BUFFER_RF = 480_000D;
    static final int FLUID_PORT_CAPACITY = 10_000;

    private static boolean registered;

    private CloudworksTeMachines() {}

    public static synchronized void register() {
        if (registered) return;
        CloudworksUpgradeCurves.Snapshot curves =
                CloudworksUpgradeCurves.capture(
                        Loader.isModLoaded(
                                CloudworksUpgradeCurves.RANDOM_COMPLEMENT_MOD_ID));
        register(Item.getItemFromBlock(TEBlocks.blockMachine), TileMachineBase.POWER_SCALING,
                TileMachineBase.NUM_AUGMENTS, MekanismConfig.current().general.FROM_RF.val(),
                meta -> new ItemStack(TEItems.itemAugment, 1, meta), curves);
        registered = true;
    }

    /** Pure registration entry so JVM tests drive it with substitute items and snapshots. */
    static synchronized void register(Item machineItem, int[] powerScaling, byte[] augmentSlots,
            double rfToJoules, IntFunction<ItemStack> augmentStack,
            CloudworksUpgradeCurves.Snapshot curves) {
        Objects.requireNonNull(machineItem, "machine item");
        Objects.requireNonNull(powerScaling, "power scaling");
        Objects.requireNonNull(augmentSlots, "augment slots");
        Objects.requireNonNull(augmentStack, "augment stacks");
        if (powerScaling.length == 0 || augmentSlots.length == 0 || !Double.isFinite(rfToJoules)
                || rfToJoules <= 0D)
            throw new IllegalArgumentException("Invalid Thermal Expansion registration inputs");
        for (int level = 0; level < powerScaling.length; level++) {
            double speed = powerScaling[level] / 100D;
            for (int meta : MACHINE_METAS)
                registerLevel(machineItem, meta, level, speed, rfToJoules);
        }
        TeAugments.register(machineItem, augmentStack, augmentSlots, powerScaling,
                curves.teSocketLimit(), curves.teCoilEnergyPerAugment(), rfToJoules);
    }

    private static void registerLevel(Item machineItem, int meta, int level, double speed,
            double rfToJoules) {
        double basePowerRf = basePowerRf(meta);
        MachineExecutionProfile profile = new MachineExecutionProfile(1,
                basePowerRf * speed * rfToJoules, MACHINE_ENERGY_BUFFER_RF * rfToJoules, 1,
                MachineUpgradePolicy.NONE,
                // Axis-priority: TE executors have no native slots but still
                // participate in the line upgrade axes (numbers doc §3).
                MachineLineUpgradePolicy.PROCESSING);
        List<CloudworksRecipeMode> modes = new ArrayList<>();
        for (CloudworksMachineAdapter adapter : adapters(meta, speed, rfToJoules))
            modes.add(new CloudworksRecipeMode(modeId(meta, adapter), 0, layout(meta), adapter));
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(
                machineStack(machineItem, meta, level), profile, layout(meta), LEVEL,
                Collections.unmodifiableList(modes)));
    }

    private static double basePowerRf(int meta) {
        return meta == CRUCIBLE_META || meta == CHARGER_META ? HEAVY_POWER_RF : STANDARD_POWER_RF;
    }

    // -----------------------------------------------------------------------------------------
    // Slot layouts
    // -----------------------------------------------------------------------------------------

    static MachineSlotLayout layout(int meta) {
        MachineSlotLayout layout = new MachineSlotLayout();
        switch (meta) {
            case FURNACE_META -> layout
                    .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64)
                    .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 4000);
            case CHARGER_META -> layout
                    .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 128)
                    .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 4000);
            case PULVERIZER_META -> layout
                    .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("primary_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64)
                    .add("secondary_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64)
                    .add("petrotheum_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 4000);
            case SAWMILL_META -> layout
                    .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("primary_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64)
                    .add("secondary_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64)
                    .add("resin_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 4000);
            case SMELTER_META -> layout
                    .add("item_input_primary", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("item_input_secondary", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("primary_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64)
                    .add("secondary_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64)
                    .add("pyrotheum_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 4000);
            case INSOLATOR_META -> layout
                    .add("item_input_primary", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("item_input_secondary", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, FLUID_PORT_CAPACITY)
                    .add("primary_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64)
                    .add("secondary_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
            case COMPACTOR_META -> layout
                    .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
            case CRUCIBLE_META -> layout
                    .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, FLUID_PORT_CAPACITY);
            case REFINERY_META -> layout
                    .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 4000)
                    .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, FLUID_PORT_CAPACITY)
                    .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
            case TRANSPOSER_META -> layout
                    .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, FLUID_PORT_CAPACITY)
                    .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64)
                    .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, FLUID_PORT_CAPACITY);
            case CENTRIFUGE_META -> layout
                    .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 256)
                    .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 4000);
            case BREWER_META -> layout
                    .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 4000)
                    .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, FLUID_PORT_CAPACITY);
            case ENCHANTER_META -> layout
                    .add("item_input_primary", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("item_input_secondary", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, FLUID_PORT_CAPACITY)
                    .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
            case PRECIPITATOR_META -> layout
                    .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 8000)
                    .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
            case EXTRUDER_META -> layout
                    .add("fluid_input_hot", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 4000)
                    .add("fluid_input_cold", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 4000)
                    .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
            default -> throw new IllegalArgumentException("Unknown Thermal Expansion machine meta " + meta);
        }
        return layout;
    }

    // -----------------------------------------------------------------------------------------
    // Recipe adapters
    // -----------------------------------------------------------------------------------------

    static List<CloudworksMachineAdapter> adapters(int meta, double speed, double rfToJoules) {
        double power = basePowerRf(meta);
        List<CloudworksMachineAdapter> adapters = new ArrayList<>();
        switch (meta) {
            case FURNACE_META -> adapters.add(new TeListAdapter<>("te_furnace",
                    () -> FurnaceManager.getRecipeList(false),
                    recipe -> itemToItem(recipe.getInput(), recipe.getOutput()),
                    FurnaceManager.FurnaceRecipe::getEnergy, power, speed));
            case REFINERY_META -> adapters.add(new TeListAdapter<>("te_refinery", RefineryManager::getRecipeList,
                    CloudworksTeMachines::describeRefinery, RefineryManager.RefineryRecipe::getEnergy,
                    power, speed));
            case EXTRUDER_META -> adapters.add(new TeListAdapter<>("te_extruder",
                    () -> ExtruderManager.getRecipeList(false), CloudworksTeMachines::describeExtruder,
                    ExtruderManager.ExtruderRecipe::getEnergy, power, speed));
            case PULVERIZER_META -> adapters.add(new TeListAdapter<>("te_pulverizer", PulverizerManager::getRecipeList,
                    CloudworksTeMachines::itemPlusChance, PulverizerManager.PulverizerRecipe::getEnergy, power, speed));
            case SAWMILL_META -> adapters.add(new TeListAdapter<>("te_sawmill", SawmillManager::getRecipeList,
                    CloudworksTeMachines::itemPlusChance, SawmillManager.SawmillRecipe::getEnergy, power, speed));
            case SMELTER_META -> adapters.add(new TeListAdapter<>("te_smelter", SmelterManager::getRecipeList,
                    CloudworksTeMachines::describeSmelter, SmelterManager.SmelterRecipe::getEnergy, power, speed));
            case INSOLATOR_META -> adapters.add(new TeListAdapter<>("te_insolator",
                    CloudworksTeMachines::nonTreeInsolatorList,
                    CloudworksTeMachines::describeInsolator, InsolatorManager.InsolatorRecipe::getEnergy, power, speed));
            case COMPACTOR_META -> adapters.add(new TeListAdapter<>("te_compactor_plate",
                    () -> CompactorManager.getRecipeList(CompactorManager.Mode.PLATE),
                    recipe -> itemToItem(recipe.getInput(), recipe.getOutput()),
                    CompactorManager.CompactorRecipe::getEnergy, power, speed));
            case CRUCIBLE_META -> adapters.add(new TeListAdapter<>("te_crucible", CrucibleManager::getRecipeList,
                    CloudworksTeMachines::describeCrucible, CrucibleManager.CrucibleRecipe::getEnergy, power, speed));
            case TRANSPOSER_META -> {
                adapters.add(new TeListAdapter<>("te_transposer_fill", TransposerManager::getFillRecipeList,
                        CloudworksTeMachines::describeTransposerFill, TransposerManager.TransposerRecipe::getEnergy, power, speed));
                adapters.add(new TeListAdapter<>("te_transposer_extract", TransposerManager::getExtractRecipeList,
                        CloudworksTeMachines::describeTransposerExtract, TransposerManager.TransposerRecipe::getEnergy, power, speed));
            }
            case CHARGER_META -> {
                adapters.add(new TeListAdapter<>("te_charger", ChargerManager::getRecipeList,
                        recipe -> itemToItem(recipe.getInput(), recipe.getOutput()),
                        ChargerManager.ChargerRecipe::getEnergy, power, speed));
                // Universal charging: every chargeable item freezes as one
                // empty-to-full recipe beside the ChargerManager table.
                adapters.add(new TeChargerAdapter("te_charger_energy", TeChargerAdapter::registeredItems,
                        TeChargerAdapter::probeEnergy, power, speed, rfToJoules));
            }
            case CENTRIFUGE_META -> adapters.add(new TeListAdapter<>("te_centrifuge", CentrifugeManager::getRecipeList,
                    CloudworksTeMachines::describeCentrifuge, CentrifugeManager.CentrifugeRecipe::getEnergy,
                    power, speed));
            case BREWER_META -> adapters.add(new TeListAdapter<>("te_brewer", BrewerManager::getRecipeList,
                    CloudworksTeMachines::describeBrewer, BrewerManager.BrewerRecipe::getEnergy, power, speed));
            case ENCHANTER_META -> adapters.add(new TeListAdapter<>("te_enchanter", EnchanterManager::getRecipeList,
                    CloudworksTeMachines::describeEnchanter, EnchanterManager.EnchanterRecipe::getEnergy, power, speed));
            case PRECIPITATOR_META -> adapters.add(new TeListAdapter<>("te_precipitator", PrecipitatorManager::getRecipeList,
                    CloudworksTeMachines::describePrecipitator, PrecipitatorManager.PrecipitatorRecipe::getEnergy, power, speed));
            default -> throw new IllegalArgumentException("Unknown Thermal Expansion machine meta " + meta);
        }
        return adapters;
    }

    private static String modeId(int meta, CloudworksMachineAdapter adapter) {
        // The adapters carry their sub-mode in the id prefix.
        if (adapter instanceof TeListAdapter<?> list) return "cloudworks:" + list.idPrefix();
        if (adapter instanceof TeChargerAdapter charger) return "cloudworks:" + charger.idPrefix();
        throw new IllegalArgumentException("Unknown Thermal Expansion adapter kind");
    }

    private static CloudworksRecipeBatch itemToItem(ItemStack input, ItemStack output) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        if (!input.isEmpty() && !output.isEmpty()) {
            batch.item(input);
            batch.output(output);
            return batch;
        }
        return batch;
    }

    private static CloudworksRecipeBatch itemPlusChance(
            PulverizerManager.PulverizerRecipe recipe) {
        return itemPlusChance(recipe.getInput(), recipe.getPrimaryOutput(),
                recipe.getSecondaryOutput(), recipe.getSecondaryOutputChance(), SecondaryVariant.NORMAL);
    }

    private static CloudworksRecipeBatch itemPlusChance(
            SawmillManager.SawmillRecipe recipe) {
        return itemPlusChance(recipe.getInput(), recipe.getPrimaryOutput(),
                recipe.getSecondaryOutput(), recipe.getSecondaryOutputChance(), SecondaryVariant.NORMAL);
    }

    private static CloudworksRecipeBatch itemPlusChance(ItemStack input, ItemStack primary,
            ItemStack secondary, int chance) {
        return itemPlusChance(input, primary, secondary, chance, SecondaryVariant.NORMAL);
    }

    private static CloudworksRecipeBatch itemPlusChance(ItemStack input, ItemStack primary,
            ItemStack secondary, int chance, SecondaryVariant variant) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        if (input.isEmpty() || primary.isEmpty()) return batch;
        batch.item(input);
        batch.output(primary);
        if (!secondary.isEmpty() && chance > 0 && !variant.suppress)
            batch.chanceOutput(secondary, variant.chance(chance));
        return batch;
    }

    private static CloudworksRecipeBatch describeSmelter(SmelterManager.SmelterRecipe recipe) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        ItemStack primaryIn = recipe.getPrimaryInput();
        ItemStack primaryOut = recipe.getPrimaryOutput();
        if (primaryIn.isEmpty() || primaryOut.isEmpty()) return batch;
        batch.item(primaryIn);
        ItemStack secondaryIn = recipe.getSecondaryInput();
        if (!secondaryIn.isEmpty()) batch.item(secondaryIn);
        batch.output(primaryOut);
        ItemStack secondaryOut = recipe.getSecondaryOutput();
        int chance = recipe.getSecondaryOutputChance();
        if (!secondaryOut.isEmpty() && chance > 0) batch.chanceOutput(secondaryOut, chance / 100D);
        return batch;
    }

    private static CloudworksRecipeBatch describeInsolator(InsolatorManager.InsolatorRecipe recipe) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        ItemStack primaryIn = recipe.getPrimaryInput();
        ItemStack primaryOut = recipe.getPrimaryOutput();
        if (primaryIn.isEmpty() || primaryOut.isEmpty()) return batch;
        batch.item(primaryIn);
        ItemStack secondaryIn = recipe.getSecondaryInput();
        if (!secondaryIn.isEmpty()) batch.item(secondaryIn);
        int water = recipe.getWater();
        if (water > 0) batch.fluid(new FluidStack(FluidRegistry.WATER, water));
        batch.output(primaryOut);
        ItemStack secondaryOut = recipe.getSecondaryOutput();
        int chance = recipe.getSecondaryOutputChance();
        if (!secondaryOut.isEmpty() && chance > 0) batch.chanceOutput(secondaryOut, chance / 100D);
        return batch;
    }

    /**
     * The base insolator list gates TREE recipes behind the @323 augment
     * (TileInsolator canStart:141 refuses them without it), so the machine
     * registration only freezes the normal subset.
     */
    static InsolatorManager.InsolatorRecipe[] nonTreeInsolatorList() {
        InsolatorManager.InsolatorRecipe[] recipes = InsolatorManager.getRecipeList();
        if (recipes == null) return null;
        List<InsolatorManager.InsolatorRecipe> result = new ArrayList<>();
        for (InsolatorManager.InsolatorRecipe recipe : recipes)
            if (recipe != null && recipe.getType() != InsolatorManager.Type.TREE) result.add(recipe);
        return result.toArray(new InsolatorManager.InsolatorRecipe[0]);
    }

    /**
     * The @323 tree-infusion unlock: with the augment installed the TREE gate
     * is gone, so the contribution freezes the complete manager list — the
     * normal recipes plus the tree set (REPLACE swaps the gated base).
     */
    static List<CloudworksRecipeSpec> freezeInsolatorTree(CloudworksMachineRegistration machine,
            double rfToJoules) {
        return TeAugments.freezeSet("te_insolator_tree", InsolatorManager::getRecipeList,
                CloudworksTeMachines::describeInsolator, InsolatorManager.InsolatorRecipe::getEnergy,
                machine, rfToJoules);
    }

    /** Melting is conservation: the fluid amount never rides the cloud output multiplier. */
    private static CloudworksRecipeBatch describeCrucible(CrucibleManager.CrucibleRecipe recipe) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        ItemStack input = recipe.getInput();
        FluidStack output = recipe.getOutput();
        if (input.isEmpty() || output == null || output.amount <= 0) return batch;
        batch.item(input);
        batch.unscaledOutputs();
        batch.output(output);
        return batch;
    }

    static CloudworksRecipeBatch describeRefinery(RefineryManager.RefineryRecipe recipe) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        FluidStack input = recipe.getInput();
        FluidStack output = recipe.getOutputFluid();
        if (input == null || input.amount <= 0 || output == null || output.amount <= 0) return batch;
        batch.fluid(input);
        batch.output(output);
        ItemStack outputItem = recipe.getOutputItem();
        int chance = recipe.getChance();
        if (!outputItem.isEmpty() && chance > 0) batch.chanceOutput(outputItem, chance / 100D);
        return batch;
    }

    private static CloudworksRecipeBatch describeTransposerFill(TransposerManager.TransposerRecipe recipe) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        ItemStack input = recipe.getInput();
        FluidStack fluid = recipe.getFluid();
        ItemStack output = recipe.getOutput();
        if (input.isEmpty() || output.isEmpty() || fluid == null || fluid.amount <= 0) return batch;
        batch.item(input);
        batch.fluid(fluid);
        batch.output(output);
        return batch;
    }

    private static CloudworksRecipeBatch describeTransposerExtract(TransposerManager.TransposerRecipe recipe) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        ItemStack input = recipe.getInput();
        ItemStack output = recipe.getOutput();
        FluidStack fluid = recipe.getFluid();
        if (input.isEmpty() || output.isEmpty()) return batch;
        batch.item(input);
        batch.output(output);
        if (fluid != null && fluid.amount > 0) {
            int chance = recipe.getChance();
            if (chance > 0 && chance < 100)
                batch.chanceOutput(QIOResourceCodecs.fluid(fluid), fluid.amount, chance / 100D, "");
            else batch.output(fluid);
        }
        return batch;
    }

    static CloudworksRecipeBatch describeCentrifuge(CentrifugeManager.CentrifugeRecipe recipe) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        ItemStack input = recipe.getInput();
        if (input.isEmpty()) return batch;
        batch.item(input);
        List<ItemStack> outputs = recipe.getOutput();
        if (outputs != null) {
            List<Integer> chances = recipe.getChance();
            for (int index = 0; index < outputs.size(); index++) {
                ItemStack output = outputs.get(index);
                if (output.isEmpty()) continue;
                int chance = chances == null || index >= chances.size() || chances.get(index) == null
                        ? 100 : chances.get(index);
                if (chance <= 0) continue;
                if (chance >= 100) batch.output(output);
                else batch.chanceOutput(output, chance / 100D);
            }
        }
        // TE's centrifuge fluid is a byproduct: canStart checks tank room and
        // processFinish fills it, so it freezes as an output on the fluid port.
        FluidStack fluid = recipe.getFluid();
        if (fluid != null && fluid.amount > 0) batch.output(fluid, "fluid_output");
        return batch;
    }

    private static CloudworksRecipeBatch describeBrewer(BrewerManager.BrewerRecipe recipe) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        ItemStack input = recipe.getInput();
        FluidStack inputFluid = recipe.getInputFluid();
        FluidStack output = recipe.getOutputFluid();
        if (input.isEmpty() || inputFluid == null || inputFluid.amount <= 0
                || output == null || output.amount <= 0) return batch;
        batch.item(input);
        batch.fluid(inputFluid);
        batch.output(output);
        return batch;
    }

    private static CloudworksRecipeBatch describeEnchanter(EnchanterManager.EnchanterRecipe recipe) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        ItemStack primaryIn = recipe.getPrimaryInput();
        ItemStack secondaryIn = recipe.getSecondaryInput();
        ItemStack output = recipe.getOutput();
        if (primaryIn.isEmpty() || secondaryIn.isEmpty() || output.isEmpty()) return batch;
        batch.item(primaryIn);
        batch.item(secondaryIn);
        int experience = recipe.getExperience();
        if (experience > 0)
            batch.fluid(new FluidStack(TFFluids.fluidExperience, experience));
        batch.output(output);
        return batch;
    }

    private static CloudworksRecipeBatch describePrecipitator(PrecipitatorManager.PrecipitatorRecipe recipe) {
        return itemToItemLikeFluidIn(recipe.getInput(), recipe.getOutput());
    }

    private static CloudworksRecipeBatch itemToItemLikeFluidIn(FluidStack input, ItemStack output) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        if (input != null && input.amount > 0 && !output.isEmpty()) {
            batch.fluid(input);
            batch.output(output);
        }
        return batch;
    }

    static CloudworksRecipeBatch describeExtruder(ExtruderManager.ExtruderRecipe recipe) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        FluidStack hot = recipe.getInputHot();
        FluidStack cold = recipe.getInputCold();
        ItemStack output = recipe.getOutput();
        if (hot == null || hot.amount <= 0 || cold == null || cold.amount <= 0 || output.isEmpty()) return batch;
        batch.fluid(hot);
        batch.fluid(cold);
        batch.output(output);
        return batch;
    }

    // -----------------------------------------------------------------------------------------
    // Identity and shared math
    // -----------------------------------------------------------------------------------------

    /** Identity carrier: exactly the NBT the machine family is keyed on, nothing else. */
    static ItemStack machineStack(Item machineItem, int meta, int level) {
        ItemStack machine = new ItemStack(machineItem, 1, meta);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByte("Level", (byte) level);
        machine.setTagCompound(tag);
        return machine;
    }

    /** TE recipe energy is per-operation RF; duration follows the tier's throughput. */
    static int durationTicks(int energyRf, double basePowerRf, double speed) {
        if (energyRf <= 0) return 1;
        return (int) Math.max(1L, Math.ceil(energyRf / (basePowerRf * speed)));
    }

    /**
     * Freezes one TE manager list. A batch with no inputs (an unfreezable or
     * half-empty representative) yields no spec; one bad recipe never blocks
     * the manager.
     */
    static final class TeListAdapter<R> implements CloudworksMachineAdapter {
        private final String idPrefix;
        private final Supplier<R[]> list;
        private final Function<R, CloudworksRecipeBatch> describe;
        private final ToIntFunction<R> energy;
        private final double basePowerRf;
        private final double speed;

        TeListAdapter(String idPrefix, Supplier<R[]> list, Function<R, CloudworksRecipeBatch> describe,
                ToIntFunction<R> energy, double basePowerRf, double speed) {
            this.idPrefix = idPrefix;
            this.list = list;
            this.describe = describe;
            this.energy = energy;
            this.basePowerRf = basePowerRf;
            this.speed = speed;
        }

        String idPrefix() {
            return idPrefix;
        }

        @Override
        public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            R[] recipes = list.get();
            if (recipes == null) return Collections.emptyList();
            for (R recipe : recipes) {
                if (recipe == null) continue;
                try {
                    result.add(describe.apply(recipe).specification(idPrefix + "_" + result.size(),
                            durationTicks(energy.applyAsInt(recipe), basePowerRf, speed)));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable ingredient rejects a single recipe, never the manager.
                }
            }
            return Collections.unmodifiableList(result);
        }
    }
}
