package mekanism.cloudworks.common.upgrade;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.cloudworks.api.module.UpgradeAxis;
import mekanism.common.Upgrade;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import java.util.ArrayList;
import java.util.List;
import mekanism.cloudworks.api.module.CloudworksUpgradeItems;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import net.minecraft.init.Items;
import net.minecraft.item.Item;

/** Frozen registry of module identity, item binding and lifecycle implementations. */
public final class CloudworksMachineModuleRegistry {
    private static final Map<ResourceLocation, MachineModuleType> TYPES = new LinkedHashMap<>();

    private CloudworksMachineModuleRegistry() {}

    public static void freezeAndRegister() {
        Map<ResourceLocation, MachineModuleType> checkpoint = new LinkedHashMap<>(TYPES);
        try {
            CloudworksAPI.freezeAndDrainMachineModules(type -> {
                if (TYPES.containsKey(type.id()))
                    throw new IllegalArgumentException("Duplicate machine module type: " + type.id());
                TYPES.put(type.id(), type);
            });
            TYPES.putIfAbsent(MachineModuleType.SPEED_ID, MachineModuleType.speed());
            TYPES.putIfAbsent(MachineModuleType.ENERGY_ID, MachineModuleType.energy());
            seedEnvironment(MachineModuleType.lavaHeat(), "cloud_lava_heat_simulation_card");
            seedEnvironment(MachineModuleType.height(), "cloud_height_simulation_card");
            // Axis-priority batch 3: Mek speed/energy upgrades as node modules
            // taking over their axis; the effect still flows through the node
            // policy curve using the module's effective count. Item binding is
            // defensive like CloudworksUpgradeEffects.settings(): a live server
            // always has the Mek items, headless fixtures do not.
            TYPES.putIfAbsent(MachineModuleType.MEK_SPEED_MODULE_ID,
                    bindModuleItem(MachineModuleType.builtInAxisModule(MachineModuleType.MEK_SPEED_MODULE_ID, 8,
                            "upgrade.cloudworks.mekanism_speed_upgrade", UpgradeAxis.SPEED), Upgrade.SPEED));
            TYPES.putIfAbsent(MachineModuleType.MEK_ENERGY_MODULE_ID,
                    bindModuleItem(MachineModuleType.builtInAxisModule(MachineModuleType.MEK_ENERGY_MODULE_ID, 8,
                            "upgrade.cloudworks.mekanism_energy_upgrade", UpgradeAxis.ENERGY), Upgrade.ENERGY));
            for (MachineModuleType type : MachineModuleType.environmentSources()) TYPES.putIfAbsent(type.id(), type);
            validateBindings();
        } catch (RuntimeException failure) {
            TYPES.clear();
            TYPES.putAll(checkpoint);
            throw failure;
        }
    }

    private static void validateBindings() {
        List<MachineModuleType> bound = new ArrayList<>();
        for (MachineModuleType type : TYPES.values())
            if (type.slot() == MachineModuleType.Slot.CUSTOM && !type.item().isEmpty()) bound.add(type);
        for (int first = 0; first < bound.size(); first++)
            for (int second = first + 1; second < bound.size(); second++)
                if (same(bound.get(first).item(), bound.get(second).item()))
                    throw new IllegalArgumentException("Custom module item identifies two types: "
                            + bound.get(first).id() + " and " + bound.get(second).id());
        validateUpgradeItemBindings();
    }

    /**
     * Late re-validation of the final upgrade item bindings. CraftTweaker scripts
     * run after the module drain and may rebind line upgrade items; bootstrap
     * calls this again before freezing (axis-priority batch 1).
     */
    public static void validateUpgradeItemBindings() {
        for (MachineModuleType type : TYPES.values())
            if (type.slot() == MachineModuleType.Slot.CUSTOM && !type.item().isEmpty()
                    && CloudworksUpgradeItems.local().typeOf(type.item()) != null)
                throw new IllegalArgumentException("Custom module item already identifies a native slot: " + type.id());
    }

    public static ResourceLocation typeForItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        ResourceLocation name = stack.getItem().getRegistryName();
        if (name != null && "cloudworks".equals(name.getNamespace()) && !stack.hasTagCompound()) {
            String path = name.getPath();
            if (path.equals("cloud_lava_heat_simulation_card") && TYPES.containsKey(MachineModuleType.LAVA_HEAT_ID))
                return MachineModuleType.LAVA_HEAT_ID;
            if (path.equals("cloud_height_simulation_card") && TYPES.containsKey(MachineModuleType.HEIGHT_ID))
                return MachineModuleType.HEIGHT_ID;
        }
        for (MachineModuleType type : TYPES.values()) {
            if (type.slot() != MachineModuleType.Slot.CUSTOM) continue;
            ItemStack template = type.item();
            if (!template.isEmpty() && ItemStack.areItemsEqual(stack, template)
                    && ItemStack.areItemStackTagsEqual(stack, template)) return type.id();
        }
        return null;
    }

    private static void seedEnvironment(MachineModuleType type, String path) {
        Item item = Item.REGISTRY.getObject(new ResourceLocation("cloudworks", path));
        if (item != null && item != Items.AIR) type = type.withItem(new ItemStack(item));
        TYPES.putIfAbsent(type.id(), type);
    }

    private static boolean same(ItemStack first, ItemStack second) {
        return !first.isEmpty() && !second.isEmpty()
                && ItemStack.areItemsEqual(first, second) && ItemStack.areItemStackTagsEqual(first, second);
    }

    public static MachineModuleType type(ResourceLocation id) { return TYPES.get(id); }

    private static MachineModuleType<MachineModuleType.EmptyModule> bindModuleItem(
            MachineModuleType<MachineModuleType.EmptyModule> module, Upgrade upgrade) {
        try {
            ItemStack stack = UpgradeUtils.getStack(upgrade);
            return stack.isEmpty() ? module : module.withItem(stack);
        } catch (Throwable unavailable) {
            return module;
        }
    }
    public static boolean isKnown(ResourceLocation id) { return TYPES.containsKey(id); }
    public static Map<ResourceLocation, MachineModuleType> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(TYPES));
    }

    public static void resetForTests() {
        TYPES.clear();
        CloudworksNodeMechanisms.resetForTests();
    }
}
