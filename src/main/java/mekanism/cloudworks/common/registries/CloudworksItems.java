package mekanism.cloudworks.common.registries;

import mekanism.cloudworks.common.Cloudworks;
import mekanism.cloudworks.common.item.*;
import mekanism.cloudworks.common.item.prefab.ItemCloudworksCard;
import mekanism.cloudworks.common.upgrade.CloudworksPortUpgrades;
import mekanism.cloudworks.api.environment.EnvironmentAxisRegistry;
import mekanism.cloudworks.api.environment.DimensionAxes;
import mekanism.cloudworks.api.environment.BiomeAxes;
import mekanism.cloudworks.api.environment.WeatherAxes;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.GameRegistry.ObjectHolder;
import net.minecraftforge.registries.IForgeRegistry;
import mekanism.cloudworks.common.card.CloudworksCardEffects;

@ObjectHolder(Cloudworks.MODID)
public class CloudworksItems {
    public static final Item PortableTerminal = new ItemCloudworksTerminal();
    public static final Item TeamTerminal = new ItemCloudworksTeamTerminal();
    public static final Item ActivationCard = new ItemCloudActivationCard();
    public static final Item OfflineProcessingCard = new ItemOfflineProcessingCard();
    public static final Item WorkRateUpgradeCard = new ItemWorkRateUpgradeCard();
    public static final Item OutputMultiplierCard = new ItemOutputMultiplierCard();
    public static final Item LineExpansionCard = new ItemLineExpansionCard();
    public static final Item NodeExpansionCard = new ItemNodeExpansionCard();
    public static final Item HeightSimulationCard = new ItemHeightSimulationCard();
    public static final Item LavaHeatSimulationCard = new ItemLavaHeatSimulationCard();
    public static final Item BiomeCard = new ItemBiomeCard();
    public static final Item DimensionCard = new ItemDimensionCard();
    public static final Item StructureCard = new ItemStructureCard();
    public static final Item WeatherCard = new ItemWeatherCard();
    public static final Item WarehouseCapacityCard = new ItemWarehouseCapacityCard();
    public static final Item LineCapacityCard = new ItemLineCapacityCard();
    public static final Item InfiniteResourceCard = new ItemInfiniteResourceCard();
    public static final Item QIODownloadUpgrade = new ItemCloudworksQIOUpgrade(CloudworksPortUpgrades.QIO_DOWNLOAD);
    public static final Item QIOUploadUpgrade = new ItemCloudworksQIOUpgrade(CloudworksPortUpgrades.QIO_UPLOAD);
    /** Line-level upgrade media (axis-priority plan batch 1): plain identity items. */
    public static final Item SpeedModule = new Item();
    public static final Item EnergyModule = new Item();

    public static void registerItems(IForgeRegistry<Item> registry) {
        registry.register(init(PortableTerminal, "portable_cloudworks_terminal"));
        registry.register(init(TeamTerminal, "team_cloudworks_terminal"));
        registry.register(init(ActivationCard, "cloud_activation_card"));
        registerEffectCard(registry, OfflineProcessingCard, "cloud_offline_processing_card");
        registerEffectCard(registry, WorkRateUpgradeCard, "cloud_work_rate_upgrade_card");
        registerEffectCard(registry, OutputMultiplierCard, "cloud_output_multiplier_card");
        registerEffectCard(registry, LineExpansionCard, "cloud_line_expansion_card");
        registerEffectCard(registry, NodeExpansionCard, "cloud_node_expansion_card");
        registry.register(init(BiomeCard, "cloud_biome_card"));
        registry.register(init(DimensionCard, "cloud_dimension_card"));
        registry.register(init(StructureCard, "cloud_structure_card"));
        registry.register(init(WeatherCard, "cloud_weather_card"));
        registerEffectCard(registry, WarehouseCapacityCard, "cloud_warehouse_capacity_card");
        registry.register(init(HeightSimulationCard, "cloud_height_simulation_card"));
        registry.register(init(LavaHeatSimulationCard, "cloud_lava_heat_simulation_card"));
        registry.register(init(LineCapacityCard, "cloud_line_capacity_card"));
        registry.register(init(InfiniteResourceCard, "cloud_infinite_resource_card"));
        registry.register(init(QIODownloadUpgrade, "qio_download_upgrade"));
        registry.register(init(QIOUploadUpgrade, "qio_upload_upgrade"));
        registry.register(init(SpeedModule, "speed_module"));
        registry.register(init(EnergyModule, "energy_module"));
    }

    public static Item init(Item item, String name) {
        return item.setCreativeTab(Cloudworks.tabCloudworks).setTranslationKey(name)
                .setRegistryName(new ResourceLocation(Cloudworks.MODID, name));
    }

    private static void registerEffectCard(IForgeRegistry<Item> registry, Item card, String name) {
        if (!(card instanceof ItemCloudworksCard)) throw new IllegalArgumentException("Effect card must use the shared CloudworksCard implementation");
        registry.register(init(card, name));
    }
}
