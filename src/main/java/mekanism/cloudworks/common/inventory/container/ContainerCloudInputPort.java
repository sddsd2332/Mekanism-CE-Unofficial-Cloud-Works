package mekanism.cloudworks.common.inventory.container;

import mekanism.cloudworks.common.content.CloudworksPortInputSlot.PortInputContainerSlot;
import mekanism.cloudworks.common.tile.TileEntityCloudInput;
import mekanism.common.inventory.container.MekanismTileContainer;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;

import javax.annotation.Nonnull;

/**
 * Upload-port container: 121 window-scoped virtual item slots (overstack sync via
 * the SYNC_COUNT pattern) plus the player inventory. The port slots only exist
 * while the items window is open — the 协议储存箱 sub-UI mirrors the MoreMachine
 * farm-output container contract.
 */
public class ContainerCloudInputPort extends MekanismTileContainer<TileEntityCloudInput> {

    private PortInputContainerSlot[] portSlots;

    public ContainerCloudInputPort(InventoryPlayer inventory, TileEntityCloudInput tile) {
        super(tile, inventory);
    }

    @Override
    protected void addSlots() {
        super.addSlots();
        portSlots = new PortInputContainerSlot[tile == null ? 0 : tile.getInventorySlots(null).size()];
        if (tile == null) {
            return;
        }
        for (int i = 0; i < portSlots.length; i++) {
            for (int slot = 0; slot < inventorySlots.size(); slot++) {
                if (inventorySlots.get(slot) instanceof PortInputContainerSlot virtual
                        && virtual.getInventorySlot() == tile.getInventorySlots(null).get(i)) {
                    portSlots[i] = virtual;
                    break;
                }
            }
        }
    }

    public VirtualInventoryContainerSlot getPortSlot(int index) {
        return portSlots != null && index >= 0 && index < portSlots.length ? portSlots[index] : null;
    }

    @Override
    public NonNullList<ItemStack> getInventory() {
        setPortSlotsSyncing(true);
        try {
            return super.getInventory();
        } finally {
            setPortSlotsSyncing(false);
        }
    }

    @Override
    public void detectAndSendChanges() {
        setPortSlotsSyncing(true);
        try {
            super.detectAndSendChanges();
        } finally {
            setPortSlotsSyncing(false);
        }
    }

    @Nonnull
    @Override
    public ItemStack slotClick(int slotId, int dragType, ClickType clickType, EntityPlayer player) {
        if (slotId >= 0 && slotId < inventorySlots.size()) {
            Slot slot = inventorySlots.get(slotId);
            if (slot instanceof PortInputContainerSlot portSlot) {
                SelectedWindowData selected = player.world.isRemote
                        ? getSelectedWindow() : getSelectedWindow(player.getUniqueID());
                if (!portSlot.exists(selected) || clickType == ClickType.SWAP) {
                    return ItemStack.EMPTY;
                }
            }
        }
        return super.slotClick(slotId, dragType, clickType, player);
    }

    private void setPortSlotsSyncing(boolean syncing) {
        if (portSlots == null) {
            return;
        }
        for (PortInputContainerSlot portSlot : portSlots) {
            if (portSlot != null) {
                portSlot.setSyncing(syncing);
            }
        }
    }
}

