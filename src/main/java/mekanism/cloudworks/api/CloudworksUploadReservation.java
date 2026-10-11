package mekanism.cloudworks.api;

import net.minecraft.item.ItemStack;

import java.util.UUID;

/**
 * Immutable snapshot of one pending machine upload (design §4.2). The stack is
 * a deep copy with the full identity NBT — the caller can never keep mutating
 * the source reference — and {@link #state} drives the crash recovery rules of
 * design §5: RESERVED without a durable intent rolls back, CLOUD_COMMITTED only
 * ever acknowledges, and a repeated transferId is idempotent success.
 */
public final class CloudworksUploadReservation {
    private final UUID transferId, owner, sourceId, lineId;
    private final String sourceSlot;
    private final ItemStack machineStack;   // defensive copy, full NBT
    private final int count;
    private final State state;
    public CloudworksUploadReservation(UUID transferId, UUID owner, UUID sourceId, UUID lineId,
                                       String sourceSlot, ItemStack machineStack, int count, State state) {
        this.transferId = java.util.Objects.requireNonNull(transferId);
        this.owner = java.util.Objects.requireNonNull(owner);
        this.sourceId = java.util.Objects.requireNonNull(sourceId);
        this.lineId = java.util.Objects.requireNonNull(lineId);
        this.sourceSlot = sourceSlot;
        ItemStack copy = machineStack.copy();
        copy.setCount(Math.max(1, count));
        this.machineStack = copy;
        this.count = count;
        this.state = java.util.Objects.requireNonNull(state);
    }

    public UUID transferId() {
        return transferId;
    }

    public UUID owner() {
        return owner;
    }

    public UUID sourceId() {
        return sourceId;
    }

    public UUID lineId() {
        return lineId;
    }

    /**
     * Source-side slot or component identifier for diagnostics and recovery.
     */
    public String sourceSlot() {
        return sourceSlot;
    }

    /**
     * The exact machine, full NBT, count normalised to {@link #count}.
     */
    public ItemStack machineStack() {
        return machineStack.copy();
    }

    public int count() {
        return count;
    }

    public State state() {
        return state;
    }

    /**
     * Lifecycle of one reservation; transitions are coordinator-owned.
     */
    public enum State {RESERVED, ACKNOWLEDGED, ROLLED_BACK}
}
