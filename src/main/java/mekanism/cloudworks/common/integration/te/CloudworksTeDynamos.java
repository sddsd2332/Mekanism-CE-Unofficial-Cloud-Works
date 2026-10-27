package mekanism.cloudworks.common.integration.te;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Objects;

import cofh.thermalexpansion.block.dynamo.TileDynamoBase;
import cofh.thermalexpansion.init.TEBlocks;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.CloudworksMachineDefinition;
import mekanism.cloudworks.api.recipe.CloudworksRecipeMode;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.api.module.MachineUpgradePolicy;
import mekanism.cloudworks.common.integration.CloudworksUpgradeCurves;
import mekanism.common.config.MekanismConfig;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import cofh.thermalexpansion.init.TEItems;
import java.util.function.IntFunction;
import mekanism.cloudworks.api.module.MachineModuleType;
import net.minecraftforge.fml.common.Loader;

/**
 * Thermal Expansion dynamo registration: six dynamos times five levels under
 * the one-per-type {@code thermalexpansion:dynamo} item with the {@code Level}
 * NBT byte identity, mirroring the machine family. Generators ride the
 * fuel-contract path (see {@link TeDynamoFuels}); the internal buffer follows
 * the native {@code basePower x 1000} storage rule per level. The efficiency
 * pieces (@513, +15% fuel energy per piece) rescale every fuel contract
 * through {@link TeAugments.DynamoEfficiency}; @512 was ruled out (cloud
 * dynamos are strict fuel conservation, no output-power axis). The dynamo
 * MODE sets are registered through the shared module path and frozen with the
 * rest of the family.
 */
public final class CloudworksTeDynamos {

    /**
     * All six dynamos share the 40 RF/t base power default.
     */
    static final int BASE_POWER_RF = 40;
    /**
     * Native storage rule: maxEnergy = basePower x 1000.
     */
    static final int ENERGY_BUFFER_MULTIPLIER = 1000;
    /** Native dynamo tanks are fixed at TEProps.FLUID_SMALL, without tier scaling. */
    static final int DYNAMO_FLUID_PORT_CAPACITY = 4000;

    static final String[] MODE_IDS = {"cloudworks:te_dynamo_steam", "cloudworks:te_dynamo_magmatic",
            "cloudworks:te_dynamo_compression", "cloudworks:te_dynamo_reactant",
            "cloudworks:te_dynamo_enervation", "cloudworks:te_dynamo_numismatic"};

    private static boolean registered;

    private CloudworksTeDynamos() {
    }

    public static synchronized void register() {
        if (registered) return;
        CloudworksUpgradeCurves.Snapshot curves =
                CloudworksUpgradeCurves.capture(
                        Loader.isModLoaded(
                                CloudworksUpgradeCurves.RANDOM_COMPLEMENT_MOD_ID));
        register(Item.getItemFromBlock(TEBlocks.blockDynamo), dynamoPowerScaling(),
                MekanismConfig.current().general.FROM_RF.val(), curves,
                meta -> new ItemStack(TEItems.itemAugment, 1, meta));
        registered = true;
    }

    /**
     * Pure registration entry so JVM tests drive it with substitute items.
     */
    static synchronized void register(Item dynamoItem, int[] powerScaling, double rfToJoules,
                                      CloudworksUpgradeCurves.Snapshot curves) {
        register(dynamoItem, powerScaling, rfToJoules, curves, null);
    }

    /**
     * Full registration entry. The augment stack supplier is optional: without
     * it (legacy tests) the efficiency pieces stay unaccepted.
     */
    static synchronized void register(Item dynamoItem, int[] powerScaling, double rfToJoules,
                                      CloudworksUpgradeCurves.Snapshot curves,
                                      IntFunction<ItemStack> augmentStack) {
        Objects.requireNonNull(dynamoItem, "dynamo item");
        Objects.requireNonNull(powerScaling, "power scaling");
        if (powerScaling.length == 0 || !Double.isFinite(rfToJoules) || rfToJoules <= 0D)
            throw new IllegalArgumentException("Invalid Thermal Expansion dynamo registration inputs");
        for (int meta = 0; meta < 6; meta++)
            for (int level = 0; level < powerScaling.length; level++)
                registerLevel(dynamoItem, meta, level, powerScaling[level], rfToJoules);
        acceptEfficiency(dynamoItem, powerScaling, curves, augmentStack);
        TeAugments.acceptDynamoModes(dynamoItem, powerScaling, dynamoAugmentSlots(),
                curves.teSocketLimit(), augmentStack);
    }

    /**
     * The efficiency piece type is declared with the machine family's augment
     * hub (see {@link TeAugments#register}); the module registry only fills at
     * freeze (after every integration registered), so the acceptance reads
     * the hub's direct reference instead of a registry lookup.
     */
    private static void acceptEfficiency(Item dynamoItem, int[] powerScaling,
                                         CloudworksUpgradeCurves.Snapshot curves, IntFunction<ItemStack> augmentStack) {
        if (augmentStack == null) return;
        MachineModuleType<?> efficiency =
                TeAugments.dynamoType(TeAugments.DYNAMO_EFFICIENCY_ID);
        if (efficiency == null) return;
        byte[] slots = dynamoAugmentSlots();
        int sockets = Math.max(1, curves.teSocketLimit());
        for (int meta = 0; meta < 6; meta++)
            for (int level = 0; level < powerScaling.length; level++)
                CloudworksAPI.acceptMachineUpgrade(dynamoStack(dynamoItem, meta, level), efficiency,
                        Math.max(0, (slots[level] & 0xFF) * sockets));
    }

    /**
     * Live dynamo augment slot table. Like the power scaling it is config
     * data behind a non-public field, read reflectively once at freeze-time
     * registration; a moved field falls back to the native default.
     */
    private static byte[] dynamoAugmentSlots() {
        try {
            Field field = TileDynamoBase.class.getDeclaredField("numAugments");
            field.setAccessible(true);
            byte[] live = (byte[]) field.get(null);
            if (live != null && live.length > 0) return live.clone();
        } catch (ReflectiveOperationException | ClassCastException | LinkageError inaccessible) {
            // A moved table must not break registration; the native default is safe.
        }
        return new byte[]{0, 1, 2, 3, 4};
    }

    private static void registerLevel(Item dynamoItem, int meta, int level, int scalePercent,
                                      double rfToJoules) {
        double speed = scalePercent / 100D;
        MachineExecutionProfile profile = new MachineExecutionProfile(1, 0D,
                BASE_POWER_RF * speed * ENERGY_BUFFER_MULTIPLIER * rfToJoules, 1,
                MachineUpgradePolicy.NONE);
        MachineSlotLayout layout = layout(meta);
        CloudworksMachineAdapter adapter = TeDynamoFuels.byMeta(meta, rfToJoules);
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(
                dynamoStack(dynamoItem, meta, level), profile, layout, CloudworksTeMachines.LEVEL,
                Collections.singletonList(new CloudworksRecipeMode(MODE_IDS[meta], 0, layout, adapter))));
    }

    private static MachineSlotLayout layout(int meta) {
        MachineSlotLayout layout = new MachineSlotLayout();
        switch (meta) {
            // Steam: item fuel plus the parallel water stream.
            case 0 -> layout
                    .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT,
                            DYNAMO_FLUID_PORT_CAPACITY);
            // Magmatic: fuel and coolant tanks, including the ordinary water mode.
            case 1 -> layout
                    .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT,
                            DYNAMO_FLUID_PORT_CAPACITY)
                    .add("fluid_input_coolant", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT,
                            DYNAMO_FLUID_PORT_CAPACITY);
            // Compression: fuel fluid plus its coolant stream.
            case 2 -> layout
                    .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT,
                            DYNAMO_FLUID_PORT_CAPACITY)
                    .add("fluid_input_coolant", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT,
                            DYNAMO_FLUID_PORT_CAPACITY);
            // Reactant: item x fluid pairs.
            case 3 -> layout
                    .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT,
                            DYNAMO_FLUID_PORT_CAPACITY);
            // Enervation: table items burn up; chargeables come back discharged.
            case 4 -> layout
                    .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                    .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
            // Numismatic: coins only.
            default -> layout
                    .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64);
        }
        return layout;
    }

    /**
     * Identity carrier: exactly the Level byte, same rule as the machines.
     */
    static ItemStack dynamoStack(Item dynamoItem, int meta, int level) {
        ItemStack dynamo = new ItemStack(dynamoItem, 1, meta);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByte("Level", (byte) level);
        dynamo.setTagCompound(tag);
        return dynamo;
    }

    /**
     * Live dynamo power scaling. The field is protected (unlike the machine
     * family's public table), so it is read reflectively once at freeze-time
     * registration — the same third-party config compatibility reflection the
     * RandomComplement snapshot uses — and falls back to the native default.
     */
    private static int[] dynamoPowerScaling() {
        try {
            Field field = TileDynamoBase.class.getDeclaredField("POWER_SCALING");
            field.setAccessible(true);
            int[] live = (int[]) field.get(null);
            if (live != null && live.length > 0) return live.clone();
        } catch (ReflectiveOperationException | ClassCastException | LinkageError inaccessible) {
            // A moved field must not break registration; the native default is safe.
        }
        return new int[]{100, 150, 200, 250, 300};
    }
}
