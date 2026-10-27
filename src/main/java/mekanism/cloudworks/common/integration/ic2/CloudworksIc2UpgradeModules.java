package mekanism.cloudworks.common.integration.ic2;

import ic2.core.item.upgrade.ItemUpgradeModule.UpgradeType;
import ic2.core.ref.ItemName;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.module.*;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/** Item bindings remain inside the optional IC2 boundary. */
public final class CloudworksIc2UpgradeModules {
    public static final ResourceLocation OVERCLOCKER = new ResourceLocation("ic2", "overclocker");
    public static final ResourceLocation STORAGE = new ResourceLocation("ic2", "energy_storage");
    private CloudworksIc2UpgradeModules() {}
    public static void register(double euToJ) {
        CloudworksAPI.registerMachineModule(overclocker(euToJ)); CloudworksAPI.registerMachineModule(storage(euToJ));
    }
    public static MachineModuleType<CloudworksIc2Overclocker> overclocker(double factor) {
        MachineModuleType<CloudworksIc2Overclocker> type = MachineModuleType.custom(OVERCLOCKER, 256,
                "ic2.upgrade.overclocker", new CloudworksIc2Overclocker(factor)).withUpgradeAxes(UpgradeAxis.SPEED);
        ItemStack item = ItemName.upgrade.hasInstance() ? ItemName.upgrade.getItemStack(UpgradeType.overclocker) : ItemStack.EMPTY;
        return item.isEmpty() ? type : type.withItem(item);
    }
    public static MachineModuleType<CloudworksIc2StorageUpgrade> storage(double factor) {
        MachineModuleType<CloudworksIc2StorageUpgrade> type = MachineModuleType.custom(STORAGE, 256,
                "ic2.upgrade.energy_storage", new CloudworksIc2StorageUpgrade(factor));
        ItemStack item = ItemName.upgrade.hasInstance() ? ItemName.upgrade.getItemStack(UpgradeType.energy_storage) : ItemStack.EMPTY;
        return item.isEmpty() ? type : type.withItem(item);
    }
    public static void accept(ItemStack machine, double factor) {
        CloudworksAPI.acceptMachineUpgrade(machine, overclocker(factor), 256);
        CloudworksAPI.acceptMachineUpgrade(machine, storage(factor), 256);
    }
}
