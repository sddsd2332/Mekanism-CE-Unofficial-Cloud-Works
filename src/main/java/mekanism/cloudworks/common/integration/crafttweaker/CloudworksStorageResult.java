package mekanism.cloudworks.common.integration.crafttweaker;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import crafttweaker.annotations.ZenRegister;
import mekanism.cloudworks.api.transfer.CloudworksDepositResult;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;

/** Nonblocking status. No world, player or mutable cloud state is retained. */
@ZenClass("mods.cloudworks.StorageResult")
@ZenRegister
public final class CloudworksStorageResult {
    private final CompletableFuture<CloudworksDepositResult> result = new CompletableFuture<>();

    CloudworksStorageResult(CompletionStage<CloudworksDepositResult> stage) {
        stage.whenComplete((value, failure) -> result.complete(failure == null && value != null ? value
                : CloudworksDepositResult.refused(CloudworksDepositResult.Status.STORAGE_ERROR, "cloudworks.error.storage")));
    }
    static CloudworksStorageResult refused(CloudworksDepositResult.Status status, String error) {
        return new CloudworksStorageResult(CompletableFuture.completedFuture(CloudworksDepositResult.refused(status, error)));
    }
    @ZenGetter("done")
    public boolean isDone() { return result.isDone(); }
    @ZenGetter("accepted")
    public boolean isAccepted() {
        CloudworksDepositResult value = result.getNow(null);
        return value != null && value.accepted();
    }
    @ZenGetter("amount")
    public long getAmount() {
        CloudworksDepositResult value = result.getNow(null);
        return value == null ? 0 : value.amount();
    }
    @ZenGetter("status")
    public String getStatus() {
        CloudworksDepositResult value = result.getNow(null);
        return value == null ? "PENDING" : value.status().name();
    }
    @ZenGetter("error")
    public String getError() {
        CloudworksDepositResult value = result.getNow(null);
        return value == null ? "" : value.error();
    }
}
