package mekanism.cloudworks.client.gui.line;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import net.minecraft.util.text.TextComponentString;

/** Independent node palette; its slot grids retain the editor's existing placement rules. */
public final class GuiLineNodePaletteWindow extends GuiLineWindow {

    public static final int CONTENT_X = 8;
    public static final int TYPE_Y = 20;
    public static final int SEARCH_Y = 39;
    public static final int GRID_Y = 58;
    public static final int GRID_COLUMNS = 5;
    public static final int GRID_ROWS = 6;

    public GuiLineNodePaletteWindow(IGuiWrapper gui, int x) {
        super(gui, Math.max(26, x), 24, 124, Math.max(120, Math.min(257, gui.getHeight() - 32)),
                "palette_nodes", CloudworksWindowTypes.LINE_PALETTE);
        addChild(new GuiElement(gui, relativeX + CONTENT_X, relativeY + TYPE_Y, 52, 18) {
            @Override
            public void renderForeground(int mouseX, int mouseY) {
                super.renderForeground(mouseX, mouseY);
                drawScaledScrollingString(new TextComponentString(tr("palette_current_type")), 0, 4,
                        TextAlignment.LEFT, titleTextColor(), width, 0, false, 0.75F, getTimeOpened());
            }
        });
    }

    public <T extends GuiElement> T control(T element) {
        return addChild(element);
    }
}
