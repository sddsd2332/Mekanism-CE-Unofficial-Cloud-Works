package mekanism.cloudworks.client.gui.line;

import java.util.function.BooleanSupplier;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiInsetElement;
import mekanism.client.render.MekanismRenderer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;

/** Same inset side-tab shape and spacing as GuiSideConfiguration's type tabs. */
public final class GuiLineResourceTypeTab extends GuiInsetElement<Void> {
    private final String label;
    private final int color;
    private final Runnable select;
    private BooleanSupplier selected = () -> false;

    public GuiLineResourceTypeTab(IGuiWrapper gui, int x, int y, String texture, String label, int color, Runnable select) {
        super(new ResourceLocation("mekanism", "gui/" + texture), gui, null, x, y, 26, 18, true);
        this.label = label;
        this.color = color;
        this.select = select;
    }

    public GuiLineResourceTypeTab selectedWhen(BooleanSupplier selected) {
        this.selected = selected;
        return this;
    }

    @Override
    protected void drawBackgroundOverlay() {
        super.drawBackgroundOverlay();
        if (selected.getAsBoolean()) {
            GuiUtils.fill(relativeX + 1, relativeY + 3, relativeX + 3, relativeY + height - 3, color);
            MekanismRenderer.resetColor();
        }
    }

    @Override
    protected void colorTab() {
        MekanismRenderer.color(color);
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        if (button == 0) select.run();
    }

    @Override
    public void renderToolTip(int mouseX, int mouseY) {
        super.renderToolTip(mouseX, mouseY);
        displayTooltip(new TextComponentString(GuiLineWindow.tr(label)), mouseX, mouseY);
    }
}
