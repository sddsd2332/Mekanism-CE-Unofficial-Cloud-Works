package mekanism.cloudworks.client.gui.line;

import java.util.*;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid.Entry;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.Choices;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.util.text.TextComponentString;

/** Per-resource replenishment targets. The catalog never transfers physical resources. */
public final class GuiLineChannelWindow extends GuiLineWindow {
    private static final SelectedWindowData.WindowType TYPE = type("channel");
    private final ContainerCloudworks container;
    private final CloudworksLineEditorModel model;
    private final CloudworksLineDraft.Channel channel;
    private final GuiTextField amount;
    private final MekanismButton apply, remove;
    private final List<Entry> marked = new ArrayList<>();
    private QIOResourceDescriptor selected;
    private GuiLineResourcePickerWindow picker;
    private UUID request;
    private Choices received;
    private String feedback = "";

    public GuiLineChannelWindow(IGuiWrapper gui, ContainerCloudworks container, CloudworksLineEditorModel model,
            CloudworksLineDraft.Channel channel) {
        super(gui, 35, 30, 202, 184, "channel_resources", TYPE);
        this.container = container;
        this.model = model;
        this.channel = channel;
        amount = new GuiTextField(gui, relativeX + 8, relativeY + 140, 118, 18).setMaxLength(19);
        amount.setText("1");
        addChild(new GuiLineResourceGrid(gui, relativeX + 8, relativeY + 23, 9, 5,
                () -> marked, () -> selected, this::select, this::remove, this::mark));
        addChild(new MekanismButton(gui, relativeX + 8, relativeY + 117, 90, 18,
                new TextComponentString(tr("select_resources")), this::openPicker, null));
        remove = addChild(new MekanismButton(gui, relativeX + 104, relativeY + 117, 90, 18,
                new TextComponentString(tr("remove_resource")), () -> remove(selected), null));
        addChild(amount);
        apply = addChild(new MekanismButton(gui, relativeX + 129, relativeY + 140, 65, 18,
                new TextComponentString(tr("apply_amount")), this::applyAmount, null));
        rebuildMarked();
        tick();
    }

    private void rebuildMarked() {
        marked.clear();
        for (CloudworksLineDraft.Resource resource : channel.resources)
            marked.add(new Entry(resource.descriptor, resource.amount));
    }

    private void select(QIOResourceDescriptor descriptor) {
        for (CloudworksLineDraft.Resource resource : channel.resources) {
            if (resource.descriptor.equals(descriptor)) {
                selected = descriptor;
                amount.setText(Long.toString(resource.amount));
                feedback = CloudworksResourceCatalog.name(descriptor);
                return;
            }
        }
    }

    private void mark(QIOResourceDescriptor descriptor) {
        if (descriptor == null || !descriptor.isResolved()) return;
        boolean special = !channel.archetypeId.isEmpty();
        if (special && !model.canSelectInfiniteResource(channel.id, descriptor)) return;
        for (CloudworksLineDraft.Resource resource : channel.resources) {
            if (resource.descriptor.equals(descriptor)) {
                select(descriptor);
                return;
            }
        }
        model.addChannelResource(channel.id, descriptor);
        rebuildMarked();
        select(descriptor);
    }

    private void remove(QIOResourceDescriptor descriptor) {
        if (descriptor == null) return;
        model.removeChannelResource(channel.id, descriptor);
        if (descriptor.equals(selected)) selected = null;
        rebuildMarked();
        feedback = "";
    }

    private void applyAmount() {
        try {
            long value = Long.parseLong(amount.getText());
            if (value <= 0) throw new NumberFormatException();
            long total = value;
            for (CloudworksLineDraft.Resource resource : channel.resources)
                if (!resource.descriptor.equals(selected)) total = Math.addExact(total, resource.amount);
            if (selected != null) model.setChannelResourceAmount(channel.id, selected, value);
            rebuildMarked();
            feedback = selected == null ? "" : CloudworksResourceCatalog.name(selected);
        } catch (NumberFormatException | ArithmeticException invalid) {
            feedback = tr("invalid_value");
        }
    }

    private void openPicker() {
        if (picker != null) return;
        CloudworksLineDraft draft = model.draft;
        Set<QIOResourceDescriptor> extra = new LinkedHashSet<>(draft.markers);
        for (CloudworksLineDraft.Channel node : draft.channels)
            for (CloudworksLineDraft.Resource resource : node.resources) extra.add(resource.descriptor);
        for (CloudworksLineDraft.Wire link : draft.links) extra.addAll(link.filters);
        picker = channel.archetypeId.isEmpty()
                ? new GuiLineResourcePickerWindow(gui(), extra, this::mark)
                : new GuiLineResourcePickerWindow(gui(), model.infiniteResources(), this::mark,
                descriptor -> model.canSelectInfiniteResource(channel.id, descriptor));
        picker.setTabListeners(window -> picker = null, null);
        gui().addWindow(picker);
        if (channel.archetypeId.isEmpty()) query(0);
    }

    private void query(int offset) {
        request = UUID.randomUUID();
        received = null;
        container.sendEditorAction(Operation.RESOURCE_CHOICES, model.draft.line, request, offset + "|");
    }

    @Override
    public void tick() {
        super.tick();
        if (picker != null) {
            Choices choices = container.guiData().choices;
            if (request != null && request.equals(choices.context) && received != choices) {
                received = choices;
                picker.addResources(choices.resources);
                if (choices.offset + choices.resources.size() < choices.total) query(choices.offset + choices.resources.size());
            }
        }
        remove.active = selected != null;
        apply.active = selected != null && channel.archetypeId.isEmpty();
        amount.visible = apply.visible = channel.archetypeId.isEmpty();
    }

    @Override
    public void close() {
        if (picker != null) picker.close();
        super.close();
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawScrollingString(new TextComponentString(feedback), 8, 167,
                TextAlignment.LEFT, 0x404040, 186, 2, false);
    }
}
