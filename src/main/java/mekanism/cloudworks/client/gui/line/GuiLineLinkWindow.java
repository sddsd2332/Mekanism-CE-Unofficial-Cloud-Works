package mekanism.cloudworks.client.gui.line;

import java.util.*;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.Choices;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid.Entry;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.util.text.TextComponentString;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;

public final class GuiLineLinkWindow extends GuiLineWindow {
    private static final SelectedWindowData.WindowType TYPE = type("link");
    private final ContainerCloudworks container;
    private final CloudworksLineEditorModel model;
    private final CloudworksLineDraft.Wire wire;
    private final List<Entry> filters = new ArrayList<>();
    private final MekanismButton remove;
    private QIOResourceDescriptor selected;
    private GuiLineResourcePickerWindow picker;
    private UUID request;
    private Choices received;
    private String error = "";

    public GuiLineLinkWindow(IGuiWrapper gui, ContainerCloudworks container, CloudworksLineEditorModel model,
            CloudworksLineDraft.Wire wire) {
        super(gui, 35, 30, 202, 207, "connection", TYPE);
        this.container = container;
        this.model = model;
        this.wire = wire;
        GuiTextField priority = addChild(new GuiTextField(gui, relativeX + 8, relativeY + 40, 90, 18).setMaxLength(11));
        GuiTextField ratio = addChild(new GuiTextField(gui, relativeX + 104, relativeY + 40, 90, 18).setMaxLength(12));
        priority.setText(Integer.toString(wire.priority));
        ratio.setText(Double.toString(wire.ratio));
        addChild(new GuiLineResourceGrid(gui, relativeX + 8, relativeY + 63, 9, 4,
                () -> filters, () -> selected, descriptor -> selected = descriptor,
                this::removeFilter, this::addFilter));
        MekanismButton selectResources = addChild(new MekanismButton(gui, relativeX + 8, relativeY + 140, 90, 18,
                new TextComponentString(tr("select_resources")), this::openPicker, null));
        selectResources.active = !wire.sourcePort.equals("heat_output");
        remove = addChild(new MekanismButton(gui, relativeX + 104, relativeY + 140, 90, 18,
                new TextComponentString(tr("remove_resource")), () -> removeFilter(selected), null));
        addChild(new MekanismButton(gui, relativeX + 8, relativeY + 163, 90, 18,
                new TextComponentString(tr("apply_link")), () -> {
                    try {
                        int order = Integer.parseInt(priority.getText());
                        double proportion = Double.parseDouble(ratio.getText());
                        if (!Double.isFinite(proportion) || proportion < 0 || proportion > 1) throw new NumberFormatException();
                        model.updateLink(wire.id, order, proportion);
                        close();
                    } catch (NumberFormatException invalid) {
                        error = tr("invalid_value");
                    }
                }, null));
        addChild(new MekanismButton(gui, relativeX + 104, relativeY + 163, 90, 18,
                new TextComponentString(tr("unlink")), () -> {
                    model.removeLink(wire.id);
                    close();
                }, null));
        rebuildFilters();
        tick();
    }

    private void openPicker() {
        if (picker != null) return;
        CloudworksLineDraft draft = model.draft;
        Set<QIOResourceDescriptor> extra = new LinkedHashSet<>(draft.markers);
        for (CloudworksLineDraft.Channel channel : draft.channels)
            for (CloudworksLineDraft.Resource resource : channel.resources) extra.add(resource.descriptor);
        for (CloudworksLineDraft.Wire link : draft.links) extra.addAll(link.filters);
        picker = new GuiLineResourcePickerWindow(gui(), extra, this::addFilter, this::accepts);
        picker.setTabListeners(window -> picker = null, null);
        gui().addWindow(picker);
        query(0);
    }

    private boolean accepts(QIOResourceDescriptor descriptor) {
        if (descriptor == null || !descriptor.isResolved()) return false;
        CloudworksLineDraft draft = model.draft;
        for (CloudworksLineDraft.Channel channel : draft.channels)
            if (channel.id.equals(wire.source))
                return descriptor.getCodecId().toString().equals(wire.sourcePort);
        for (CloudworksLineDraft.Machine machine : draft.machines)
            if (machine.id.equals(wire.source)) {
                MachineSlotLayout layout = CloudworksNodeMechanisms.slots(machine);
                MachineSlotLayout.Slot slot = layout == null ? null : layout.find(wire.sourcePort);
                return slot != null && slot.codecId().equals(descriptor.getCodecId());
            }
        return false;
    }

    private void addFilter(QIOResourceDescriptor descriptor) {
        if (accepts(descriptor)) {
            model.addLinkFilter(wire.id, descriptor);
            selected = descriptor;
            rebuildFilters();
            error = "";
        } else {
            error = tr("invalid_value");
        }
    }

    private void removeFilter(QIOResourceDescriptor descriptor) {
        model.removeLinkFilter(wire.id, descriptor);
        if (Objects.equals(selected, descriptor)) selected = null;
        rebuildFilters();
        error = "";
    }

    private void rebuildFilters() {
        filters.clear();
        for (QIOResourceDescriptor filter : wire.filters) filters.add(new Entry(filter, 1));
    }

    private void query(int offset) {
        request = UUID.randomUUID();
        received = null;
        container.sendEditorAction(Operation.RESOURCE_CHOICES, model.draft.line, request, offset + "|");
    }

    @Override
    public void tick() {
        super.tick();
        remove.active = selected != null;
        if (picker != null) {
            Choices choices = container.guiData().choices;
            if (request != null && request.equals(choices.context) && received != choices) {
                received = choices;
                picker.addResources(choices.resources);
                if (choices.offset + choices.resources.size() < choices.total)
                    query(choices.offset + choices.resources.size());
            }
        }
    }

    @Override
    public void close() {
        if (picker != null) picker.close();
        super.close();
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawScrollingString(new TextComponentString(tr("priority_ratio")), 8, 26,
                TextAlignment.LEFT, 0x404040, 186, 3, false);
        String filter = selected == null ? tr(wire.filters.isEmpty() ? "all_resources" : "filter")
                : CloudworksResourceCatalog.name(selected);
        drawScrollingString(new TextComponentString(error.isEmpty() ? filter : error), 8, 190,
                TextAlignment.LEFT, 0x404040, 186, 3, false);
    }
}
