package mekanism.cloudworks.client.gui.line;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.scroll.GuiTextScrollList;
import mekanism.cloudworks.client.gui.element.GuiCloudSlotScroll;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.registry.MachineKey;
import mekanism.cloudworks.common.service.CloudworksLineMachineUsage;
import mekanism.cloudworks.common.service.CloudworksLineMachineUsage.Node;
import mekanism.cloudworks.common.service.CloudworksLineMachineUsage.Type;
import mekanism.common.inventory.ISlotClickHandler.IScrollableSlot;
import mekanism.common.util.LangUtils;
import net.minecraft.util.text.TextComponentString;

public final class GuiLineMachineUsageWindow extends GuiLineWindow {
    private final Supplier<CloudworksLineDraft> configuration;
    private final LongSupplier configurationVersion;
    private final Consumer<UUID> focus;
    private final GuiTextScrollList locations;
    private CloudworksLineDraft snapshot;
    private long snapshotVersion = Long.MIN_VALUE;
    private List<Type> types = Collections.emptyList();
    private MachineKey selectedType;
    private UUID lastNode;
    private long lastClick;

    public GuiLineMachineUsageWindow(IGuiWrapper gui, Supplier<CloudworksLineDraft> configuration,
            Consumer<UUID> focus) {
        this(gui, configuration, () -> 0L, focus);
    }

    public GuiLineMachineUsageWindow(IGuiWrapper gui, Supplier<CloudworksLineDraft> configuration,
            LongSupplier configurationVersion, Consumer<UUID> focus) {
        super(gui, (gui.getWidth() - 227) / 2, 30, 227, 151, "machine_usage", CloudworksWindowTypes.LINE_MACHINE_USAGE);
        this.configuration = configuration;
        this.configurationVersion = configurationVersion;
        this.focus = focus;
        addChild(new GuiCloudSlotScroll(gui, relativeX + 8, relativeY + 26, 5, 6,
                this::slots, this::selectType, this::selectedIndex, this::typeTooltip));
        locations = addChild(new GuiTextScrollList(gui, relativeX + 115, relativeY + 26, 104, 108) {
            @Override protected void setSelected(int index) {
                super.setSelected(index);
                selectNode(index);
            }
        });
        refresh();
    }

    private void refresh() {
        CloudworksLineDraft current = configuration.get();
        long version = configurationVersion.getAsLong();
        if (snapshot == current && snapshotVersion == version) return;
        snapshot = current;
        snapshotVersion = version;
        types = CloudworksLineMachineUsage.collect(current);
        if (selectedIndex() < 0) {
            selectedType = null;
            locations.clearSelection();
            lastNode = null;
        }
        updateLocations();
    }

    private int selectedIndex() {
        for (int index = 0; index < types.size(); index++)
            if (types.get(index).key.equals(selectedType)) return index;
        return -1;
    }

    private List<IScrollableSlot> slots() {
        List<IScrollableSlot> slots = new ArrayList<>();
        for (Type type : types) slots.add(GuiCloudSlotScroll.slot(type.stack(), type.count));
        return slots;
    }

    private List<String> typeTooltip(int index) {
        if (index < 0 || index >= types.size()) return Collections.emptyList();
        Type type = types.get(index);
        List<String> lines = new ArrayList<>();
        lines.add(LangUtils.localizeWithFormat("cloudworks.editor.machine_usage_count", type.count));
        lines.add(LangUtils.localizeWithFormat("cloudworks.editor.machine_usage_nodes", type.nodes.size()));
        return lines;
    }

    private void selectType(int index) {
        if (index < 0 || index >= types.size() || index == selectedIndex()) return;
        selectedType = types.get(index).key;
        lastNode = null;
        locations.clearSelection();
        updateLocations();
    }

    private List<Node> nodes() {
        int selected = selectedIndex();
        return selected < 0 ? Collections.emptyList() : types.get(selected).nodes;
    }

    private void updateLocations() {
        List<String> labels = new ArrayList<>();
        for (Node node : nodes())
            labels.add(node.x + ", " + node.y + "  x" + node.count);
        locations.setText(labels);
    }

    private void selectNode(int index) {
        List<Node> nodes = nodes();
        if (index < 0 || index >= nodes.size()) return;
        UUID id = nodes.get(index).id;
        long now = System.currentTimeMillis();
        if (id.equals(lastNode) && now - lastClick < 350) {
            focus.accept(id);
            lastNode = null;
        } else lastNode = id;
        lastClick = now;
    }

    @Override public void tick() { super.tick(); refresh(); }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawScrollingString(new TextComponentString(tr("machine_usage_types")), 8, 16,
                TextAlignment.LEFT, 0x404040, 90, 0, false);
        drawScrollingString(new TextComponentString(tr("machine_usage_locations")), 115, 16,
                TextAlignment.LEFT, 0x404040, 104, 0, false);
        long total = 0;
        for (Type type : types) total = Math.addExact(total, type.count);
        drawScrollingString(new TextComponentString(LangUtils.localizeWithFormat(
                "cloudworks.editor.machine_usage_total", types.size(), total)), 8, 138,
                TextAlignment.LEFT, 0x404040, 211, 0, false);
    }
}
