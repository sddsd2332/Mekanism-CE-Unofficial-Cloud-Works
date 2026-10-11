package mekanism.cloudworks.common.content;

import mekanism.api.IContentsListener;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.inventory.slot.InputInventorySlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Port input item slot: {@link InputInventorySlot} semantics (external automation may
 * insert, not extract) with a 8192 storage limit, following the
 * {@code FactoryExtraInventorySlot}/{@code BinInventorySlot} pattern — only the limit
 * changes, no insertion/extraction behavior is overridden. The container slot carries
 * the overstacked count through vanilla sync inside an NBT tag (SYNC_COUNT pattern).
 */
public class CloudworksPortInputSlot extends InputInventorySlot {
    /**
     * Default per-slot storage for the port item buffer.
     */
    public static final int LIMIT = 8192;
    /**
     * Vanilla-sync carrier for the real overstacked count (MoreMachine farm-output pattern).
     */
    private static final String SYNC_COUNT = "CloudworksPortInputCount";

    private CloudworksPortInputSlot(@Nullable IContentsListener listener, int x, int y) {
        super(BasicInventorySlot.alwaysTrue, BasicInventorySlot.alwaysTrue, listener, x, y);
        obeyStackLimit = false;
    }

    public static CloudworksPortInputSlot at(@Nullable IContentsListener listener, int x, int y) {
        return new CloudworksPortInputSlot(listener, x, y);
    }

    @Override
    public int getLimit(@Nonnull ItemStack stack) {
        return LIMIT;
    }

    @Override
    public PortInputContainerSlot createContainerSlot() {
        return new PortInputContainerSlot();
    }

    private void setFromContainer(ItemStack stack) {
        ItemStack decoded = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        if (!decoded.isEmpty() && decoded.hasTagCompound() && decoded.getTagCompound().hasKey(SYNC_COUNT)) {
            NBTTagCompound tag = decoded.getTagCompound().copy();
            int count = tag.getInteger(SYNC_COUNT);
            tag.removeTag(SYNC_COUNT);
            decoded.setTagCompound(tag.getKeySet().isEmpty() ? null : tag);
            decoded.setCount(Math.max(0, count));
        }
        setStackUnchecked(decoded);
    }

    /**
     * Window-scoped virtual container slot with overstack-safe vanilla sync.
     */
    public class PortInputContainerSlot extends VirtualInventoryContainerSlot {

        private boolean syncing;

        private PortInputContainerSlot() {
            super(CloudworksPortInputSlot.this, CloudworksWindowTypes.PORT_ITEMS_DATA,
                    CloudworksPortInputSlot.this.getSlotOverlay(), CloudworksPortInputSlot.this::setFromContainer);
        }

        public void setSyncing(boolean syncing) {
            this.syncing = syncing;
        }

        @Nonnull
        @Override
        public ItemStack getItem() {
            ItemStack stored = CloudworksPortInputSlot.this.getStack();
            if (stored.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack visible = stored.copy();
            visible.setCount(Math.min(stored.getCount(), Math.max(1, stored.getMaxStackSize())));
            if (syncing) {
                NBTTagCompound tag = visible.hasTagCompound() ? visible.getTagCompound().copy() : new NBTTagCompound();
                tag.setInteger(SYNC_COUNT, stored.getCount());
                visible.setTagCompound(tag);
            }
            return visible;
        }

        @Nonnull
        @Override
        public ItemStack getStackToRender() {
            return CloudworksPortInputSlot.this.getStack().copy();
        }
    }
}
