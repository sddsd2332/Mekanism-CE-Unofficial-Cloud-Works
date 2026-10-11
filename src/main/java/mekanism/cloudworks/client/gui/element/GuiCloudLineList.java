package mekanism.cloudworks.client.gui.element;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.scroll.GuiScrollList;
import mekanism.client.gui.element.slot.GuiDynamicResourceSlot;
import mekanism.api.processing.MachineResourceStack;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import mekanism.common.util.LangUtils;

/**
 * QIO JobList-style production-line directory.
 */
public final class GuiCloudLineList extends GuiScrollList {

    private final Supplier<List<Entry>> entries;
    private final Supplier<UUID> selectedId;
    private final Consumer<Entry> selectHandler;
    private final Consumer<Entry> openHandler;
    private UUID lastClickedId;
    private long lastClickTime = Long.MIN_VALUE;
    private final List<GuiDynamicResourceSlot> markerRows = new ArrayList<>();

    public GuiCloudLineList(IGuiWrapper gui, int x, int y, int width, int height,
            Supplier<List<Entry>> entries, Supplier<UUID> selectedId,
            Consumer<Entry> selectHandler, Consumer<Entry> openHandler) {
        super(gui, x, y, width, height, 26, GuiInnerScreen.SCREEN, GuiInnerScreen.SCREEN_SIZE);
        this.entries = entries;
        this.selectedId = selectedId;
        this.selectHandler = selectHandler;
        this.openHandler = openHandler;
        for (int row = 0; row < (height - 2) / 26; row++) {
            final int visibleRow = row;
            GuiDynamicResourceSlot markers = addChild(new GuiDynamicResourceSlot(gui, relativeX + 3,
                    relativeY + 5 + row * 26, 3, 1, () -> resources(visibleRow)));
            markerRows.add(markers);
            markers.click((element, mouseX, mouseY) -> {
                setSelected(getCurrentSelection() + visibleRow);
                return true;
            });
        }
    }

    private List<MachineResourceStack> resources(int row) {
        List<Entry> list = entries.get();
        int index = getCurrentSelection() + row;
        List<MachineResourceStack> result = new ArrayList<>();
        if (index < 0 || index >= list.size()) return result;
        Entry entry = list.get(index);
        for (QIOResourceDescriptor marker : entry.markers)
            result.add(MachineResourceStack.resource("", marker, 1));
        if (result.isEmpty() && !entry.icon.isEmpty()) result.add(MachineResourceStack.item("", entry.icon, 1));
        return result;
    }

    @Override
    protected int getMaxElements() {
        return entries.get().size();
    }

    @Override
    public boolean hasSelection() {
        return selectedId.get() != null;
    }

    @Override
    protected void setSelected(int index) {
        List<Entry> list = entries.get();
        if (index < 0 || index >= list.size()) {
            return;
        }
        Entry entry = list.get(index);
        selectHandler.accept(entry);
        long now = System.currentTimeMillis();
        if (entry.id.equals(lastClickedId) && now - lastClickTime < 350) {
            openHandler.accept(entry);
            lastClickedId = null;
            lastClickTime = Long.MIN_VALUE;
        } else {
            lastClickedId = entry.id;
            lastClickTime = now;
        }
    }

    @Override
    public void clearSelection() {
        selectHandler.accept(null);
        lastClickedId = null;
        lastClickTime = Long.MIN_VALUE;
    }

    @Override
    protected void renderElements(int mouseX, int mouseY, float partialTicks) {
        List<Entry> list = entries.get();
        int first = getCurrentSelection();
        int count = Math.min(getFocusedElements(), Math.max(0, list.size() - first));
        for (int row = 0; row < markerRows.size(); row++) markerRows.get(row).visible = row < count;
        for (int row = 0; row < count; row++) {
            Entry entry = list.get(first + row);
            int rowY = relativeY + 1 + row * elementHeight;
            boolean hovered = mouseX >= getX() + 1 && mouseX < getX() + barXShift - 1
                    && mouseY >= getY() + 1 + row * elementHeight
                    && mouseY < getY() + 1 + (row + 1) * elementHeight;
            if (entry.id.equals(selectedId.get())) {
                GuiUtils.fill(relativeX + 1, rowY, relativeX + barXShift - 1,
                        rowY + elementHeight, 0x80608AA0);
            } else if (hovered) {
                GuiUtils.fill(relativeX + 1, rowY, relativeX + barXShift - 1,
                        rowY + elementHeight, 0x405F737D);
            }
        }
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        List<Entry> list = entries.get();
        if (list.isEmpty()) {
            drawScrollingString(new TextComponentString(LangUtils.localize("cloudworks.cloud.empty")), 4,
                    Math.max(4, (height - getFont().FONT_HEIGHT) / 2),
                    TextAlignment.LEFT, 0xFF5A6266, barXShift - 8, 3, false);
            return;
        }
        int first = getCurrentSelection();
        int count = Math.min(getFocusedElements(), Math.max(0, list.size() - first));
        for (int row = 0; row < count; row++) {
            Entry entry = list.get(first + row);
            int rowY = relativeY + 3 + row * elementHeight;
            int textX = relativeX + 61;
            int textWidth = Math.max(8, barXShift - 66);
            String name = getFont().trimStringToWidth(entry.name, Math.max(8, textWidth - 70));
            drawScrollingString(new TextComponentString(name), textX - relativeX, rowY - relativeY,
                    TextAlignment.LEFT, 0xFF40484C, textWidth - 70, 3, false);
            String state = LangUtils.localize(entry.enabled ? "cloudworks.cloud.state_on" : "cloudworks.cloud.state_off");
            drawScrollingString(new TextComponentString(state), barXShift - 70, rowY - relativeY,
                    TextAlignment.LEFT, entry.enabled ? 0xFF347A52 : 0xFF667177, 66, 3, false);
            String summary = entry.nodes + " " + LangUtils.localize("cloudworks.cloud.nodes") + "  "
                    + String.format(Locale.ROOT, "%.0f/%.0f J", entry.energy, entry.maxEnergy);
            if (entry.runWhileOwnerOffline)
                summary += "  " + LangUtils.localize("cloudworks.cloud.offline_enabled");
            drawScrollingString(new TextComponentString(summary), textX - relativeX, rowY - relativeY + 11,
                    TextAlignment.LEFT, 0xFF596368, textWidth, 3, false);
            GuiUtils.fill(relativeX + 2, rowY + elementHeight - 2,
                    relativeX + barXShift - 2, rowY + elementHeight - 1, 0xFFB5BEC2);
            if (entry.enabled) {
                GuiUtils.fill(relativeX + 2, rowY + elementHeight - 2,
                        relativeX + barXShift - 2, rowY + elementHeight - 1, 0xFF4C9A6C);
            }
        }
    }

    public static final class Entry {
        public final UUID id;
        public final String name;
        public final boolean enabled, runWhileOwnerOffline;
        public final int nodes;
        public final double energy;
        public final double maxEnergy;
        public final ItemStack icon;
        public final List<QIOResourceDescriptor> markers;

        public Entry(UUID id, String name, boolean enabled, int nodes,
                double energy, double maxEnergy, ItemStack icon) {
            this(id, name, enabled, nodes, energy, maxEnergy, icon, java.util.Collections.emptyList());
        }

        public Entry(UUID id, String name, boolean enabled, int nodes,
                double energy, double maxEnergy, ItemStack icon, List<QIOResourceDescriptor> markers) {
            this(id, name, enabled, nodes, energy, maxEnergy, icon, markers, false);
        }

        public Entry(UUID id, String name, boolean enabled, int nodes,
                double energy, double maxEnergy, ItemStack icon, List<QIOResourceDescriptor> markers,
                boolean runWhileOwnerOffline) {
            this.id = id;
            this.name = name;
            this.enabled = enabled;
            this.runWhileOwnerOffline = runWhileOwnerOffline;
            this.nodes = nodes;
            this.energy = energy;
            this.maxEnergy = maxEnergy;
            this.icon = icon;
            this.markers = new ArrayList<>(markers);
        }
    }
}
