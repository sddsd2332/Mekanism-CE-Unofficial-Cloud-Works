package mekanism.cloudworks.common.integration.crafttweaker;

import java.util.UUID;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.player.IPlayer;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.transfer.CloudworksDepositRequest;
import mekanism.cloudworks.api.transfer.CloudworksDepositResult;
import mekanism.cloudworks.api.transfer.CloudworksTransferRequest;
import mekanism.cloudworks.api.transfer.CloudworksTransferResult;
import mekanism.cloudworks.api.module.MachineModuleType;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;
import java.util.function.Supplier;

/** Runtime rewards only; physical inputs are the calling pack's responsibility. */
@ZenClass("mods.cloudworks.Storage")
@ZenRegister
public final class CloudworksStorage {
    private CloudworksStorage() {}

    @ZenMethod
    public static CloudworksStorageResult addMachine(IPlayer player, IItemStack machine) {
        return addMachine(player, machine, machine == null ? 0 : machine.getAmount());
    }
    @ZenMethod
    public static CloudworksStorageResult addMachine(IPlayer player, IItemStack machine, long amount) {
        return addMachineTo(player, "", machine, amount);
    }
    @ZenMethod
    public static CloudworksStorageResult addMachineTo(IPlayer player, String owner, IItemStack machine, long amount) {
        return submit(player, owner, () -> CloudworksDepositRequest.machines(CloudworksMachines.stack(machine), amount), amount);
    }
    @ZenMethod
    public static CloudworksStorageResult addUpgrade(IPlayer player, IItemStack upgrade) {
        return addUpgrade(player, upgrade, upgrade == null ? 0 : upgrade.getAmount());
    }
    @ZenMethod
    public static CloudworksStorageResult addUpgrade(IPlayer player, IItemStack upgrade, long amount) {
        return addUpgradeTo(player, "", upgrade, amount);
    }
    @ZenMethod
    public static CloudworksStorageResult addUpgradeTo(IPlayer player, String owner, IItemStack upgrade, long amount) {
        return submit(player, owner, () -> CloudworksDepositRequest.upgrades(CloudworksMachines.stack(upgrade), amount), amount);
    }
    @ZenMethod
    public static CloudworksStorageResult addUpgradeType(IPlayer player, String type, long amount) {
        return addUpgradeTypeTo(player, "", type, amount);
    }
    @ZenMethod
    public static CloudworksStorageResult addUpgradeTypeTo(IPlayer player, String owner, String type, long amount) {
        return submit(player, owner, () -> CloudworksDepositRequest.upgradeType(
                "speed".equals(type) ? MachineModuleType.SPEED_ID
                        : "energy".equals(type) ? MachineModuleType.ENERGY_ID : new ResourceLocation(type), amount), amount);
    }

    @ZenMethod
    public static CloudworksTransferStorageResult uploadMachine(IPlayer player, IItemStack machine, long amount) {
        return uploadMachineTo(player, "", machine, amount);
    }
    @ZenMethod
    public static CloudworksTransferStorageResult uploadMachineTo(IPlayer player, String owner,
            IItemStack machine, long amount) {
        return transfer(player, owner, () -> CloudworksTransferRequest.uploadMachine(
                CloudworksMachines.stack(machine), amount));
    }
    @ZenMethod
    public static CloudworksTransferStorageResult downloadMachine(IPlayer player, IItemStack machine, long amount) {
        return downloadMachineTo(player, "", machine, amount);
    }
    @ZenMethod
    public static CloudworksTransferStorageResult downloadMachineTo(IPlayer player, String owner,
            IItemStack machine, long amount) {
        return transfer(player, owner, () -> CloudworksTransferRequest.downloadMachine(
                CloudworksMachines.stack(machine), amount));
    }
    @ZenMethod
    public static CloudworksTransferStorageResult uploadUpgradeType(IPlayer player, String type, long amount) {
        return uploadUpgradeTypeTo(player, "", type, amount);
    }
    @ZenMethod
    public static CloudworksTransferStorageResult downloadUpgradeType(IPlayer player, String type, long amount) {
        return downloadUpgradeTypeTo(player, "", type, amount);
    }
    @ZenMethod
    public static CloudworksTransferStorageResult uploadUpgradeTypeTo(IPlayer player, String owner, String type, long amount) {
        return transfer(player, owner, () -> CloudworksTransferRequest.uploadUpgrade(parseUpgrade(type), amount));
    }
    @ZenMethod
    public static CloudworksTransferStorageResult downloadUpgradeTypeTo(IPlayer player, String owner, String type, long amount) {
        return transfer(player, owner, () -> CloudworksTransferRequest.downloadUpgrade(parseUpgrade(type), amount));
    }
    @ZenMethod
    public static CloudworksTransferStorageResult uploadResource(IPlayer player, CloudworksResources resource) {
        return uploadResourceTo(player, "", resource);
    }
    @ZenMethod
    public static CloudworksTransferStorageResult downloadResource(IPlayer player, CloudworksResources resource) {
        return downloadResourceTo(player, "", resource);
    }
    @ZenMethod
    public static CloudworksTransferStorageResult uploadResourceTo(IPlayer player, String owner, CloudworksResources resource) {
        return transfer(player, owner, () -> CloudworksTransferRequest.uploadResource(resource.single(), resource.amount));
    }
    @ZenMethod
    public static CloudworksTransferStorageResult downloadResourceTo(IPlayer player, String owner, CloudworksResources resource) {
        return transfer(player, owner, () -> CloudworksTransferRequest.downloadResource(resource.single(), resource.amount));
    }

    private static ResourceLocation parseUpgrade(String type) {
        return "speed".equals(type) ? MachineModuleType.SPEED_ID
                : "energy".equals(type) ? MachineModuleType.ENERGY_ID : new ResourceLocation(type);
    }

    private static CloudworksTransferStorageResult transfer(IPlayer player, String owner,
            Supplier<CloudworksTransferRequest> request) {
        try {
            Object internal = player == null ? null : player.getInternal();
            if (!(internal instanceof EntityPlayerMP serverPlayer) || serverPlayer.world == null || serverPlayer.world.isRemote)
                return CloudworksTransferStorageResult.refused(CloudworksTransferResult.Status.UNAVAILABLE, "cloudworks.error.storage");
            UUID target = owner == null || owner.isEmpty() ? serverPlayer.getUniqueID() : UUID.fromString(owner);
            return new CloudworksTransferStorageResult(CloudworksAPI.transfer(
                    serverPlayer.getUniqueID(), target, request.get()));
        } catch (RuntimeException invalid) {
            return CloudworksTransferStorageResult.refused(CloudworksTransferResult.Status.INVALID_ITEM,
                    "cloudworks.error.deposit_item");
        }
    }

    private static CloudworksStorageResult submit(IPlayer player, String owner,
            Supplier<CloudworksDepositRequest> request, long amount) {
        if (amount <= 0) return CloudworksStorageResult.refused(CloudworksDepositResult.Status.INVALID_AMOUNT, "cloudworks.error.deposit_amount");
        try {
            Object internal = player == null ? null : player.getInternal();
            if (!(internal instanceof EntityPlayerMP serverPlayer) || serverPlayer.world == null || serverPlayer.world.isRemote)
                return CloudworksStorageResult.refused(CloudworksDepositResult.Status.UNAVAILABLE, "cloudworks.error.storage");
            UUID actor = serverPlayer.getUniqueID();
            UUID target = owner == null || owner.isEmpty() ? actor : UUID.fromString(owner);
            return new CloudworksStorageResult(CloudworksAPI.deposit(actor, target, request.get()));
        } catch (RuntimeException invalid) {
            return CloudworksStorageResult.refused(CloudworksDepositResult.Status.INVALID_ITEM, "cloudworks.error.deposit_item");
        }
    }
}
