package mekanism.cloudworks.common.registries;

import mekanism.cloudworks.common.Cloudworks;
import mekanism.cloudworks.common.item.ItemCloudworksTerminal;
import mekanism.cloudworks.common.item.ItemCloudActivationCard;
import mekanism.cloudworks.common.item.ItemLineExpansionCard;
import mekanism.cloudworks.common.item.ItemNodeExpansionCard;
import mekanism.cloudworks.common.item.ItemHeightSimulationCard;
import mekanism.cloudworks.common.item.ItemLavaHeatSimulationCard;
import mekanism.cloudworks.common.item.ItemNetherEnvironmentCard;
import mekanism.cloudworks.common.item.ItemOfflineProcessingCard;
import mekanism.cloudworks.common.item.ItemCloudworksQIOUpgrade;
import mekanism.cloudworks.common.upgrade.CloudworksPortUpgrades;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.GameRegistry.ObjectHolder;
import net.minecraftforge.registries.IForgeRegistry;

@ObjectHolder(Cloudworks.MODID)
public class CloudworksItems {
    public static final Item PortableTerminal = new ItemCloudworksTerminal();
    public static final Item ActivationCard = new ItemCloudActivationCard();
    public static final Item OfflineProcessingCard = new ItemOfflineProcessingCard();
    public static final Item WorkRateUpgradeCard = new mekanism.cloudworks.common.item.ItemWorkRateUpgradeCard();
    public static final Item LineExpansionCard = new ItemLineExpansionCard();
    public static final Item NodeExpansionCard = new ItemNodeExpansionCard();
    public static final Item HeightSimulationCard = new ItemHeightSimulationCard();
    public static final Item LavaHeatSimulationCard = new ItemLavaHeatSimulationCard();
    public static final Item NetherEnvironmentCard = new ItemNetherEnvironmentCard();
    public static final Item QIODownloadUpgrade = new ItemCloudworksQIOUpgrade(CloudworksPortUpgrades.QIO_DOWNLOAD);
    public static final Item QIOUploadUpgrade = new ItemCloudworksQIOUpgrade(CloudworksPortUpgrades.QIO_UPLOAD);

    public static void registerItems(IForgeRegistry<Item> registry) {
        registry.register(init(PortableTerminal, "portable_cloudworks_terminal"));
        registry.register(init(ActivationCard, "cloud_activation_card"));
        registry.register(init(OfflineProcessingCard, "cloud_offline_processing_card"));
        registry.register(init(WorkRateUpgradeCard, "cloud_work_rate_upgrade_card"));
        registry.register(init(LineExpansionCard, "cloud_line_expansion_card"));
        registry.register(init(NodeExpansionCard, "cloud_node_expansion_card"));
        registry.register(init(HeightSimulationCard, "cloud_height_simulation_card"));
        registry.register(init(LavaHeatSimulationCard, "cloud_lava_heat_simulation_card"));
        registry.register(init(NetherEnvironmentCard, "cloud_nether_environment_card"));
        registry.register(init(QIODownloadUpgrade, "qio_download_upgrade"));
        registry.register(init(QIOUploadUpgrade, "qio_upload_upgrade"));
    }

    public static Item init(Item item, String name) {
        return item.setCreativeTab(Cloudworks.tabCloudworks).setTranslationKey(name)
                .setRegistryName(new ResourceLocation(Cloudworks.MODID, name));
    }
}
