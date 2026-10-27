package mekanism.cloudworks.api.transfer;

import java.util.Optional;
import java.util.UUID;
import java.util.Objects;

/**
 * Outcome of one coordinated machine upload (design §4.3). The coordinator is
 * {@code CloudworksPlayerDataManager.uploadMachines}; GUI, command and upload
 * block entries must route through it — no entry point may run its own
 * transfer logic.
 */
public final class CloudworksUploadResult {
    private final int transferred;
    private final Refusal refusal;
    private final UUID transferId;
    private CloudworksUploadResult(int transferred, Refusal refusal, UUID transferId) {
        this.transferred = transferred;
        this.refusal = refusal;
        this.transferId = transferId;
    }

    public static CloudworksUploadResult success(int transferred, UUID transferId) {
        return new CloudworksUploadResult(transferred, Refusal.NONE, transferId);
    }

    public static CloudworksUploadResult refused(Refusal refusal) {
        return new CloudworksUploadResult(0, Objects.requireNonNull(refusal), null);
    }

    public boolean success() {
        return refusal == Refusal.NONE;
    }

    public int transferred() {
        return transferred;
    }

    public Refusal refusal() {
        return refusal;
    }

    /**
     * Present on success; ties the result to the idempotently recoverable transaction.
     */
    public Optional<UUID> transferId() {
        return Optional.ofNullable(transferId);
    }

    /**
     * Machine-readable refusal reason; SUCCESS carries none.
     */
    public enum Refusal {
        NONE, LINE_NOT_FOUND, LINE_ACTIVE, NODE_LIMIT, MACHINE_UNREGISTERED,
        COUNT_INVALID, OWNER_MISMATCH, SOURCE_REFUSED, STORAGE_FAILURE,
        /**
         * Recovery only: the cloud never committed; the source rolled back (§5.1).
         */
        NOT_COMMITTED,
        /**
         * The owner's cloud record is disabled; uploads need an enabled cloud.
         */
        CLOUD_DISABLED,
        /**
         * Merge form only: the stack is not the target node's exact variant.
         */
        MACHINE_MISMATCH
    }
}
