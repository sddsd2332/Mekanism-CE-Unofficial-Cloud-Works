package mekanism.cloudworks.common.content;

import mekanism.api.inventory.IInventorySlot;

import java.util.List;

/**
 * Host of the five terminal functional slots ({@link CloudworksTerminalSlots}):
 * the portable terminal item keeps them in its stack NBT, the dashboard tile in
 * tile NBT. Cloud transfers journal the resulting slot projection separately.
 */
public interface CloudworksTerminalHost {
    java.util.UUID terminalId();
    default java.util.UUID appliedTerminalTransfer() { return null; }
    default void acknowledgeTerminalTransfer(java.util.UUID transferId) { }

    /** A detached or replaced physical host cannot receive a journal projection. */
    default boolean available() { return true; }

    /** Start persisting the physical source/acknowledgement before releasing a transfer. */
    default void persistIdentity() { persist(); }

    /** Nonblocking confirmation; a queued chunk save is not a durable acknowledgement. */
    default boolean identityDurable() { return true; }

    /**
     * Exactly five slots in the contract order: machine in, machine out,
     * energy in, upgrade in, upgrade out.
     */
    List<IInventorySlot> terminalSlots();

    /**
     * Called after any slot mutation that should outlive the tick (host-side
     * persistence; the cloud side has its own ordered-write pipeline).
     */
    void persist();
}
