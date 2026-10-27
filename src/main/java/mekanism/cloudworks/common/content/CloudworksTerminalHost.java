package mekanism.cloudworks.common.content;

import mekanism.api.inventory.IInventorySlot;

import java.util.List;
import java.util.UUID;

/**
 * Host of the five terminal functional slots ({@link CloudworksTerminalSlots}):
 * the portable terminal item keeps them in its stack NBT, the dashboard tile in
 * tile NBT. Cloud transfers journal the resulting slot projection separately.
 */
public interface CloudworksTerminalHost {
    UUID terminalId();
    default UUID appliedTerminalTransfer() { return null; }
    default void acknowledgeTerminalTransfer(UUID transferId) { }

    /** A detached or replaced physical host cannot receive a journal projection. */
    default boolean available() { return true; }

    /** Start persisting the physical source/acknowledgement before releasing a transfer. */
    default void persistIdentity() { persist(); }

    /** Nonblocking confirmation; a queued chunk save is not a durable acknowledgement. */
    default boolean identityDurable() { return true; }

    /** Team terminal items may retarget after the old domain's durable transfer is acknowledged. */
    default boolean allowsOwnerChange() { return false; }

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
