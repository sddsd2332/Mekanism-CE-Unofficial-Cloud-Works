package mekanism.cloudworks.common.service;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side inventory preflight used by node removal and machine return.
 */
public final class CloudworksInventoryUtil {
    private CloudworksInventoryUtil() {
    }

    public static boolean canFit(InventoryPlayer inventory, List<ItemStack> stacks) {
        List<ItemStack> simulated = new ArrayList<>();
        for (ItemStack existing : inventory.mainInventory) simulated.add(existing.copy());
        for (ItemStack source : stacks) {
            int remaining = source.getCount();
            for (ItemStack existing : simulated) {
                if (remaining <= 0 || existing.isEmpty() || !ItemStack.areItemsEqual(existing, source)
                        || !ItemStack.areItemStackTagsEqual(existing, source)) continue;
                int room = Math.min(existing.getMaxStackSize(), inventory.getInventoryStackLimit()) - existing.getCount();
                int moved = Math.min(Math.max(0, room), remaining);
                existing.grow(moved);
                remaining -= moved;
            }
            for (int i = 0; i < simulated.size() && remaining > 0; i++)
                if (simulated.get(i).isEmpty()) {
                    int moved = Math.min(source.getMaxStackSize(), remaining);
                    ItemStack placed = source.copy();
                    placed.setCount(moved);
                    simulated.set(i, placed);
                    remaining -= moved;
                }
            if (remaining > 0) return false;
        }
        return true;
    }

    public static boolean insertAll(InventoryPlayer inventory, List<ItemStack> stacks) {
        if (!canFit(inventory, stacks)) return false;
        for (ItemStack stack : stacks) if (!inventory.addItemStackToInventory(stack.copy())) return false;
        return true;
    }
}
