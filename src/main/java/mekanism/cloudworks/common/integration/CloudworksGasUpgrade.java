package mekanism.cloudworks.common.integration;

import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.common.Upgrade;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/** Only explicit native secondary-fuel machines accept this module. */
public final class CloudworksGasUpgrade implements ICustomMachineModule<CloudworksGasUpgrade> {
    public static final ResourceLocation ID = new ResourceLocation("cloudworks", "mek_gas");
    public static MachineModuleType<CloudworksGasUpgrade> type() {
        MachineModuleType<CloudworksGasUpgrade> type = MachineModuleType.builtInModule(ID,
                Upgrade.GAS.getMaxInstalled(), "upgrade.gas", new CloudworksGasUpgrade());
        try {
            ItemStack item = Upgrade.GAS.getStack();
            return item.isEmpty() || item.getItem().getRegistryName() == null ? type : type.withItem(item);
        } catch (LinkageError unavailable) { return type; }
    }
    public static double multiplier(MachineNodeData node, double time) {
        int maximum = node.capabilities().profile() == null ? 0 : node.capabilities().profile().upgradePolicy().maxCustom(ID);
        if (maximum <= 0) return 1D;
        double count = Math.min(maximum, node.customUpgrades().count(ID) / Math.max(1L, node.machineCount()));
        return Math.pow(mekanism.cloudworks.common.upgrade.CloudworksUpgradeEffects.settings()[1], -count / maximum) / time;
    }
}
