package mekanism.cloudworks.client.gui.line;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid.Entry;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import net.minecraft.util.text.TextComponentString;

/** Runtime contents are read-only; the enabled flag belongs to the editor draft. */
public final class GuiLineMachineWindow extends GuiLineWindow {
    private final ContainerCloudworks container;
    private final CloudworksLineEditorModel model;
    private final CloudworksLineDraft.Machine machine;
    private final List<Entry> inputs = new ArrayList<>(), outputs = new ArrayList<>(), templates = new ArrayList<>();
    private final boolean configuration;
    private final MekanismButton enable, disable;
    private final MekanismButton clear;
    private CloudworksGuiData.Graph seen;

    public GuiLineMachineWindow(IGuiWrapper gui, ContainerCloudworks container, CloudworksLineEditorModel model,
            CloudworksLineDraft.Machine machine) {
        super(gui, 35, 30, 230, (GuiLineNodeCacheWindow.hasTemplatePort(machine.type) ? 209 : 177)
                        + (thermal(machine.type) ? 54 : 0),
                "machine_details", type("machine"));
        this.container = container;
        this.model = model;
        this.machine = machine;
        configuration = GuiLineNodeCacheWindow.hasTemplatePort(machine.type);
        CloudworksInfuseRenderer.register();
        addChild(new GuiCloudQIOResourceGrid(gui, relativeX + 8, relativeY + 40, 5, 4, () -> inputs));
        addChild(new GuiCloudQIOResourceGrid(gui, relativeX + 115, relativeY + 40, 5, 4, () -> outputs));
        enable = addChild(new MekanismButton(gui, relativeX + 8, relativeY + 129, 99, 18,
                new TextComponentString(tr("enable_node")), () -> model.setMachineActive(machine.id, true), null));
        disable = addChild(new MekanismButton(gui, relativeX + 115, relativeY + 129, 107, 18,
                new TextComponentString(tr("disable_node")), () -> model.setMachineActive(machine.id, false), null));
        clear = addChild(new MekanismButton(gui, relativeX + 8, relativeY + 151, 214, 18,
                new TextComponentString(tr("clear_cache")), () ->
                        container.sendNodeAction(Operation.CLEAR_NODE_CACHE,
                                container.guiData().graph.id, machine.id), null));
        if (configuration)
            addChild(new GuiCloudQIOResourceGrid(gui, relativeX + 8, relativeY + 185, 1, 1, () -> templates));
        if (thermal(machine.type))
            addChild(new GuiLineThermalScreen(gui, relativeX + 8, relativeY + (configuration ? 207 : 175), container, machine.id));
        tick();
    }

    static boolean thermal(net.minecraft.item.ItemStack stack) {
        return mekanism.cloudworks.common.registry.CloudworksRegistries.thermal(
                mekanism.cloudworks.common.registry.CloudworksRegistries.identityKey(stack)) != null;
    }

    @Override
    public void tick() {
        super.tick();
        CloudworksGuiData.Graph graph = container.guiData().graph;
        if (graph != seen) {
            seen = graph;
            inputs.clear();
            outputs.clear();
            templates.clear();
            for (CloudworksGuiData.Node node : graph.nodes) {
                if (!node.id.equals(machine.id)) continue;
                collect(node.input, inputs);
                collect(node.output, outputs);
                collect(node.templates, templates);
                break;
            }
        }
        enable.active = !machine.active;
        disable.active = machine.active;
        clear.active = container.guiData().graph.enabled == false;
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
        drawScrollingString(new TextComponentString(tr("input_cache")), 8, 27,
                TextAlignment.LEFT, 0x404040, 99, 2, false);
        drawScrollingString(new TextComponentString(tr("output_cache")), 115, 27,
                TextAlignment.LEFT, 0x404040, 107, 2, false);
        drawScrollingString(new TextComponentString(machine.type.getDisplayName()), 8, 116,
                TextAlignment.LEFT, 0x404040, 214, 2, false);
        if (configuration)
            drawScrollingString(new TextComponentString(tr("template_cache")), 48, 190,
                    TextAlignment.LEFT, 0x404040, 174, 2, false);
    }
}
