package mekanism.cloudworks.common.content;

import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.util.ResourceLocation;
import javax.annotation.Nullable;

/**
 * Cloudworks movable/pinnable GUI windows (协议储存箱 port sub-UIs). Window types
 * register into the shared Mekanism window registry so position pinning and the
 * "slot exists only in its window" gating work unchanged.
 */
public final class CloudworksWindowTypes {
    /**
     * 121-slot item buffer window of the upload port.
     */
    public static final SelectedWindowData.WindowType PORT_ITEMS = SelectedWindowData.WindowType.register(
            new ResourceLocation("cloudworks", "port_items"), "cloudworks_port_items", true);
    /**
     * 36-tank hybrid buffer window of the upload port.
     */
    public static final SelectedWindowData.WindowType PORT_TANKS = SelectedWindowData.WindowType.register(
            new ResourceLocation("cloudworks", "port_tanks"), "cloudworks_port_tanks", true);

    public static final SelectedWindowData PORT_ITEMS_DATA = new SelectedWindowData(PORT_ITEMS);
    public static final SelectedWindowData PORT_TANKS_DATA = new SelectedWindowData(PORT_TANKS);

    /**
     * 121-slot item buffer window of the download port.
     */
    public static final SelectedWindowData.WindowType PORT_OUT_ITEMS = SelectedWindowData.WindowType.register(
            new ResourceLocation("cloudworks", "port_out_items"), "cloudworks_port_out_items", true);
    /**
     * 36-tank hybrid buffer window of the download port.
     */
    public static final SelectedWindowData.WindowType PORT_OUT_TANKS = SelectedWindowData.WindowType.register(
            new ResourceLocation("cloudworks", "port_out_tanks"), "cloudworks_port_out_tanks", true);

    public static final SelectedWindowData PORT_OUT_ITEMS_DATA = new SelectedWindowData(PORT_OUT_ITEMS);
    public static final SelectedWindowData PORT_OUT_TANKS_DATA = new SelectedWindowData(PORT_OUT_TANKS);
    public static final SelectedWindowData.WindowType PORT_QIO_FILTERS = SelectedWindowData.WindowType.register(
            new ResourceLocation("cloudworks", "port_qio_filters"), "cloudworks_port_qio_filters", true);

    /**
     * 云端总览·机器缓存层窗.
     */
    public static final SelectedWindowData.WindowType CLOUD_CACHE = SelectedWindowData.WindowType.register(
            new ResourceLocation("cloudworks", "cloud_cache"), "cloudworks_cloud_cache", true);
    /**
     * 云端总览·三仓查看窗.
     */
    public static final SelectedWindowData.WindowType CLOUD_WAREHOUSES = SelectedWindowData.WindowType.register(
            new ResourceLocation("cloudworks", "cloud_warehouses"), "cloudworks_cloud_warehouses", true);

    /**
     * 云端总览·能量层窗.
     */
    public static final SelectedWindowData.WindowType CLOUD_ENERGY = SelectedWindowData.WindowType.register(
            new ResourceLocation("cloudworks", "cloud_energy"), "cloudworks_cloud_energy", true);

    /**
     * 云端总览·回收站窗.
     */
    public static final SelectedWindowData.WindowType CLOUD_RECYCLE = SelectedWindowData.WindowType.register(
            new ResourceLocation("cloudworks", "cloud_recycle"), "cloudworks_cloud_recycle", true);

    /**
     * 云端总览·升级层窗.
     */
    public static final SelectedWindowData.WindowType CLOUD_UPGRADE = SelectedWindowData.WindowType.register(
            new ResourceLocation("cloudworks", "cloud_upgrade"), "cloudworks_cloud_upgrade", true);
    public static final SelectedWindowData.WindowType CLOUD_STATUS = SelectedWindowData.WindowType.register(
            new ResourceLocation("cloudworks", "cloud_status"), "cloudworks_cloud_status", true);


    public static final SelectedWindowData CLOUD_CACHE_DATA = new SelectedWindowData(CLOUD_CACHE);
    public static final SelectedWindowData CLOUD_ENERGY_DATA = new SelectedWindowData(CLOUD_ENERGY);
    public static final SelectedWindowData CLOUD_UPGRADE_DATA = new SelectedWindowData(CLOUD_UPGRADE);

    public static final SelectedWindowData.WindowType CLOUD_INVENTORY = SelectedWindowData.WindowType.register(
            new ResourceLocation("cloudworks", "cloud_inventory"), "cloudworks_cloud_inventory", true);
    public static final SelectedWindowData CLOUD_INVENTORY_DATA = new SelectedWindowData(CLOUD_INVENTORY);

    public static final SelectedWindowData.WindowType LINE_IDENTITY = lineWindow("identity");
    public static final SelectedWindowData.WindowType LINE_STATE = lineWindow("state");
    public static final SelectedWindowData.WindowType LINE_UPGRADES = lineWindow("upgrades");
    public static final SelectedWindowData.WindowType LINE_ENERGY = lineWindow("energy");
    public static final SelectedWindowData.WindowType LINE_CHANNEL = lineWindow("channel");
    public static final SelectedWindowData.WindowType LINE_MACHINE = lineWindow("machine");
    public static final SelectedWindowData.WindowType LINE_LINK = lineWindow("link");
    public static final SelectedWindowData.WindowType LINE_RESOURCES = lineWindow("resources");
    public static final SelectedWindowData.WindowType LINE_MACHINE_USAGE = lineWindow("machine_usage");
    public static final SelectedWindowData.WindowType LINE_BLUEPRINTS = lineWindow("blueprints");
    public static final SelectedWindowData.WindowType LINE_BLUEPRINT_NEEDS = lineWindow("blueprint_needs");

    private static SelectedWindowData.WindowType lineWindow(String name) {
        return SelectedWindowData.WindowType.register(new ResourceLocation("cloudworks", "line_" + name),
                "cloudworks_line_" + name, false);
    }

    public static boolean isTerminalInventoryWindow(@Nullable SelectedWindowData windowData) {
        return CLOUD_CACHE_DATA.equals(windowData) || CLOUD_ENERGY_DATA.equals(windowData)
                || CLOUD_UPGRADE_DATA.equals(windowData);
    }

    /**
     * 终端插槽窗（临时 GUI）: 机器上传/下载、能量物品上传、升级上传/下载 五槽
     * 与玩家背包窗共用同一窗口上下文，所有槽位点击在该上下文中有效.
     */
    public static final SelectedWindowData.WindowType CLOUD_TERMINAL = SelectedWindowData.WindowType.register(
            new ResourceLocation("cloudworks", "cloud_terminal"), "cloudworks_cloud_terminal", true);

    public static final SelectedWindowData CLOUD_TERMINAL_DATA = new SelectedWindowData(CLOUD_TERMINAL);

    private CloudworksWindowTypes() {
    }
}
