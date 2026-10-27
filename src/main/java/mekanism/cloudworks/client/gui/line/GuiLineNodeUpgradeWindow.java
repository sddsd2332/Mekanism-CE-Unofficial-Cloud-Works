package mekanism.cloudworks.client.gui.line;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mekanism.client.gui.IGuiWrapper;
import mekanism.cloudworks.client.gui.GuiCloudworksLine;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.cloudworks.client.gui.element.CloudUpgradeEntry;
import mekanism.cloudworks.client.gui.element.GuiCloudUpgradeScrollList;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeLimits;
import mekanism.common.Upgrade;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import mekanism.cloudworks.api.machine.CloudworksEnergyGenerator;
import mekanism.cloudworks.common.recipe.CloudworksWindGeneratorAdapter;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import net.minecraft.util.text.TextComponentTranslation;

/** Available custom module assets feed one node through the draft transaction. */
public final class GuiLineNodeUpgradeWindow extends GuiLineWindow {
    private enum Source { CLOUD, NODE }

    private final CloudworksLineEditorModel model;
    private final UUID node;
    private final GuiCloudUpgradeScrollList cloudList, nodeList;
    private final MekanismButton add, remove;
    private CloudworksGuiData seenGraph;
    private final List<String> seenCustomTypes = new ArrayList<>();
    private final List<CloudUpgradeEntry> entries = new ArrayList<>();
    private Source selectedSource;
    private CloudUpgradeEntry selected;

    public GuiLineNodeUpgradeWindow(IGuiWrapper gui, CloudworksLineEditorModel model, UUID node) {
        super(gui, 30, 58, 240, 202, "node_upgrades", type("upgrades"));
        this.model = model;
        this.node = node;
        refreshEntries();
        cloudList = addChild(new GuiCloudUpgradeScrollList(gui, relativeX + 8, relativeY + 32, 106,
                () -> entries(Source.CLOUD), entry -> displayCount(entry, Source.CLOUD),
                entry -> select(Source.CLOUD, entry)));
        nodeList = addChild(new GuiCloudUpgradeScrollList(gui, relativeX + 124, relativeY + 32, 106,
                () -> entries(Source.NODE), entry -> displayCount(entry, Source.NODE),
                entry -> select(Source.NODE, entry)));
        add = addChild(new MekanismButton(gui, relativeX + 8, relativeY + 143, 108, 18,
                new TextComponentString(tr("add_upgrade")), () -> transfer(true), null));
        remove = addChild(new MekanismButton(gui, relativeX + 124, relativeY + 143, 108, 18,
                new TextComponentString(tr("uninstall")), () -> transfer(false), null));
        tick();
    }

    private CloudworksLineDraft.Machine current() {
        if (model.draft != null) for (CloudworksLineDraft.Machine machine : model.draft.machines)
            if (machine.id.equals(node)) return machine;
        return null;
    }

    private void refreshEntries() {
        seenGraph = model.graphSnapshot();
        seenCustomTypes.clear();
        seenCustomTypes.addAll(model.customPoolIds());
        entries.clear();
        for (String id : seenCustomTypes) entries.add(CloudUpgradeEntry.custom(id));
    }

    private List<CloudUpgradeEntry> entries(Source source) {
        List<CloudUpgradeEntry> visible = new ArrayList<>();
        CloudworksLineDraft.Machine machine = current();
        if (machine == null) return visible;
        for (CloudUpgradeEntry entry : entries) {
            if (capacity(entry, machine) == 0) continue;
            if (count(entry, source) > 0) visible.add(entry);
        }
        return visible;
    }

    private long capacity(CloudUpgradeEntry entry, CloudworksLineDraft.Machine machine) {
        return entry.customType()
                ? CloudworksUpgradeLimits.draftCustomCapacity(machine, new ResourceLocation(entry.wireId))
                : CloudworksUpgradeLimits.nodeCapacity(
                        "speed".equals(entry.wireId) ? Upgrade.SPEED : Upgrade.ENERGY, machine);
    }

    private long count(CloudUpgradeEntry entry, Source source) {
        if (source == Source.CLOUD) return entry.customType() ? model.customCloudAvailable(entry.wireId) : 0;
        CloudworksLineDraft.Machine machine = current();
        if (machine == null) return 0;
        return entry.customType() ? machine.assignedCustom(entry.wireId) : 0;
    }

    private int displayCount(CloudUpgradeEntry entry, Source source) {
        return (int) Math.min(Integer.MAX_VALUE, count(entry, source));
    }

    private GuiCloudUpgradeScrollList list(Source source) {
        return switch (source) {
            case CLOUD -> cloudList;
            case NODE -> nodeList;
        };
    }

    private void select(Source source, CloudUpgradeEntry entry) {
        if (entry != null) {
            selectedSource = source;
            selected = entry;
            for (Source other : Source.values()) if (other != source) list(other).clearSelection();
        } else if (selectedSource == source) {
            selectedSource = null;
            selected = null;
        }
        updateEnabledButtons();
    }

    private void transfer(boolean install) {
        CloudworksLineDraft.Machine machine = current();
        if (machine == null || machine.waiting != null || selected == null
                || install != (selectedSource != Source.NODE)) return;
        CloudUpgradeEntry entry = selected;
        if (entry.customType())
            model.nodeCustomUpgrade(node, entry.wireId, install, GuiScreen.isShiftKeyDown());
        if (selected != null && count(entry, selectedSource) == 0) list(selectedSource).clearSelection();
        updateEnabledButtons();
    }

    @Override
    public void tick() {
        if (seenGraph != model.graphSnapshot() || !seenCustomTypes.equals(model.customPoolIds())) refreshEntries();
        super.tick();
        updateEnabledButtons();
    }

    @Override
    public void close() {
        // On-demand catalogue: closing the only catalogue consumer drops the server-side view,
        // so the next data push clears the stale catalogue from the client.
        if (gui() instanceof GuiCloudworksLine lineGui) lineGui.setCatalogRequested(false);
        super.close();
    }

    private void updateEnabledButtons() {
        CloudworksLineDraft.Machine machine = current();
        boolean ready = machine != null && machine.waiting == null;
        add.active = ready && selected != null && selectedSource != Source.NODE
                && count(selected, selectedSource) > 0 && count(selected, Source.NODE) < capacity(selected, machine);
        remove.active = ready && selected != null && selectedSource == Source.NODE
                && count(selected, Source.NODE) > 0;
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawScrollingString(new TextComponentString(tr("available_node_upgrades")), 8, 21,
                TextAlignment.LEFT, 0x404040, 108, 2, false);
        drawScrollingString(new TextComponentString(tr("node_upgrades")), 124, 21,
                TextAlignment.LEFT, 0x404040, 108, 2, false);
        CloudworksLineDraft.Machine machine = current();
        if (machine == null || selected == null || !selected.customType()) return;
        long perMachine = machine.count <= 0 ? 0 : machine.assignedCustom(selected.wireId) / machine.count;
        drawScrollingString(new TextComponentTranslation("cloudworks.editor.module_per_machine", perMachine),
                8, 167, TextAlignment.LEFT, 0x404040, 224, 2, false);
        if (MachineModuleType.HEIGHT_ID.toString().equals(selected.wireId)) {
            CloudworksEnergyGenerator generator =
                    CloudworksRegistries.energyGenerator(machine.type);
            if (generator instanceof CloudworksWindGeneratorAdapter) {
                CloudworksWindGeneratorAdapter wind =
                        (CloudworksWindGeneratorAdapter) generator;
                drawScrollingString(new TextComponentTranslation("cloudworks.editor.height_effective",
                                wind.effectiveHeight((int) Math.min(Integer.MAX_VALUE, perMachine)),
                                wind.effectiveHeight(wind.heightLimit())),
                        8, 182, TextAlignment.LEFT, 0x404040, 224, 2, false);
            }
        } else if (MachineModuleType.LAVA_HEAT_ID.toString().equals(selected.wireId))
            drawScrollingString(new TextComponentTranslation("cloudworks.editor.lava_positions", perMachine),
                    8, 182, TextAlignment.LEFT, 0x404040, 224, 2, false);
    }
}
