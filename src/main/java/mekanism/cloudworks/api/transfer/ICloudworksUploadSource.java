package mekanism.cloudworks.api.transfer;

import net.minecraft.item.ItemStack;

import java.util.UUID;

/**
 * Server-side source of machines uploaded into the cloud (GPT design
 * CLOUDWORKS_MACHINE_UPLOAD_COMPONENT_DESIGN_20261002 §4.1). Implemented by the
 * future item container and the upload-block component; NOT wired anywhere yet.
 *
 * <p>All methods run on the server thread. A source owns real slots, never a
 * detached {@link ItemStack} copy: {@link #reserve} snapshots the exact machine
 * (full NBT) into an immutable reservation, {@link #acknowledge} clears the
 * pending reservation after the cloud side committed durably, and
 * {@link #rollback} restores it on failure or recovery (durable intent absent).
 * The coordinator is {@code CloudworksPlayerDataManager.uploadMachines};
 * real source implementations (item container, upload block) are future work.
 * Both {@link #acknowledge} and {@link #rollback} must be idempotent: recovery
 * and retries may repeat them for the same transferId.</p>
 */
public interface ICloudworksUploadSource {
    /**
     * Stable identity of this source component (persisted with the reservation).
     */
    UUID sourceId();

    /**
     * Owner whose cloud receives the machines.
     */
    UUID owner();

    /**
     * The player whose inventory owes the physical deduction for this upload,
     * or null when the source has no physical side (admin grants, locked GUI
     * slots). Non-null makes the coordinator record a DURABLE debit entry the
     * moment the cloud side commits, so a crash between commit and acknowledge
     * still deducts exactly once — at the player's next login (GPT review 4).
     */
    default UUID debitPlayer() {
        return null;
    }

    /**
     * Fast pre-check: the source could serve this upload (no side effects).
     */
    boolean canUpload(UUID lineId, ItemStack requested, int count);

    /**
     * Snapshots {@code count} machines of the exact stack into a pending
     * reservation identified by {@code transferId}; the source slots must not
     * release the same machines to anyone else until acknowledge or rollback.
     */
    CloudworksUploadReservation reserve(UUID lineId, ItemStack requested, int count, UUID transferId);

    /**
     * Cloud committed durably: the reservation becomes final (idempotent).
     * Returns the OUTSTANDING SHORTFALL — units that could not be physically
     * deducted (0 = fully settled). A positive shortfall keeps the durable
     * debit entry alive for retry and audit; implementations must never
     * deduct unrelated items (identity-validate every stack they touch).
     */
    long acknowledge(UUID transferId);

    /**
     * Upload failed or was never committed: restore the reserved machines (idempotent).
     */
    void rollback(UUID transferId);
}
