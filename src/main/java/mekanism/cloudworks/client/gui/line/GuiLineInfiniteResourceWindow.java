package mekanism.cloudworks.client.gui.line;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.slot.GuiSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.cloudworks.client.gui.element.CloudworksResourceIcon;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetypes;
import mekanism.common.util.LangUtils;
import net.minecraft.util.text.TextComponentString;

/** One configured resource, not a physical buffer or a multi-resource manifest. */
public final class GuiLineInfiniteResourceWindow extends GuiLineWindow {
    private final ContainerCloudworks container;
    private final CloudworksLineEditorModel model;
    private final CloudworksLineDraft expectedDraft;
    private final UUID line, node;
    private final boolean editing;
    private final MekanismButton select, remove;
    private GuiLineResourcePickerWindow picker;

    public GuiLineInfiniteResourceWindow(IGuiWrapper gui, ContainerCloudworks container,
            CloudworksLineEditorModel model, UUID line, UUID node, boolean editing) {
        super(gui, 35, 30, 202, editing ? 75 : 101,
                "cloudworks.special.infinite_resources", type("channel"));
        this.container = container;
        this.model = model;
        this.line = line;
        this.node = node;
        this.editing = editing;
        expectedDraft = editing ? model.draft : null;
        addChild(new GuiSlot(SlotType.NORMAL, gui, relativeX + 92, relativeY + 24) {
            @Override
            protected void drawContents() {
                QIOResourceDescriptor resource = selectedResource();
                if (resource == null) return;
                if (!CloudworksResourceIcon.render(gui(), resource, relativeX + 1, relativeY + 1)) {
                    CloudworksLineDraft.Channel channel = channel();
                    if (channel != null)
                        gui().renderItem(CloudworksChannelIcons.stack(channel.archetypeId, channel.mode),
                                relativeX + 1, relativeY + 1);
                }
            }
        }.setRenderHover(true).tooltip(this::slotTooltip));
        if (editing) {
            select = addChild(new MekanismButton(gui, relativeX + 8, relativeY + 49, 90, 18,
                    new TextComponentString(tr("select_resources")), this::openPicker, null));
            remove = addChild(new MekanismButton(gui, relativeX + 104, relativeY + 49, 90, 18,
                    new TextComponentString(tr("remove_resource")), this::removeResource, null));
        } else {
            select = remove = null;
        }
        refreshButtons();
    }

    private CloudworksLineDraft.Channel channel() {
        CloudworksLineDraft configuration;
        if (editing) {
            configuration = model.draft == expectedDraft ? expectedDraft : null;
        } else {
            configuration = line.equals(container.guiData().graph.id) ? container.guiData().graph.configuration : null;
        }
        if (configuration != null) for (CloudworksLineDraft.Channel channel : configuration.channels)
            if (node.equals(channel.id)
                    && CloudworksSpecialNodeArchetypes.INFINITE_RESOURCES.toString().equals(channel.archetypeId))
                return channel;
        return null;
    }

    private QIOResourceDescriptor selectedResource() {
        CloudworksLineDraft.Channel channel = channel();
        return channel == null || channel.resources.isEmpty() ? null : channel.resources.get(0).descriptor;
    }

    private boolean editable() {
        CloudworksLineDraft.Channel channel = channel();
        return editing && model.clientActionsAllowed(expectedDraft) && channel != null && channel.waiting == null;
    }

    private void openPicker() {
        if (!editable() || picker != null) return;
        picker = new GuiLineResourcePickerWindow(gui(), model.infiniteResources(), resource -> {
            if (editable() && model.canSelectInfiniteResource(node, resource))
                model.addChannelResource(node, resource);
            refreshButtons();
        }, resource -> editable() && model.canSelectInfiniteResource(node, resource));
        picker.setTabListeners(window -> {
            picker = null;
            refreshButtons();
        }, null);
        gui().addWindow(picker);
        refreshButtons();
    }

    private void removeResource() {
        QIOResourceDescriptor resource = selectedResource();
        if (editable() && resource != null) model.removeChannelResource(node, resource);
        refreshButtons();
    }

    private List<String> slotTooltip() {
        QIOResourceDescriptor resource = selectedResource();
        return resource == null ? Collections.emptyList() : Collections.singletonList(CloudworksResourceCatalog.name(resource));
    }

    private void refreshButtons() {
        if (!editing) return;
        select.active = editable() && picker == null;
        remove.active = editable() && selectedResource() != null;
    }

    @Override
    public void tick() {
        super.tick();
        refreshButtons();
    }

    @Override
    public void close() {
        if (picker != null) picker.close();
        super.close();
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        if (editing) return;
        QIOResourceDescriptor resource = selectedResource();
        String family = resource == null ? tr("resource_unselected") : tr(switch (CloudworksResourceIcon.family(resource)) {
            case ITEM -> "resources_item";
            case FLUID -> "resources_fluid";
            case GAS -> "resources_gas";
            case INFUSE -> "resources_infuse";
            case OTHER, UNKNOWN -> "resources_other";
        });
        String name = resource == null ? tr("resource_unselected") : CloudworksResourceCatalog.name(resource);
        String[] keys = {"infinite_node_type", "infinite_resource_family", "infinite_resource_name"};
        String[] values = {tr("resource_source"), family, name};
        for (int index = 0; index < keys.length; index++)
            drawScrollingString(new TextComponentString(LangUtils.localizeWithFormat(
                            "cloudworks.editor." + keys[index], values[index])),
                    8, 49 + index * 14, TextAlignment.LEFT, 0x404040, 186, 2, false);
    }
}
