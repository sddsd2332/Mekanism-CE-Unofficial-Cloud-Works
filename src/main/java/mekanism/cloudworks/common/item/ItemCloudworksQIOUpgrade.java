package mekanism.cloudworks.common.item;

import mekanism.cloudworks.common.tile.prefab.TileEntityCloudworksPort;
import mekanism.cloudworks.common.upgrade.CloudworksPortUpgrades;
import mekanism.common.Upgrade;
import mekanism.common.base.IUpgradeTile;
import mekanism.common.item.ItemUpgrade;
import net.minecraft.item.ItemStack;

public final class ItemCloudworksQIOUpgrade extends ItemUpgrade {
    public ItemCloudworksQIOUpgrade(Upgrade upgrade) {
        super(upgrade);
    }

    @Override
    public boolean canInstallUpgrade(ItemStack stack, IUpgradeTile tile) {
        return tile instanceof TileEntityCloudworksPort port
                && getUpgradeType(stack) == (port.isInputPort()
                        ? CloudworksPortUpgrades.QIO_DOWNLOAD : CloudworksPortUpgrades.QIO_UPLOAD);
    }
}
