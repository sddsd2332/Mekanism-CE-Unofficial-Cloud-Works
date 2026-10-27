package mekanism.cloudworks.api.transfer;

import java.util.Objects;

/** ACCEPTED means atomically admitted to cloud state and its normal persistence queue, not an fsync receipt. */
public final class CloudworksDepositResult {
    public enum Status { ACCEPTED, DENIED, DISABLED, INVALID_ITEM, INVALID_AMOUNT, UNAVAILABLE, STORAGE_ERROR }
    private final Status status;
    private final long amount;
    private final String error;

    public CloudworksDepositResult(Status status, long amount, String error) {
        this.status = Objects.requireNonNull(status);
        if (amount < 0 || (status == Status.ACCEPTED) != (amount > 0))
            throw new IllegalArgumentException("Invalid deposit result");
        this.amount = amount;
        this.error = Objects.requireNonNull(error);
    }
    public static CloudworksDepositResult accepted(long amount) {
        return new CloudworksDepositResult(Status.ACCEPTED, amount, "");
    }
    public static CloudworksDepositResult refused(Status status, String error) {
        return new CloudworksDepositResult(status, 0, error);
    }
    public Status status() { return status; }
    public boolean accepted() { return status == Status.ACCEPTED; }
    public long amount() { return amount; }
    public String error() { return error; }
}
