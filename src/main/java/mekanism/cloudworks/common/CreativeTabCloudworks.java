package mekanism.cloudworks.common;

import mekanism.cloudworks.common.registries.CloudworksItems;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;

public final class CreativeTabCloudworks extends CreativeTabs {
    public CreativeTabCloudworks() {
        super("tabCloudworks");
    }

    @Override
    public ItemStack createIcon() {
        return new ItemStack(CloudworksItems.PortableTerminal);
    }
}
