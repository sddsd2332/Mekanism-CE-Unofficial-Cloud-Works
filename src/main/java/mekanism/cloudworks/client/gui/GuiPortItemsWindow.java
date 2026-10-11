package mekanism.cloudworks.client.gui;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.gui.warning.WarningTracker.WarningType;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.tile.TileEntityCloudInput;
import mekanism.cloudworks.common.inventory.container.ContainerCloudInputPort;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Movable/pinnable 11×11 item buffer window of the upload port. The blocked
 * buffer position stripes through the standard slot warning texture.
 */
@SideOnly(Side.CLIENT)
public class GuiPortItemsWindow extends GuiWindow {

    private static final int COLUMNS = 11;
    private static final int ROWS = 11;

    public GuiPortItemsWindow(IGuiWrapper gui, int x, int y, ContainerCloudInputPort container) {
        super(gui, x, y, COLUMNS * 18 + 16, ROWS * 18 + 28, new SelectedWindowData(CloudworksWindowTypes.PORT_ITEMS));
        interactionStrategy = InteractionStrategy.ALL;
        TileEntityCloudInput tile = ((GuiCloudInputPort) gui).getTileEntity();
        for (int slot = 0; slot < COLUMNS * ROWS; slot++) {
            int bufferIndex = slot;
            GuiVirtualSlot virtual = new GuiVirtualSlot(this, SlotType.NORMAL, gui,
                    relativeX + 8 + slot % COLUMNS * 18, relativeY + 20 + slot / COLUMNS * 18, container.getPortSlot(slot));
            virtual.warning(WarningType.NO_SPACE_IN_OUTPUT,
                    () -> tile.getBlockedBufferIndex() == bufferIndex);
            addChild(virtual);
        }
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawTitleText(new TextComponentTranslation("gui.cloudworks.port_items"), 5);
    }
}

