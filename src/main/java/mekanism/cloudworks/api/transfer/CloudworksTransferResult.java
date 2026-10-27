package mekanism.cloudworks.api.transfer;

import java.util.Collections;
import java.util.List;
import net.minecraft.item.ItemStack;
import java.util.ArrayList;

/** Completed cloud-side transfer projection. External MMCE inventory changes remain caller-owned. */
public final class CloudworksTransferResult {
    public enum Status { ACCEPTED, DENIED, DISABLED, INVALID_ITEM, INVALID_AMOUNT, INSUFFICIENT, CAPACITY, UNAVAILABLE, STORAGE_ERROR }
    private final Status status;
    private final long amount;
    private final List<ItemStack> machines;
    private final String error;
    public CloudworksTransferResult(Status status, long amount, List<ItemStack> machines, String error) {
        this.status = status; this.amount = amount;
        this.machines = Collections.unmodifiableList(new ArrayList<>(machines));
        this.error = error == null ? "" : error;
    }
    public static CloudworksTransferResult accepted(long amount, List<ItemStack> machines) {
        return new CloudworksTransferResult(Status.ACCEPTED, amount, machines, "");
    }
    public static CloudworksTransferResult refused(Status status, String error) {
        return new CloudworksTransferResult(status, 0, Collections.emptyList(), error);
    }
    public Status status() { return status; }
    public boolean accepted() { return status == Status.ACCEPTED; }
    public long amount() { return amount; }
    public List<ItemStack> machines() { return machines; }
    public String error() { return error; }
}
