package mekanism.cloudworks.client.gui.line;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.scroll.GuiTextScrollList;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.cloudworks.client.gui.CloudworksViewport;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid;
import mekanism.cloudworks.common.blueprint.CloudworksBlueprint;
import mekanism.cloudworks.common.blueprint.CloudworksBlueprintLibrary;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.PacketCloudworksBlueprint;
import mekanism.cloudworks.common.network.PacketCloudworksBlueprint.Metadata;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import net.minecraft.util.text.TextComponentString;

/** The single library/import entry point, with a shared read-only line canvas. */
public final class GuiLineBlueprintWindow extends GuiLineWindow {
    private final ContainerCloudworks container;
    private final UUID line;
    private final boolean canPlace;
    private final Consumer<CloudworksBlueprint> place;
    private final GuiTextField playerFilter, nameFilter, code;
    private final GuiTextScrollList list;
    private final GuiLineCanvas preview;
    private final GuiCloudQIOResourceGrid markers;
    private final CloudworksLineEditorModel previewModel = new CloudworksLineEditorModel();
    private final MekanismButton mode, placeButton, copy, delete, parse, players;
    private final List<Metadata> all = new ArrayList<>(), shown = new ArrayList<>();
    private long version;
    private UUID selected;
    private CloudworksBlueprint selectedBlueprint;
    private String query = "", feedback = "";
    private boolean pastePage, deleteConfirmed, placing;

    public GuiLineBlueprintWindow(IGuiWrapper gui, ContainerCloudworks container, UUID line, boolean canPlace,
            Consumer<CloudworksBlueprint> place) {
        super(gui, Math.max(8, (gui.getWidth() - Math.min(500, gui.getWidth() - 16)) / 2), 24,
                Math.min(500, gui.getWidth() - 16), Math.max(150, Math.min(264, gui.getHeight() - 48)),
                "blueprint_library", type("blueprints"));
        this.container = container; this.line = line; this.canPlace = canPlace; this.place = place;
        int leftWidth = Math.min(170, (width - 28) / 2);
        playerFilter = addChild(new GuiTextField(gui, relativeX + 8, relativeY + 25, leftWidth - 21, 18).setMaxLength(64));
        players = addChild(new MekanismButton(gui, relativeX + leftWidth - 10, relativeY + 25, 18, 18,
                new TextComponentString(">"), this::nextPlayer, null));
        nameFilter = addChild(new GuiTextField(gui, relativeX + leftWidth + 12, relativeY + 25,
                Math.max(25, width - leftWidth - 100), 18).setMaxLength(64));
        mode = addChild(new MekanismButton(gui, relativeX + width - 80, relativeY + 25, 72, 18,
                new TextComponentString(tr("blueprint_paste")), () -> { pastePage = !pastePage; updateVisibility(); }, null));
        list = addChild(createBlueprintList(gui, relativeX + 8, relativeY + 59,
                leftWidth, height - 90, this::selectBlueprint));
        preview = addChild(new GuiLineCanvas(gui, relativeX + leftWidth + 12, relativeY + 59,
                width - leftWidth - 20, height - 114, new CloudworksViewport(), previewModel,
                () -> previewModel.draft, () -> CloudworksGuiData.EMPTY.graph, () -> false, () -> {}, () -> -1));
        preview.preview(true);
        markers = addChild(new GuiCloudQIOResourceGrid(gui, relativeX + leftWidth + 12, relativeY + height - 51,
                Math.min(10, Math.max(1, (width - leftWidth - 30) / 18)), 1, () -> {
                    List<GuiCloudQIOResourceGrid.Entry> entries = new ArrayList<>();
                    if (previewModel.draft != null) previewModel.draft.markers.forEach(marker ->
                            entries.add(new GuiCloudQIOResourceGrid.Entry(marker, 1)));
                    return entries;
                }));
        int buttonWidth = Math.max(30, (width - 22) / 3);
        placeButton = addChild(new MekanismButton(gui, relativeX + 8, relativeY + height - 25, buttonWidth, 18,
                new TextComponentString(tr("blueprint_place")), this::placeSelected, null));
        copy = addChild(new MekanismButton(gui, relativeX + 11 + buttonWidth, relativeY + height - 25, buttonWidth, 18,
                new TextComponentString(tr("blueprint_copy")), () -> request(PacketCloudworksBlueprint.COPY, selected, ""), null));
        delete = addChild(new MekanismButton(gui, relativeX + 14 + buttonWidth * 2, relativeY + height - 25, buttonWidth, 18,
                new TextComponentString(tr("blueprint_delete")), this::deleteSelected, null));
        code = addChild(new GuiTextField(gui, relativeX + 8, relativeY + 59, width - 16, 18)
                .setMaxLength(CloudworksBlueprintLibrary.CODE_LENGTH));
        parse = addChild(new MekanismButton(gui, relativeX + 8, relativeY + 83, Math.max(40, width - 16), 18,
                new TextComponentString(tr("blueprint_parse")), () -> {
                    if (!canPlace) return;
                    placing = true;
                    request(PacketCloudworksBlueprint.PARSE, null, code.getText().trim());
                }, null));
        version = container.blueprintVersion();
        requestList(0);
        updateVisibility();
    }

    private void request(int operation, UUID entry, String text) {
        container.requestBlueprint(operation, line, entry, text, null, 0, 0, Collections.emptySet());
    }

    private void requestList(int offset) {
        container.requestBlueprint(PacketCloudworksBlueprint.LIST, line, null, "", null,
                offset, 0, Collections.emptySet());
    }

    static GuiTextScrollList createBlueprintList(IGuiWrapper gui, int x, int y, int width, int height,
            IntConsumer onSelection) {
        return new GuiTextScrollList(gui, x, y, width, height) {
            @Override protected void setSelected(int index) {
                super.setSelected(index);
                onSelection.accept(index);
            }
        };
    }
    private void nextPlayer() {
        List<String> names = new ArrayList<>();
        names.add("");
        all.stream().map(entry -> entry.exporterName).distinct().sorted(String.CASE_INSENSITIVE_ORDER).forEach(names::add);
        int current = names.indexOf(playerFilter.getText());
        playerFilter.setText(names.get((current + 1) % names.size()));
    }
    private void selectBlueprint(int index) {
        if (container.blueprintBusy() || index < 0 || index >= shown.size()) return;
        selected = shown.get(index).id; selectedBlueprint = null; deleteConfirmed = false;
        delete.setMessage(new TextComponentString(tr("blueprint_delete")));
        previewModel.draft = null; placing = false;
        request(PacketCloudworksBlueprint.READ, selected, "");
    }
    private void placeSelected() {
        if (!canPlace || selectedBlueprint == null) return;
        placing = true;
        container.requestBlueprint(PacketCloudworksBlueprint.PRECHECK, line, null, "",
                selectedBlueprint, 0, 0, Collections.emptySet());
    }
    private void deleteSelected() {
        if (!deleteConfirmed) {
            deleteConfirmed = true;
            delete.setMessage(new TextComponentString(tr("confirm_delete")));
        } else request(PacketCloudworksBlueprint.DELETE, selected, "");
    }
    private void filter() {
        String player = playerFilter.getText().trim().toLowerCase(Locale.ROOT);
        String name = nameFilter.getText().trim().toLowerCase(Locale.ROOT);
        String next = player + "\n" + name;
        if (query.equals(next)) return;
        query = next;
        shown.clear();
        List<String> labels = new ArrayList<>();
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (Metadata entry : all) if (entry.exporterName.toLowerCase(Locale.ROOT).contains(player)
                && entry.name.toLowerCase(Locale.ROOT).contains(name)) {
            shown.add(entry);
            labels.add(entry.name + " / " + entry.exporterName + " / " + time.format(new Date(entry.time)));
        }
        list.setText(labels);
        clearSelection();
    }
    private void clearSelection() {
        selected = null; selectedBlueprint = null; previewModel.draft = null;
        list.clearSelection(); deleteConfirmed = false;
        delete.setMessage(new TextComponentString(tr("blueprint_delete")));
    }
    private void updateVisibility() {
        playerFilter.visible = nameFilter.visible = players.visible = list.visible = preview.visible = markers.visible = !pastePage;
        placeButton.visible = copy.visible = delete.visible = !pastePage;
        code.visible = parse.visible = pastePage;
        mode.setMessage(new TextComponentString(tr(pastePage ? "blueprint_library" : "blueprint_paste")));
    }
    @Override public void tick() {
        super.tick();
        if (version != container.blueprintVersion()) {
            version = container.blueprintVersion();
            PacketCloudworksBlueprint.Result result = container.blueprintResult;
            if (!result.error.isEmpty()) {
                feedback = mekanism.common.util.LangUtils.localize(result.error);
                if (result.operation == PacketCloudworksBlueprint.PARSE
                        || result.operation == PacketCloudworksBlueprint.PRECHECK) placing = false;
            }
            else if (result.operation == PacketCloudworksBlueprint.LIST) {
                if (result.pageOffset == 0) all.clear();
                all.addAll(result.entries); query = "\0"; filter();
                if (result.hasMore && !container.blueprintBusy()) requestList(result.nextOffset);
            } else if (result.operation == PacketCloudworksBlueprint.READ) {
                selectedBlueprint = result.blueprint;
                previewModel.draft = selectedBlueprint.draft();
                preview.fit();
                feedback = summary(selectedBlueprint);
            } else if ((result.operation == PacketCloudworksBlueprint.PARSE
                    || result.operation == PacketCloudworksBlueprint.PRECHECK) && placing) {
                placing = false;
                if (canPlace && result.blueprint != null) {
                    feedback = summary(result.blueprint);
                    place.accept(result.blueprint);
                    close();
                    return;
                }
                feedback = mekanism.common.util.LangUtils.localize("cloudworks.error.blueprint_invalid");
            } else if (result.operation == PacketCloudworksBlueprint.DELETE) {
                clearSelection(); requestList(0);
            }
        }
        filter();
        boolean ready = !container.blueprintBusy();
        placeButton.active = canPlace && ready && selectedBlueprint != null;
        copy.active = ready && selectedBlueprint != null;
        Metadata entry = shown.stream().filter(value -> value.id.equals(selected)).findFirst().orElse(null);
        delete.visible = !pastePage && entry != null && entry.canDelete;
        delete.active = ready && entry != null;
        parse.active = canPlace && ready && !code.getText().trim().isEmpty();
        mode.active = ready;
        list.active = ready;
    }
    private static String summary(CloudworksBlueprint blueprint) {
        mekanism.cloudworks.common.network.data.CloudworksLineDraft draft = blueprint.draft();
        return mekanism.common.util.LangUtils.localizeWithFormat("cloudworks.editor.blueprint_summary",
                draft.name, draft.machines.size() + draft.channels.size(), draft.links.size(), draft.channels.size());
    }
    @Override public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        if (!pastePage) {
            drawScrollingString(new TextComponentString(tr("blueprint_player")), 8, 16, TextAlignment.LEFT,
                    0x404040, 140, 1, false);
            drawScrollingString(new TextComponentString(tr("blueprint_name")), Math.min(170, (width - 28) / 2) + 12,
                    16, TextAlignment.LEFT, 0x404040, 100, 1, false);
        }
        drawScrollingString(new TextComponentString(feedback), 8, 47, TextAlignment.LEFT,
                0x404040, width - 16, 1, false);
    }
}
