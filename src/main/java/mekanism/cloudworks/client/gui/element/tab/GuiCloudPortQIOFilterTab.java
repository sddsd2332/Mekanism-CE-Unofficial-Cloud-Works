package mekanism.cloudworks.client.gui.element.tab;

import java.util.function.Supplier;
import mekanism.client.SpecialColors;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.tab.window.GuiWindowCreatorTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.cloudworks.client.gui.GuiCloudPortQIOFilterWindow;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.tile.prefab.TileEntityCloudworksPort;
import mekanism.common.inventory.container.MekanismTileContainer;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.util.LangUtils;
import mekanism.common.util.MekanismUtils;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

public final class GuiCloudPortQIOFilterTab
        extends GuiWindowCreatorTab<TileEntityCloudworksPort, GuiCloudPortQIOFilterTab> {
    private final MekanismTileContainer<?> container;

    public GuiCloudPortQIOFilterTab(IGuiWrapper gui, TileEntityCloudworksPort tile,
            MekanismTileContainer<?> container, Supplier<GuiCloudPortQIOFilterTab> self) {
        super(MekanismUtils.getResource(MekanismUtils.ResourceType.GUI, "button/fuzzy.png"),
                gui, tile, -26, 90, 26, 18, true, self);
        this.container = container;
        visible = tile.hasQIOUpgrade();
    }

    @Override protected Integer getTabColor() { return SpecialColors.TAB_CONFIGURATION.argb(); }
    @Override protected ITextComponent getTooltipText() {
        return new TextComponentString(LangUtils.localize("cloudworks.port.qio_filters"));
    }
    @Override protected GuiWindow createWindow(SelectedWindowData data) {
        return new GuiCloudPortQIOFilterWindow(gui(), dataSource, container, data);
    }
    @Override protected SelectedWindowData getNextWindowData() {
        return new SelectedWindowData(CloudworksWindowTypes.PORT_QIO_FILTERS);
    }
    @Override public void openPinnedWindows() {
        if (dataSource.hasQIOUpgrade()) super.openPinnedWindows();
    }
    @Override public void tick() {
        super.tick();
        visible = dataSource.hasQIOUpgrade();
    }
}
