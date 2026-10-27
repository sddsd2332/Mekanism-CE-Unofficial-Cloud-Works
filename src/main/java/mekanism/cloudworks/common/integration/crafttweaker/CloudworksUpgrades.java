package mekanism.cloudworks.common.integration.crafttweaker;

import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.module.CloudworksUpgradeItems;
import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.common.Upgrade;
import net.minecraft.item.ItemStack;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;
import net.minecraft.util.ResourceLocation;

@ZenClass("mods.cloudworks.Upgrades")
@ZenRegister
public final class CloudworksUpgrades {
    private CloudworksUpgrades() {}

    private static Upgrade type(String name) {
        if ("speed".equals(name)) return Upgrade.SPEED;
        if ("energy".equals(name)) return Upgrade.ENERGY;
        throw new IllegalArgumentException("Cloud upgrade type must be speed or energy");
    }

    @ZenMethod
    public static void setItem(String name, IItemStack source) {
        Upgrade type = type(name);
        ItemStack stack = CloudworksMachines.stack(source);
        CloudworksScriptAction.submit("Cloudworks upgrade item " + name,
                () -> CloudworksUpgradeItems.local().bind(type, stack), true);
    }

    @ZenMethod
    public static void useNativeItem(String name) {
        Upgrade type = type(name);
        CloudworksScriptAction.submit("Cloudworks native upgrade item " + name,
                () -> CloudworksUpgradeItems.local().useNative(type), true);
    }

    @ZenMethod
    public static IItemStack getItem(String name) {
        ItemStack stack = CloudworksUpgradeItems.local().stack(type(name));
        return stack.isEmpty() ? null : CraftTweakerMC.getIItemStack(stack);
    }

    /** Namespaced ids of every registered custom upgrade type (8.5-B; read-only). */
    @ZenMethod
    public static String[] customTypes() {
        return CloudworksAPI.machineModuleTypes().stream()
                .filter(type -> type.slot() == MachineModuleType.Slot.CUSTOM)
                .map(type -> type.id().toString())
                .toArray(String[]::new);
    }

    /** Physical upload item bound to a custom type, or null when the id is unknown or editor-only. */
    @ZenMethod
    public static IItemStack getCustomItem(String id) {
        MachineModuleType type;
        try {
            ResourceLocation parsed = new ResourceLocation(id);
            type = CloudworksAPI.machineModuleTypes().stream().filter(candidate -> candidate.id().equals(parsed))
                    .findFirst().orElse(null);
        } catch (Exception invalid) {
            return null;
        }
        if (type == null || type.slot() != MachineModuleType.Slot.CUSTOM) return null;
        ItemStack stack = type.item();
        return stack.isEmpty() ? null : CraftTweakerMC.getIItemStack(stack);
    }
}
