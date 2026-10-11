package mekanism.cloudworks.client;

import mekanism.cloudworks.common.Cloudworks;
import mekanism.cloudworks.common.registries.CloudworksBlocks;
import mekanism.cloudworks.common.registries.CloudworksItems;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Client model registration for the entry set. All block/item models live in
 * the cloudworks asset domain (copied from the reused QIO dashboard, hybrid
 * storage and dimensional stabilizer sources) — the standalone split keeps a
 * self-contained resource pack.
 */
@Mod.EventBusSubscriber(value = Side.CLIENT, modid = Cloudworks.MODID)
public final class CloudworksClientModels {
    private CloudworksClientModels() {
    }

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        model(CloudworksItems.PortableTerminal, "portable_cloudworks_terminal");
        model(CloudworksItems.ActivationCard, "cloud_activation_card");
        model(CloudworksItems.OfflineProcessingCard, "cloud_offline_processing_card");
        model(CloudworksItems.WorkRateUpgradeCard, "cloud_work_rate_upgrade_card");
        model(CloudworksItems.LineExpansionCard, "cloud_line_expansion_card");
        model(CloudworksItems.NodeExpansionCard, "cloud_node_expansion_card");
        model(CloudworksItems.HeightSimulationCard, "cloud_height_simulation_card");
        model(CloudworksItems.LavaHeatSimulationCard, "cloud_lava_heat_simulation_card");
        model(CloudworksItems.NetherEnvironmentCard, "cloud_nether_environment_card");
        model(CloudworksItems.QIODownloadUpgrade, "qio_download_upgrade");
        model(CloudworksItems.QIOUploadUpgrade, "qio_upload_upgrade");
        model(Item.getItemFromBlock(CloudworksBlocks.Dashboard), "cloudworks_dashboard");
        model(Item.getItemFromBlock(CloudworksBlocks.CloudInput), "cloud_input");
        model(Item.getItemFromBlock(CloudworksBlocks.CloudOutput), "cloud_output");
    }

    private static void model(Item item, String name) {
        ModelLoader.setCustomModelResourceLocation(item, 0,
                new ModelResourceLocation(Cloudworks.MODID + ":" + name, "inventory"));
    }
}

