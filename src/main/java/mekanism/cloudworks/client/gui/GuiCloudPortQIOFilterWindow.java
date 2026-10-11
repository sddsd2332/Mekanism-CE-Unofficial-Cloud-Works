package mekanism.cloudworks.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid.Entry;
import mekanism.cloudworks.client.gui.line.CloudworksResourceCatalog;
import mekanism.cloudworks.client.gui.line.GuiLineResourceGrid;
import mekanism.cloudworks.client.gui.line.GuiLineResourcePickerWindow;
import mekanism.cloudworks.common.content.CloudworksPortIo;
import mekanism.cloudworks.common.network.CloudworksNetwork;
import mekanism.cloudworks.common.network.PacketCloudworksPortQIO;
import mekanism.cloudworks.common.network.PacketCloudworksPortQIO.Operation;
import mekanism.cloudworks.common.tile.prefab.TileEntityCloudworksPort;
import mekanism.common.inventory.container.MekanismTileContainer;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.util.LangUtils;
import net.minecraft.util.text.TextComponentString;

public final class GuiCloudPortQIOFilterWindow extends GuiWindow {
    private final TileEntityCloudworksPort tile;
    private final MekanismTileContainer<?> container;
    private final List<Entry> entries = new ArrayList<>();
    private List<QIOResourceDescriptor> displayed = Collections.emptyList();
    private final MekanismButton remove;
    private QIOResourceDescriptor selected;
    private GuiLineResourcePickerWindow picker;
    private int choicesVersion;

    public GuiCloudPortQIOFilterWindow(IGuiWrapper gui, TileEntityCloudworksPort tile,
            MekanismTileContainer<?> container, SelectedWindowData window) {
        super(gui, (gui.getWidth() - 202) / 2, 15, 202, 142, window);
        this.tile = tile;
        this.container = container;
        interactionStrategy = InteractionStrategy.ALL;
        addChild(new GuiLineResourceGrid(gui, relativeX + 8, relativeY + 23, 9, 4,
                () -> entries, resource -> selected = resource,
                resource -> request(Operation.REMOVE_FILTER, resource, 0),
                resource -> request(Operation.ADD_FILTER, resource, 0)));
        addChild(new MekanismButton(gui, relativeX + 8, relativeY + 101, 90, 18,
                text("cloudworks.editor.select_resources"), this::openPicker, null));
        remove = addChild(new MekanismButton(gui, relativeX + 104, relativeY + 101, 90, 18,
                text("cloudworks.editor.remove_resource"),
                () -> request(Operation.REMOVE_FILTER, selected, 0), null));
        remove.active = false;
        request(Operation.REFRESH, null, 0);
    }

    private static TextComponentString text(String key) {
        return new TextComponentString(LangUtils.localize(key));
    }

    private void request(Operation operation, QIOResourceDescriptor resource, int offset) {
        if (resource != null && !CloudworksPortIo.isSupportedDownloadResource(resource)) return;
        CloudworksNetwork.CHANNEL.sendToServer(
                new PacketCloudworksPortQIO.Request(container.windowId, operation, resource, offset));
    }

    private void openPicker() {
        if (picker != null) return;
        picker = new GuiLineResourcePickerWindow(gui(), tile.getQIOFilters(),
                resource -> request(Operation.ADD_FILTER, resource, 0),
                CloudworksPortIo::isSupportedDownloadResource);
        picker.setTabListeners(window -> picker = null, null);
        gui().addWindow(picker);
        choicesVersion = tile.getQIOChoicesVersion();
        request(Operation.RESOURCES, null, 0);
    }

    @Override public void tick() {
        super.tick();
        if (!tile.hasQIOUpgrade()) {
            close();
            return;
        }
        if (!displayed.equals(tile.getQIOFilters())) {
            displayed = new ArrayList<>(tile.getQIOFilters());
            entries.clear();
            for (QIOResourceDescriptor resource : displayed) entries.add(new Entry(resource, 1));
            if (!displayed.contains(selected)) selected = null;
        }
        remove.active = selected != null && displayed.contains(selected);
        if (picker != null && choicesVersion != tile.getQIOChoicesVersion()) {
            choicesVersion = tile.getQIOChoicesVersion();
            picker.addResources(tile.getQIOChoices());
            int next = tile.getQIOChoicesOffset() + tile.getQIOChoices().size();
            if (next < tile.getQIOChoicesTotal() && !tile.getQIOChoices().isEmpty())
                request(Operation.RESOURCES, null, next);
        }
    }

    @Override public void close() {
        if (picker != null) picker.close();
        super.close();
    }

    @Override public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawTitleText(text("cloudworks.port.qio_filters"), 6);
        if (selected != null)
            drawScrollingString(new TextComponentString(CloudworksResourceCatalog.name(selected)),
                    8, 127, TextAlignment.LEFT, 0x404040, 186, 2, false);
    }
}
