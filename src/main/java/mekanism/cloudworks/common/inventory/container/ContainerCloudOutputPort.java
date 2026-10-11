package mekanism.cloudworks.common.inventory.container;

import mekanism.cloudworks.common.content.CloudworksPortOutputSlot.PortOutputContainerSlot;
import mekanism.cloudworks.common.tile.TileEntityCloudOutput;
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
 * Download-port container: 121 window-scoped virtual item slots (overstack sync
 * via the SYNC_COUNT pattern) plus the player inventory — the download mirror of
 * the upload-port container contract.
 */
public class ContainerCloudOutputPort extends MekanismTileContainer<TileEntityCloudOutput> {

    private PortOutputContainerSlot[] portSlots;

    public ContainerCloudOutputPort(InventoryPlayer inventory, TileEntityCloudOutput tile) {
        super(tile, inventory);
    }

    @Override
    protected void addSlots() {
        super.addSlots();
        portSlots = new PortOutputContainerSlot[tile == null ? 0 : tile.getInventorySlots(null).size()];
        if (tile == null) {
            return;
        }
        for (int i = 0; i < portSlots.length; i++) {
            for (int slot = 0; slot < inventorySlots.size(); slot++) {
                if (inventorySlots.get(slot) instanceof PortOutputContainerSlot virtual
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
            if (slot instanceof PortOutputContainerSlot portSlot) {
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
        for (PortOutputContainerSlot portSlot : portSlots) {
            if (portSlot != null) {
                portSlot.setSyncing(syncing);
            }
        }
    }
}

