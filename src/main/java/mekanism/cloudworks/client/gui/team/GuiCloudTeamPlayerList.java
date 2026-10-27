package mekanism.cloudworks.client.gui.team;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.scroll.GuiScrollList;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

/** Avatar rows shared by the public team directory, members, requests and transfer chooser. */
public final class GuiCloudTeamPlayerList extends GuiScrollList {
    public static final int ROW_HEIGHT = 28;
    public static final int AUTO_COLOR = 0xFF72CE8C;
    public static final int APPROVAL_COLOR = 0xFFB8BEC2;
    public static final int REQUESTED_COLOR = 0xFF66D5DF;
    private final Supplier<List<Entry>> entries;
    private final Supplier<UUID> selection;
    private final Consumer<Entry> clicked;
    private final ITextComponent emptyText;

    public GuiCloudTeamPlayerList(IGuiWrapper gui, int x, int y, int width, int height,
            Supplier<List<Entry>> entries, Supplier<UUID> selection, Consumer<Entry> clicked,
            ITextComponent emptyText) {
        super(gui, x, y, width, height, ROW_HEIGHT, GuiInnerScreen.SCREEN, GuiInnerScreen.SCREEN_SIZE);
        this.entries = entries;
        this.selection = selection;
        this.clicked = clicked;
        this.emptyText = emptyText;
    }

    @Override protected int getMaxElements() { return entries.get().size(); }
    @Override public boolean hasSelection() { return selection.get() != null; }
    @Override public void clearSelection() { clicked.accept(null); }

    @Override protected void setSelected(int index) {
        List<Entry> rows = entries.get();
        if (index >= 0 && index < rows.size() && rows.get(index).enabled) clicked.accept(rows.get(index));
    }

    @Override protected void renderElements(int mouseX, int mouseY, float partialTicks) {
        List<Entry> rows = entries.get();
        int first = getCurrentSelection();
        int count = Math.min(getFocusedElements(), Math.max(0, rows.size() - first));
        for (int row = 0; row < count; row++) {
            Entry entry = rows.get(first + row);
            int y = relativeY + 1 + row * elementHeight;
            boolean hovered = entry.enabled && mouseX >= getX() + 1 && mouseX < getX() + barXShift - 1
                    && mouseY >= getY() + 1 + row * elementHeight
                    && mouseY < getY() + 1 + (row + 1) * elementHeight;
            int background = entry.id.equals(selection.get()) ? 0x805A95AE
                    : hovered ? 0x405F8797 : 0x18000000 | entry.color & 0x00FFFFFF;
            GuiUtils.fill(relativeX + 1, y, relativeX + barXShift - 1, y + elementHeight, background);
            GuiUtils.fill(relativeX + 1, y, relativeX + 3, y + elementHeight - 1, entry.color);
        }
    }

    @Override public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        List<Entry> rows = entries.get();
        if (rows.isEmpty()) {
            drawScaledScrollingString(emptyText, 6, Math.max(6, (height - 9) / 2), TextAlignment.LEFT,
                    0xFFB8BEC2, barXShift - 12, 0, false, 0.8F, 0);
            return;
        }
        int first = getCurrentSelection();
        int count = Math.min(getFocusedElements(), Math.max(0, rows.size() - first));
        for (int row = 0; row < count; row++) {
            Entry entry = rows.get(first + row);
            int y = 1 + row * elementHeight;
            CloudworksTeamGui.avatar(entry.avatar, relativeX + 6, relativeY + y + 5, 18);
            drawScaledScrollingString(new TextComponentString(entry.title), 30, y + 4, TextAlignment.LEFT,
                    entry.color, barXShift - 34, 0, false, 0.85F, 0);
            drawScaledScrollingString(new TextComponentString(entry.detail), 30, y + 16, TextAlignment.LEFT,
                    0xFFABB4BC, barXShift - 34, 0, false, 0.7F, 0);
        }
    }

    public static final class Entry {
        public final UUID id, avatar;
        public final String title, detail;
        public final int color;
        public final boolean enabled;

        public Entry(UUID id, UUID avatar, String title, String detail, int color, boolean enabled) {
            this.id = id;
            this.avatar = avatar;
            this.title = title;
            this.detail = detail;
            this.color = color;
            this.enabled = enabled;
        }
    }
}
