package mekanism.cloudworks.common.content;

import net.minecraft.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

/** Inventory insertion helpers that report the exact accepted count, including partial stacks. */
public final class CloudworksRecoveryInventoryTransfer {
    private CloudworksRecoveryInventoryTransfer() {
    }

    public static int accepted(IItemHandler inventory, ItemStack template, int requested) {
        if (inventory == null || template == null || template.isEmpty() || requested <= 0) return 0;
        ItemStack offered = template.copy();
        offered.setCount(requested);
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory, offered, true);
        return requested - (remainder.isEmpty() ? 0 : remainder.getCount());
    }

    public static int insert(IItemHandler inventory, ItemStack template, int requested) {
        if (inventory == null || template == null || template.isEmpty() || requested <= 0) return 0;
        ItemStack offered = template.copy();
        offered.setCount(requested);
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory, offered, false);
        return requested - (remainder.isEmpty() ? 0 : remainder.getCount());
    }
}
