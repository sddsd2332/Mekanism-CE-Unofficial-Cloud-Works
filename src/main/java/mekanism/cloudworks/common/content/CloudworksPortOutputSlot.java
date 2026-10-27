package mekanism.cloudworks.common.content;

import mekanism.api.IContentsListener;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.BooleanSupplier;

/**
 * Port output item slot: output semantics (external automation may extract; only
 * the port logic inserts) with a 8192 storage limit, following the
 * {@code FactoryExtraInventorySlot}/{@code BinInventorySlot} pattern — only the
 * limit changes; an optional team-lifecycle predicate can pause extraction. The container
 * slot carries the overstacked count through vanilla sync inside an NBT tag
 * (SYNC_COUNT pattern).
 */
public class CloudworksPortOutputSlot extends BasicInventorySlot {
    /**
     * Default per-slot storage for the port item buffer.
     */
    public static final int LIMIT = 8192;
    /**
     * Vanilla-sync carrier for the real overstacked count (MoreMachine farm-output pattern).
     */
    private static final String SYNC_COUNT = "CloudworksPortOutputCount";

    private CloudworksPortOutputSlot(@Nullable IContentsListener listener, int x, int y) {
        this(() -> true, listener, x, y);
    }

    private CloudworksPortOutputSlot(BooleanSupplier enabled, @Nullable IContentsListener listener, int x, int y) {
        super(LIMIT, (stack, automation) -> enabled.getAsBoolean(), BasicInventorySlot.internalOnly,
                BasicInventorySlot.alwaysTrue, listener, x, y);
        obeyStackLimit = false;
    }

    public static CloudworksPortOutputSlot at(@Nullable IContentsListener listener, int x, int y) {
        return new CloudworksPortOutputSlot(listener, x, y);
    }

    public static CloudworksPortOutputSlot at(BooleanSupplier enabled,
            @Nullable IContentsListener listener, int x, int y) {
        return new CloudworksPortOutputSlot(enabled, listener, x, y);
    }

    @Override
    public int getLimit(@Nonnull ItemStack stack) {
        return LIMIT;
    }

    @Override
    public PortOutputContainerSlot createContainerSlot() {
        return new PortOutputContainerSlot();
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
    public class PortOutputContainerSlot extends VirtualInventoryContainerSlot {

        private boolean syncing;

        private PortOutputContainerSlot() {
            super(CloudworksPortOutputSlot.this, CloudworksWindowTypes.PORT_OUT_ITEMS_DATA,
                    CloudworksPortOutputSlot.this.getSlotOverlay(), CloudworksPortOutputSlot.this::setFromContainer);
        }

        public void setSyncing(boolean syncing) {
            this.syncing = syncing;
        }

        @Nonnull
        @Override
        public ItemStack getItem() {
            ItemStack stored = CloudworksPortOutputSlot.this.getStack();
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
            return CloudworksPortOutputSlot.this.getStack().copy();
        }
    }
}
