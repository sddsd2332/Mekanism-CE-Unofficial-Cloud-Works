package mekanism.cloudworks.common.upgrade;

import java.util.Collections;
import mekanism.api.EnumColor;
import mekanism.cloudworks.common.Cloudworks;
import mekanism.cloudworks.common.registries.CloudworksItems;
import mekanism.common.Upgrade;
import net.minecraft.item.ItemStack;

public final class CloudworksPortUpgrades {
    public static final Upgrade QIO_DOWNLOAD = Upgrade.builder(Cloudworks.MODID, "qio_download")
            .maxInstalled(1)
            .maxItemStackSize(1)
            .color(EnumColor.YELLOW)
            .stack(count -> new ItemStack(CloudworksItems.QIODownloadUpgrade, count))
            .info((upgrade, tile) -> Collections.emptyList())
            .register();
    public static final Upgrade QIO_UPLOAD = Upgrade.builder(Cloudworks.MODID, "qio_upload")
            .maxInstalled(1)
            .maxItemStackSize(1)
            .color(EnumColor.AQUA)
            .stack(count -> new ItemStack(CloudworksItems.QIOUploadUpgrade, count))
            .info((upgrade, tile) -> Collections.emptyList())
            .register();

    private CloudworksPortUpgrades() {
    }
}
