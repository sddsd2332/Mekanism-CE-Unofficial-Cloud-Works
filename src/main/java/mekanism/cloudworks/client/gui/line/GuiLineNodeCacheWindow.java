package mekanism.cloudworks.client.gui.line;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.cloudworks.client.gui.element.CloudUpgradeEntry;
import mekanism.cloudworks.client.gui.element.GuiCloudMixedUpgradeScrollList;
import mekanism.cloudworks.client.gui.element.GuiCloudSupportedUpgrades;
import mekanism.cloudworks.client.gui.element.GuiCloudUpgradeDetails;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid.Entry;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeLimits;
import mekanism.common.Upgrade;
import mekanism.common.util.LangUtils;
import net.minecraft.util.text.TextComponentString;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/** Machine input/output and channel buffers are view-only in observer mode. */
public final class GuiLineNodeCacheWindow extends GuiLineWindow {
    private final ContainerCloudworks container;
    private final UUID line, node;
    private final String name;
    private final boolean machine;
    private final boolean configuration;
    private final boolean recipeSelection;
    private final boolean thermal;
    private final int naturalHeight;
    private final GuiLinePalette content;
    private final GuiCloudMixedUpgradeScrollList installedUpgrades;
    private final List<CloudUpgradeEntry> upgradeEntries = new ArrayList<>();
    private final Map<String, Long> upgradeCounts = new LinkedHashMap<>();
    private CloudUpgradeEntry selectedUpgrade;
    private CloudworksLineDraft.Machine machineSnapshot;
    private String recipeType = "";
    private final List<Entry> inputs = new ArrayList<>(), outputs = new ArrayList<>(), templates = new ArrayList<>();
    private CloudworksGuiData.Graph seen;

    public GuiLineNodeCacheWindow(IGuiWrapper gui, ContainerCloudworks container, UUID line,
            UUID node, String name, boolean machine) {
        this(gui, container, line, node, name, machine, false);
    }

    public GuiLineNodeCacheWindow(IGuiWrapper gui, ContainerCloudworks container, UUID line,
            UUID node, String name, boolean machine, boolean configuration) {
        this(gui, container, line, node, name, machine, configuration,
                (configuration ? 165 : 133) + (hasThermal(container, node) ? 54 : 0)
                        + (hasRecipeSelection(container, node) ? 22 : 0)
                        + (machine ? upgradeAreaHeight(gui, container) : 0));
    }

    private GuiLineNodeCacheWindow(IGuiWrapper gui, ContainerCloudworks container, UUID line,
            UUID node, String name, boolean machine, boolean configuration, int naturalHeight) {
        super(gui, 35, 30, machine ? 230 : 122, windowHeight(gui, naturalHeight),
                "node_cache", type(machine ? "machine" : "channel"));
        this.container = container;
        this.line = line;
        this.node = node;
        this.name = name;
        this.machine = machine;
        this.configuration = configuration;
        this.naturalHeight = naturalHeight;
        recipeSelection = hasRecipeSelection(container, node);
        thermal = hasThermal(container, node);
        content = addChild(new GuiLinePalette(gui, relativeX, relativeY + 18,
                width, height - 22, naturalHeight - 22));
        GuiElement labels = new GuiElement(gui, relativeX, relativeY + 18, width, naturalHeight - 22) {
            @Override
            public void renderForeground(int mouseX, int mouseY) {
                renderContentText(content.scrollOffset());
            }
        };
        labels.active = false;
        content.control(labels);
        CloudworksInfuseRenderer.register();
        content.control(new GuiCloudQIOResourceGrid(gui, relativeX + 8, relativeY + 40, 5, 4, () -> inputs));
        if (machine)
            content.control(new GuiCloudQIOResourceGrid(gui, relativeX + 115, relativeY + 40, 5, 4, () -> outputs));
        if (configuration)
            content.control(new GuiCloudQIOResourceGrid(gui, relativeX + 8, relativeY + 137, 5, 1, () -> templates));
        if (thermal)
            content.control(new GuiLineThermalScreen(gui, relativeX + 8,
                    relativeY + (configuration ? 163 : 131), container, node));
        if (machine) {
            int x = relativeX + (width - 167) / 2;
            int y = relativeY + naturalHeight - upgradeAreaHeight(gui, container);
            installedUpgrades = content.control(new GuiCloudMixedUpgradeScrollList(gui, x, y,
                    GuiCloudUpgradeDetails.HEIGHT, () -> upgradeEntries,
                    upgrade -> (int) Math.min(Integer.MAX_VALUE, upgradeCount(upgrade)),
                    upgrade -> selectedUpgrade = upgrade));
            content.control(new GuiCloudUpgradeDetails(gui, installedUpgrades.getRelativeRight(), y,
                    GuiCloudUpgradeDetails.MACHINE_LINES,
                    () -> selectedUpgrade, upgrade -> new TextComponentString(LangUtils.localizeWithFormat(
                            "cloudworks.editor.node_upgrade_status", LangUtils.localize("gui.upgrades.amount"),
                            upgradeCount(upgrade)))));
            content.control(new GuiCloudSupportedUpgrades(gui, x, y + GuiCloudUpgradeDetails.HEIGHT,
                    upgrade -> machineSnapshot != null && CloudworksUpgradeLimits.nodeCapacity(upgrade, machineSnapshot) > 0,
                    // Capability is a client fact: types come from the frozen local registry.
                    GuiCloudSupportedUpgrades::localCustomTypes,
                    upgrade -> machineSnapshot != null && CloudworksUpgradeLimits.draftCustomCapacity(
                            machineSnapshot, new ResourceLocation(upgrade.id)) > 0));
        } else installedUpgrades = null;
        content.finish();
        tick();
    }

    private static int upgradeAreaHeight(IGuiWrapper gui, ContainerCloudworks container) {
        return GuiCloudUpgradeDetails.HEIGHT
                + Math.max(18, 12 * GuiCloudSupportedUpgrades.calculateNeededRows(gui,
                        GuiCloudSupportedUpgrades.localCustomTypes())) + 8;
    }

    private static int windowHeight(IGuiWrapper gui, int naturalHeight) {
        return Math.min(naturalHeight, Math.max(80, gui.getHeight() - 8));
    }

    @Override
    public void resize(int prevLeft, int prevTop, int left, int top) {
        super.resize(prevLeft, prevTop, left, top);
        height = windowHeight(gui(), naturalHeight);
        content.viewportHeight(height - 22);
        move(0, Math.min(0, gui().getHeight() - 4 - getRelativeBottom()));
    }

    private static boolean hasThermal(ContainerCloudworks container, UUID id) {
        for (mekanism.cloudworks.common.network.data.node.Node node : container.guiData().graph.nodes)
            if (id.equals(node.id)) return node.thermal != null
                    || node.ports.stream().anyMatch(port -> port.codec.equals("cloudworks:heat"));
        return false;
    }

    private static boolean hasRecipeSelection(ContainerCloudworks container, UUID id) {
        if (container.guiData().graph.configuration != null)
            for (CloudworksLineDraft.Machine machine
                    : container.guiData().graph.configuration.machines)
                if (id.equals(machine.id)) return GuiLineMachineWindow.hasRecipeSelection(machine.type, machine.recipeType);
        return false;
    }

    public static boolean hasTemplatePort(ItemStack stack) {
        return hasTemplatePort(stack, "");
    }

    public static boolean hasTemplatePort(ItemStack stack, String recipeType) {
        MachineSlotLayout layout =
                CloudworksRegistries.slots(stack, recipeType);
        return layout != null && layout.slots().stream()
                .anyMatch(slot -> slot.role() == MachineSlotLayout.PortRole.CONFIGURATION);
    }
    public static boolean hasTemplatePort(CloudworksLineDraft.Machine machine) {
        MachineSlotLayout layout =
                CloudworksNodeMechanisms.slots(machine);
        return layout != null && layout.slots().stream()
                .anyMatch(slot -> slot.role() == MachineSlotLayout.PortRole.CONFIGURATION);
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
        machineSnapshot = null;
        upgradeEntries.clear();
        upgradeCounts.clear();
        if (line.equals(graph.id) && graph.configuration != null)
            for (CloudworksLineDraft.Machine value : graph.configuration.machines)
                if (node.equals(value.id)) {
                    machineSnapshot = value;
                    recipeType = value.recipeType;
                    if (installedUpgrades != null) updateInstalledUpgrades(value);
                    break;
                }
        if (installedUpgrades != null && selectedUpgrade != null && upgradeCount(selectedUpgrade) == 0)
            installedUpgrades.clearSelection();
        if (!line.equals(graph.id)) return;
        for (mekanism.cloudworks.common.network.data.node.Node snapshot : graph.nodes) {
            if (!node.equals(snapshot.id)) continue;
            collect(snapshot.input, inputs);
            if (machine) collect(snapshot.output, outputs);
            collect(snapshot.templates, templates);
            break;
        }
    }

    private void updateInstalledUpgrades(CloudworksLineDraft.Machine value) {
        value.assignedCustom.forEach((id, count) -> {
            if (count > 0) addUpgrade(CloudUpgradeEntry.custom(id), count);
        });
    }

    private void addUpgrade(CloudUpgradeEntry entry, long count) {
        upgradeEntries.add(entry);
        upgradeCounts.put(entry.wireId, count);
    }

    private long upgradeCount(CloudUpgradeEntry entry) {
        return upgradeCounts.getOrDefault(entry.wireId, 0L);
    }

    private static void collect(List<CloudworksGuiData.ResourceSummary> resources, List<Entry> target) {
        Map<QIOResourceDescriptor, Long> totals = new LinkedHashMap<>();
        for (CloudworksGuiData.ResourceSummary resource : resources)
            if (resource.amount > 0) totals.merge(resource.descriptor, resource.amount, Math::addExact);
        totals.forEach((resource, amount) -> target.add(new Entry(resource, amount)));
    }

    private void renderContentText(int scroll) {
        drawScrollingString(new TextComponentString(tr(machine ? "input_cache" : "buffer_cache")), 8, 27 - scroll,
                TextAlignment.LEFT, 0x404040, 99, 2, false);
        if (machine)
            drawScrollingString(new TextComponentString(tr("output_cache")), 115, 27 - scroll,
                    TextAlignment.LEFT, 0x404040, 107, 2, false);
        drawScrollingString(new TextComponentString(name), 8, 116 - scroll,
                TextAlignment.LEFT, 0x404040, machine ? 214 : 106, 2, false);
        if (configuration)
            drawScrollingString(new TextComponentString(tr("template_cache")), 115, 142 - scroll,
                    TextAlignment.LEFT, 0x404040, 107, 2, false);
        if (recipeSelection)
            drawScrollingString(new TextComponentString(GuiLineMachineWindow.recipeTypeLabel(recipeType)),
                    8, (configuration ? 165 : 133) + (thermal ? 54 : 0) + 3 - scroll,
                    TextAlignment.LEFT, 0x404040, 214, 2, false);
    }
}
