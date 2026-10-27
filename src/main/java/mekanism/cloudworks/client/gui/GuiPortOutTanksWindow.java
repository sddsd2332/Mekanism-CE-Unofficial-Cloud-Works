package mekanism.cloudworks.client.gui;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.gauge.GaugeType;
import mekanism.client.gui.element.gauge.GuiHybridGauge;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.cloudworks.common.content.CloudworksHybridTank;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.tile.prefab.TileEntityCloudworksPort;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Collections;

/**
 * Movable/pinnable 6×6 hybrid-tank window of the download port. Each cell is a
 * slot-sized hybrid gauge (fluid OR gas view of one tank); no warning state on
 * this side — the pause protocol belongs to the upload port.
 */
@SideOnly(Side.CLIENT)
public class GuiPortOutTanksWindow extends GuiWindow {

    private static final int COLUMNS = 6;
    private static final int ROWS = 6;

    public GuiPortOutTanksWindow(IGuiWrapper gui, int x, int y, TileEntityCloudworksPort tile) {
        super(gui, x, y, COLUMNS * 18 + 16, ROWS * 18 + 28, new SelectedWindowData(CloudworksWindowTypes.PORT_OUT_TANKS));
        interactionStrategy = InteractionStrategy.ALL;
        for (int tank = 0; tank < COLUMNS * ROWS; tank++) {
            int tankIndex = tank;
            CloudworksHybridTank cell = tankIndex < tile.portTanks().size() ? tile.portTanks().get(tankIndex) : null;
            addChild(new GuiHybridGauge(
                    () -> cell == null ? null : cell.gasView(),
                    () -> cell == null ? Collections.emptyList() : Collections.singletonList(cell.gasView()),
                    () -> cell == null ? null : cell.fluidView(),
                    () -> cell == null ? Collections.emptyList() : Collections.singletonList(cell.fluidView()),
                    GaugeType.SlOT, gui, relativeX + 8 + tank % COLUMNS * 18, relativeY + 20 + tank / COLUMNS * 18));
        }
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawTitleText(new TextComponentTranslation("gui.cloudworks.port_out_tanks"), 5);
    }
}
