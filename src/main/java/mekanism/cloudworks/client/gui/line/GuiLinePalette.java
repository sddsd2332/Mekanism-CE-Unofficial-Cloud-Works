package mekanism.cloudworks.client.gui.line;

import java.util.ArrayList;
import java.util.List;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.opengl.GL11;

/** Keeps window content usable below its natural height. */
public final class GuiLinePalette extends GuiElement {
    public static final int CONTENT_HEIGHT = 192;
    private final List<GuiElement> content = new ArrayList<>();
    private final int contentHeight;
    private int scroll;

    public GuiLinePalette(IGuiWrapper gui, int x, int y, int height) {
        this(gui, x, y, 108, height, CONTENT_HEIGHT);
    }

    public GuiLinePalette(IGuiWrapper gui, int x, int y, int width, int height, int contentHeight) {
        super(gui, x, y, width, Math.max(1, height));
        this.contentHeight = contentHeight;
    }

    public <T extends GuiElement> T control(T element) {
        content.add(element);
        return addChild(element);
    }

    public void finish() {
        // GuiElement renders child foregrounds after the parent's foreground.
        // This last child closes the clip after all palette content has rendered.
        addChild(new GuiElement(gui(), relativeX, relativeY, 0, 0) {
            @Override
            public void renderForeground(int mouseX, int mouseY) {
                GL11.glPopAttrib();
            }
        });
    }

    @Override
    public void renderBackgroundOverlay(int mouseX, int mouseY) {
        super.renderBackgroundOverlay(mouseX, mouseY);
        int factor = new ScaledResolution(minecraft).getScaleFactor();
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(getX() * factor, minecraft.displayHeight - (getY() + height) * factor, width * factor, height * factor);
    }

    public boolean canScroll(int direction) {
        return direction < 0 ? scroll > 0 : scroll < Math.max(0, contentHeight - height);
    }

    public int scrollOffset() {
        return scroll;
    }

    public void viewportHeight(int height) {
        this.height = Math.max(1, height);
        setScroll(scroll);
    }

    public void resetScroll() {
        int movement = scroll;
        scroll = 0;
        content.forEach(element -> element.move(0, movement));
    }

    public void scroll(int direction) {
        setScroll(scroll + direction * 18);
    }

    private void setScroll(int position) {
        int next = Math.max(0, Math.min(Math.max(0, contentHeight - height), position));
        int movement = scroll - next;
        scroll = next;
        content.forEach(element -> element.move(0, movement));
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible && mouseX >= getX() && mouseX < getX() + width
                && mouseY >= getY() && mouseY < getY() + height;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return active && visible && isMouseOverCheckWindows(mouseX, mouseY)
                && super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!active || !visible || !isMouseOverCheckWindows(mouseX, mouseY)) return false;
        if (!GuiScreen.isShiftKeyDown() && super.mouseScrolled(mouseX, mouseY, delta)) return true;
        int direction = delta > 0 ? -1 : 1;
        if (!canScroll(direction)) return false;
        scroll(direction);
        return true;
    }

    @Override
    public boolean isMouseOverTooltip(double mouseX, double mouseY) {
        return visible && isMouseOverCheckWindows(mouseX, mouseY);
    }

    @Override
    public void renderToolTip(int mouseX, int mouseY) {
        if (isMouseOverTooltip(mouseX, mouseY)) super.renderToolTip(mouseX, mouseY);
    }
}
