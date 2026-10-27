package mekanism.cloudworks.common.integration.crafttweaker;

import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.environment.CloudworksLootInjection;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.OreDictionary;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

/** CraftTweaker declarations for manual probability drops on the frozen loot pipeline. */
@ZenClass("mods.cloudworks.LootDrops")
@ZenRegister
public final class CloudworksLootDrops {
    private CloudworksLootDrops() {}

    /** Adds one item per matching entity kill with the supplied independent chance. */
    @ZenMethod
    public static void entity(String entityId, IItemStack item, double chance) {
        CloudworksLootInjection injection = CloudworksLootInjection.entity(
                entityId, item(item), 1, 1, chance);
        submit(injection);
    }

    /** Adds a ranged stack per matching entity kill with the supplied independent chance. */
    @ZenMethod
    public static void entityRange(String entityId, IItemStack item, int minCount,
            int maxCount, double chance) {
        CloudworksLootInjection injection = CloudworksLootInjection.entity(
                entityId, item(item), minCount, maxCount, chance);
        submit(injection);
    }

    /** Adds a metadata-specific ranged stack per matching entity kill. */
    @ZenMethod
    public static void entityMeta(String entityId, IItemStack item, int meta,
            int minCount, int maxCount, double chance) {
        CloudworksLootInjection injection = CloudworksLootInjection.entity(
                entityId, item(item), meta, minCount, maxCount, chance);
        submit(injection);
    }

    /** Adds one item per simulated kill or chest-table event on the named axis. */
    @ZenMethod
    public static void axis(String axisId, IItemStack item, double chance) {
        CloudworksLootInjection injection = CloudworksLootInjection.axis(
                axis(axisId), item(item), 1, 1, chance);
        submit(injection);
    }

    /** Adds a ranged stack per simulated kill or chest-table event on the named axis. */
    @ZenMethod
    public static void axisRange(String axisId, IItemStack item, int minCount,
            int maxCount, double chance) {
        CloudworksLootInjection injection = CloudworksLootInjection.axis(
                axis(axisId), item(item), minCount, maxCount, chance);
        submit(injection);
    }

    /** Adds a metadata-specific ranged stack per event on the named axis. */
    @ZenMethod
    public static void axisMeta(String axisId, IItemStack item, int meta,
            int minCount, int maxCount, double chance) {
        CloudworksLootInjection injection = CloudworksLootInjection.axis(
                axis(axisId), item(item), meta, minCount, maxCount, chance);
        submit(injection);
    }

    private static ResourceLocation axis(String value) {
        if (value == null || value.isEmpty())
            throw new IllegalArgumentException("Loot injection axis id cannot be empty");
        ResourceLocation id;
        try {
            id = new ResourceLocation(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid loot injection axis id: " + value, invalid);
        }
        if (!"cloudworks".equals(id.getNamespace()))
            throw new IllegalArgumentException("Loot injection axis id must use the cloudworks namespace");
        return id;
    }

    private static ResourceLocation item(IItemStack source) {
        ItemStack stack = CloudworksMachines.stack(source);
        NBTTagCompound tag = stack.getTagCompound();
        if (tag != null && !tag.isEmpty())
            throw new IllegalArgumentException("Loot injection items cannot carry NBT");
        if (stack.getMetadata() == OreDictionary.WILDCARD_VALUE)
            throw new IllegalArgumentException("Loot injection items require concrete metadata");
        ResourceLocation id = stack.getItem().getRegistryName();
        if (id == null)
            throw new IllegalArgumentException("Loot injection item is not registered");
        return id;
    }

    private static void submit(CloudworksLootInjection injection) {
        CloudworksScriptAction.submit("Cloudworks loot injection " + injection.describeTarget(),
                () -> CloudworksAPI.registerLootInjection(injection));
    }
}
