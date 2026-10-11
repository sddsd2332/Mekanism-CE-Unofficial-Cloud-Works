package mekanism.cloudworks.common.inventory.slot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.slot.HotBarSlot;
import mekanism.common.inventory.container.slot.MainInventorySlot;
import mekanism.common.inventory.container.slot.VirtualHotBarSlot;
import mekanism.common.inventory.container.slot.VirtualMainInventorySlot;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;

/** Real player slots shared by the three terminal inventory windows. */
public final class CloudworksPlayerInventorySlots {

    private CloudworksPlayerInventorySlots() {
    }

    public static MainInventorySlot main(MekanismContainer container, IInventory inventory,
            int index, int x, int y) {
        return new TerminalMainInventorySlot(container, inventory, index, x, y);
    }

    public static HotBarSlot hotbar(MekanismContainer container, IInventory inventory,
            int index, int x, int y, boolean locked) {
        return new TerminalHotBarSlot(container, inventory, index, x, y, locked);
    }

    @Nullable
    private static SelectedWindowData selected(MekanismContainer container, EntityPlayer player) {
        return container.isRemote() ? container.getSelectedWindow()
                : container.getSelectedWindow(player.getUniqueID());
    }

    private static final class TerminalMainInventorySlot extends VirtualMainInventorySlot {
        private final MekanismContainer container;

        private TerminalMainInventorySlot(MekanismContainer container, IInventory inventory,
                int index, int x, int y) {
            super(inventory, index, x, y);
            this.container = container;
        }

        @Override
        public boolean exists(@Nullable SelectedWindowData windowData) {
            return CloudworksWindowTypes.isTerminalInventoryWindow(windowData);
        }

        @Override
        public boolean canTakeStack(@Nonnull EntityPlayer player) {
            return exists(selected(container, player)) && super.canTakeStack(player);
        }

        @Override
        public boolean isEnabled() {
            return container.isRemote() && exists(container.getSelectedWindow());
        }
    }

    private static final class TerminalHotBarSlot extends VirtualHotBarSlot {
        private final MekanismContainer container;
        private final boolean locked;

        private TerminalHotBarSlot(MekanismContainer container, IInventory inventory,
                int index, int x, int y, boolean locked) {
            super(inventory, index, x, y);
            this.container = container;
            this.locked = locked;
        }

        @Override
        public boolean exists(@Nullable SelectedWindowData windowData) {
            return CloudworksWindowTypes.isTerminalInventoryWindow(windowData);
        }

        @Override
        public boolean canTakeStack(@Nonnull EntityPlayer player) {
            return !locked && exists(selected(container, player)) && super.canTakeStack(player);
        }

        @Override
        public boolean isEnabled() {
            return container.isRemote() && exists(container.getSelectedWindow());
        }
    }
}
