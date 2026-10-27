package mekanism.cloudworks.client.gui.element;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.common.util.LangUtils;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

/**
 * Shared selection details for the cloud upgrade window and read-only node contents.
 * The whole content list — name, amount and every description line — scrolls
 * automatically up and down like TOPTankListElement (three lines in the cloud
 * window where the uninstall button covers the lower panel strip, four in the
 * machine node window). Long lines scroll horizontally as an edge-pausing
 * marquee instead of shrinking to fit.
 */
public final class GuiCloudUpgradeDetails extends GuiInnerScreen {
    public static final int WIDTH = 59;
    public static final int HEIGHT = 50;
    public static final int CLOUD_LINES = 3;
    public static final int MACHINE_LINES = 4;

    private static final int PADDING = 2, CLOUD_BUTTON_STRIP = 16;
    private static final float TEXT_SCALE = 0.6F;

    private final int visibleLines;
    private final Supplier<CloudUpgradeEntry> selection;
    private final Function<CloudUpgradeEntry, ITextComponent> amount;
    private final WrappedTextRenderer noSelection =
            new WrappedTextRenderer(this, LangUtils.localize("gui.upgrades.noSelection"));
    private String selectedId;
    private long selectedAt;

    public GuiCloudUpgradeDetails(IGuiWrapper gui, int x, int y, int visibleLines,
            Supplier<CloudUpgradeEntry> selection, Function<CloudUpgradeEntry, ITextComponent> amount) {
        super(gui, x, y, WIDTH, HEIGHT);
        if (visibleLines != CLOUD_LINES && visibleLines != MACHINE_LINES)
            throw new IllegalArgumentException("Upgrade details require three or four visible rows");
        this.visibleLines = visibleLines;
        this.selection = selection;
        this.amount = amount;
    }

    static int rowHeight(int visibleLines) {
        return (HEIGHT - 2 * PADDING - (visibleLines == CLOUD_LINES ? CLOUD_BUTTON_STRIP : 0)) / visibleLines;
    }

    static float scroll(int lines, int visibleLines, int rowHeight, long elapsedMillis) {
        return IGuiWrapper.marqueeOffset(lines * (double) rowHeight,
                visibleLines * (double) rowHeight, elapsedMillis);
    }

    static List<ITextComponent> content(CloudUpgradeEntry entry, ITextComponent amount) {
        List<ITextComponent> lines = new ArrayList<>(entry.description.size() + 2);
        lines.add(new TextComponentString(entry.name));
        lines.add(amount);
        for (String description : entry.description) lines.add(new TextComponentString(description));
        return lines;
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        CloudUpgradeEntry entry = selection.get();
        if (entry == null) {
            selectedId = null;
            noSelection.renderWithScale(relativeX + 2, relativeY + 2, screenTextColor(), width - 4, 0.8F);
            return;
        }
        if (!Objects.equals(selectedId, entry.wireId)) {
            selectedId = entry.wireId;
            selectedAt = GuiElement.getMillis();
        }
        List<ITextComponent> lines = content(entry, amount.apply(entry));
        long elapsed = Math.max(0, GuiElement.getMillis() - selectedAt);
        int rowHeight = rowHeight(visibleLines);
        float offset = scroll(lines.size(), visibleLines, rowHeight, elapsed);
        float minX = relativeX + PADDING, maxX = relativeX + width - PADDING;
        float minY = relativeY + PADDING, maxY = minY + visibleLines * rowHeight;
        // TOP also clips one complete moving list. Both scroll axes and glyph
        // rendering remain native; only the nested LWJGL clipping is adapted here.
        CloudworksGuiScissor.clipped(gui(), minX, minY, maxX, maxY, () -> {
            for (int row = 0; row < lines.size(); row++) {
                float y = minY + row * rowHeight - offset;
                if (y + rowHeight <= minY || y >= maxY) continue;
                ITextComponent line = lines.get(row);
                float textWidth = getFont().getStringWidth(line.getFormattedText()) * TEXT_SCALE;
                float horizontal = IGuiWrapper.marqueeOffset(textWidth, maxX - minX, elapsed);
                float textY = (2 * y + rowHeight - getFont().FONT_HEIGHT) / 2F;
                IGuiWrapper.drawTextWithScale(getFont(), line, minX - horizontal, textY,
                        screenTextColor(), false, TEXT_SCALE);
            }
        });
    }
}
