package mekanism.cloudworks.client.gui;

import java.util.ArrayList;
import java.util.List;
import mekanism.client.gui.IGuiWrapper;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid;
import mekanism.client.render.MekanismRenderer;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.common.util.LangUtils;
import mekanism.common.content.qio.QIOResourceEntry;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 三仓查看子窗（三列只读）: receiving / transfer / sending resource lists with
 * amounts; strictly view-only — no extraction of any kind.
 */
@SideOnly(Side.CLIENT)
public class GuiCloudWarehousesWindow extends GuiWindow {

    private static final int W = 334, H = 140;
    private final ContainerCloudworks container;
    private final GuiCloudQIOResourceGrid receiving, transfer, sending;

    public GuiCloudWarehousesWindow(IGuiWrapper gui, int x, int y, ContainerCloudworks container) {
        super(gui, x, y, W, H, new SelectedWindowData(CloudworksWindowTypes.CLOUD_WAREHOUSES));
        this.container = container;
        interactionStrategy = InteractionStrategy.ALL;
        receiving = addChild(new GuiCloudQIOResourceGrid(gui, relativeX + 8, relativeY + 26, 5, 6,
                () -> entries(container.guiData().warehouses.receiving)));
        transfer = addChild(new GuiCloudQIOResourceGrid(gui, relativeX + 115, relativeY + 26, 5, 6,
                () -> entries(container.guiData().warehouses.transfer)));
        sending = addChild(new GuiCloudQIOResourceGrid(gui, relativeX + 222, relativeY + 26, 5, 6,
                () -> entries(container.guiData().warehouses.sending)));
        container.sendAction(Operation.WAREHOUSES_VIEW, null, "1");
    }

    private String tr(String key) { return LangUtils.localize("gui.cloudworks." + key); }

    private List<GuiCloudQIOResourceGrid.Entry> entries(List<QIOResourceEntry> resources) {
        List<GuiCloudQIOResourceGrid.Entry> values = new ArrayList<>();
        for (QIOResourceEntry resource : resources) {
            values.add(new GuiCloudQIOResourceGrid.Entry(resource));
        }
        return values;
    }

    @Override
    public void close() {
        container.sendAction(Operation.WAREHOUSES_VIEW, null, "0");
        super.close();
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        MekanismRenderer.resetGuiRenderState();
        super.renderForeground(mouseX, mouseY);
        drawTitleText(new TextComponentTranslation("gui.cloudworks.cloud_warehouses"), 5);
        drawScrollingString(new TextComponentString(tr("wh_receiving")), 8, 16,
                TextAlignment.LEFT, 0xFF66D29E, 90, 3, false);
        drawScrollingString(new TextComponentString(tr("wh_transfer")), 115, 16,
                TextAlignment.LEFT, 0xFFE5C86B, 90, 3, false);
        drawScrollingString(new TextComponentString(tr("wh_sending")), 222, 16,
                TextAlignment.LEFT, 0xFF7FBFE8, 90, 3, false);
        MekanismRenderer.resetGuiRenderState();
    }
}
