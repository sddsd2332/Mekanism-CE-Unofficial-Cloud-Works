package mekanism.cloudworks.client.gui.line;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.gui.IGuiWrapper;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid.Entry;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import net.minecraft.util.text.TextComponentString;

/** Machine input/output and channel buffers are view-only in observer mode. */
public final class GuiLineNodeCacheWindow extends GuiLineWindow {
    private final ContainerCloudworks container;
    private final UUID line, node;
    private final String name;
    private final boolean machine;
    private final boolean configuration;
    private final List<Entry> inputs = new ArrayList<>(), outputs = new ArrayList<>(), templates = new ArrayList<>();
    private CloudworksGuiData.Graph seen;

    public GuiLineNodeCacheWindow(IGuiWrapper gui, ContainerCloudworks container, UUID line,
            UUID node, String name, boolean machine) {
        this(gui, container, line, node, name, machine, false);
    }

    public GuiLineNodeCacheWindow(IGuiWrapper gui, ContainerCloudworks container, UUID line,
            UUID node, String name, boolean machine, boolean configuration) {
        super(gui, 35, 30, machine ? 230 : 122, (configuration ? 165 : 133)
                        + (hasThermal(container, node) ? 54 : 0), "node_cache", type(machine ? "machine" : "channel"));
        this.container = container;
        this.line = line;
        this.node = node;
        this.name = name;
        this.machine = machine;
        this.configuration = configuration;
        CloudworksInfuseRenderer.register();
        addChild(new GuiCloudQIOResourceGrid(gui, relativeX + 8, relativeY + 40, 5, 4, () -> inputs));
        if (machine)
            addChild(new GuiCloudQIOResourceGrid(gui, relativeX + 115, relativeY + 40, 5, 4, () -> outputs));
        if (configuration)
            addChild(new GuiCloudQIOResourceGrid(gui, relativeX + 8, relativeY + 137, 1, 1, () -> templates));
        if (hasThermal(container, node))
            addChild(new GuiLineThermalScreen(gui, relativeX + 8, relativeY + (configuration ? 163 : 131), container, node));
        tick();
    }

    private static boolean hasThermal(ContainerCloudworks container, UUID id) {
        for (CloudworksGuiData.Node node : container.guiData().graph.nodes)
            if (id.equals(node.id)) return !node.icon.isEmpty() && GuiLineMachineWindow.thermal(node.icon);
        return false;
    }

    public static boolean hasTemplatePort(net.minecraft.item.ItemStack stack) {
        mekanism.cloudworks.common.slot.MachineSlotLayout layout =
                mekanism.cloudworks.common.registry.CloudworksRegistries.slots(stack);
        return layout != null && layout.slots().stream()
                .anyMatch(slot -> slot.role() == mekanism.cloudworks.common.slot.MachineSlotLayout.PortRole.CONFIGURATION);
    }

    @Override
    public void tick() {
        super.tick();
        CloudworksGuiData.Graph graph = container.guiData().graph;
        if (graph == seen) return;
        seen = graph;
        inputs.clear();
        outputs.clear();
        templates.clear();
        if (!line.equals(graph.id)) return;
        for (CloudworksGuiData.Node snapshot : graph.nodes) {
            if (!node.equals(snapshot.id)) continue;
            collect(snapshot.input, inputs);
            if (machine) collect(snapshot.output, outputs);
            collect(snapshot.templates, templates);
            break;
        }
    }

    private static void collect(List<CloudworksGuiData.ResourceSummary> resources, List<Entry> target) {
        Map<QIOResourceDescriptor, Long> totals = new LinkedHashMap<>();
        for (CloudworksGuiData.ResourceSummary resource : resources)
            if (resource.amount > 0) totals.merge(resource.descriptor, resource.amount, Math::addExact);
        totals.forEach((resource, amount) -> target.add(new Entry(resource, amount)));
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawScrollingString(new TextComponentString(tr(machine ? "input_cache" : "buffer_cache")), 8, 27,
                TextAlignment.LEFT, 0x404040, 99, 2, false);
        if (machine)
            drawScrollingString(new TextComponentString(tr("output_cache")), 115, 27,
                    TextAlignment.LEFT, 0x404040, 107, 2, false);
        drawScrollingString(new TextComponentString(name), 8, 116,
                TextAlignment.LEFT, 0x404040, machine ? 214 : 106, 2, false);
        if (configuration)
            drawScrollingString(new TextComponentString(tr("template_cache")), 48, 142,
                    TextAlignment.LEFT, 0x404040, 174, 2, false);
    }
}
