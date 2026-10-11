package mekanism.cloudworks.client.gui;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.inventory.container.ContainerCloudOutputPort;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Movable/pinnable 11×11 item buffer window of the download port. No warning
 * state on this side — the pause protocol belongs to the upload port.
 */
@SideOnly(Side.CLIENT)
public class GuiPortOutItemsWindow extends GuiWindow {

    private static final int COLUMNS = 11;
    private static final int ROWS = 11;

    public GuiPortOutItemsWindow(IGuiWrapper gui, int x, int y, ContainerCloudOutputPort container) {
        super(gui, x, y, COLUMNS * 18 + 16, ROWS * 18 + 28, new SelectedWindowData(CloudworksWindowTypes.PORT_OUT_ITEMS));
        interactionStrategy = InteractionStrategy.ALL;
        for (int slot = 0; slot < COLUMNS * ROWS; slot++) {
            addChild(new GuiVirtualSlot(this, SlotType.NORMAL, gui,
                    relativeX + 8 + slot % COLUMNS * 18, relativeY + 20 + slot / COLUMNS * 18, container.getPortSlot(slot)));
        }
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawTitleText(new TextComponentTranslation("gui.cloudworks.port_out_items"), 5);
    }
}
