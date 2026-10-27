package mekanism.cloudworks.common.integration.crafttweaker;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import mekanism.cloudworks.api.transfer.CloudworksTransferResult;
import net.minecraft.item.ItemStack;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import java.util.ArrayList;
import java.util.List;

/** Nonblocking transfer result. The caller owns writing downloaded values to its inventory. */
@ZenClass("mods.cloudworks.TransferResult")
@ZenRegister
public final class CloudworksTransferStorageResult {
    private final CompletableFuture<CloudworksTransferResult> result = new CompletableFuture<>();
    CloudworksTransferStorageResult(CompletionStage<CloudworksTransferResult> stage) {
        stage.whenComplete((value, failure) -> result.complete(failure == null && value != null ? value
                : CloudworksTransferResult.refused(CloudworksTransferResult.Status.STORAGE_ERROR, "cloudworks.error.storage")));
    }
    static CloudworksTransferStorageResult refused(CloudworksTransferResult.Status status, String error) {
        return new CloudworksTransferStorageResult(CompletableFuture.completedFuture(
                CloudworksTransferResult.refused(status, error)));
    }
    @ZenGetter("done")
    public boolean isDone() { return result.isDone(); }
    @ZenGetter("accepted")
    public boolean isAccepted() { CloudworksTransferResult value = result.getNow(null); return value != null && value.accepted(); }
    @ZenGetter("amount")
    public long getAmount() { CloudworksTransferResult value = result.getNow(null); return value == null ? 0 : value.amount(); }
    @ZenGetter("status")
    public String getStatus() { CloudworksTransferResult value = result.getNow(null); return value == null ? "PENDING" : value.status().name(); }
    @ZenGetter("error")
    public String getError() { CloudworksTransferResult value = result.getNow(null); return value == null ? "" : value.error(); }
    @ZenGetter("machines")
    public IItemStack[] getMachines() {
        CloudworksTransferResult value = result.getNow(null);
        if (value == null) return new IItemStack[0];
        List<IItemStack> result = new ArrayList<>();
        for (ItemStack machine : value.machines()) result.add(CraftTweakerMC.getIItemStack(machine));
        return result.toArray(new IItemStack[0]);
    }
}
