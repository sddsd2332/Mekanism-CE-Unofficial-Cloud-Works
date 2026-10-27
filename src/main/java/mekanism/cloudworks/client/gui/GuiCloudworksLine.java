package mekanism.cloudworks.client.gui;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.button.MekanismImageButton;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.cloudworks.client.gui.element.GuiCloudSlotScroll;
import mekanism.cloudworks.client.gui.line.*;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import mekanism.cloudworks.common.network.PacketCloudworksLineEdit;
import mekanism.cloudworks.common.network.PacketCloudworksBlueprint;
import mekanism.cloudworks.common.network.CloudworksLineDraftCodec;
import mekanism.cloudworks.common.blueprint.CloudworksBlueprint;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.common.inventory.ISlotClickHandler.IScrollableSlot;
import mekanism.common.util.LangUtils;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.registries.CloudworksItems;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetype;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetypes;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.event.ClickEvent;

/**
 * Full-window observer and transactional editor. Both reuse the terminal's open container.
 */
@SideOnly(Side.CLIENT)
public final class GuiCloudworksLine extends GuiMekanism<ContainerCloudworks> {
    private static final int SYNC_BUSY_COOLDOWN_TICKS = 40;
    private static final int PALETTE_MACHINES = 0;
    private static final int PALETTE_CHANNELS = 1;
    private static final int PALETTE_SPECIAL = 2;
    private static final int PALETTE_SUPPORT = 3;
    private static final Object NODE_WINDOW_KEY = new Object();
    private static final Object LINK_WINDOW_KEY = new Object();
    private final ContainerCloudworks container;
    private final InventoryPlayer inventory;
    private final UUID line;
    private final CloudworksLineEditorModel model = new CloudworksLineEditorModel();
    private final CloudworksViewport viewport = new CloudworksViewport();
    private final List<GuiElement> editControls = new ArrayList<>(), observerControls = new ArrayList<>();
    private final Map<Object, GuiWindow> managedWindows = new HashMap<>();
    private final Map<GuiElement, Class<? extends GuiWindow>> windowControls = new HashMap<>();
    private GuiLineCanvas canvas;
    private MekanismButton place, remove, confirm, reload;
    private GuiCloudSlotScroll paletteSlots;
    private final List<GuiLineResourceTypeTab> paletteTabs = new ArrayList<>();
    private MekanismButton paletteTypeButton;
    private MekanismButton paletteBlueprints;
    private GuiTextField paletteSearch;
    private GuiLinePalette paletteContent;
    private final List<String> machineCategoryIds = new ArrayList<>();
    private final Map<String, String> machineCategoryKeys = new LinkedHashMap<>();
    private List<Integer> visibleMachineIndices = Collections.emptyList();
    private List<Integer> visibleChannelIndices = Collections.emptyList();
    private String selectedMachineCategory = CloudworksMachinePalette.ALL;
    private String paletteQuery = "";
    private String cachedChannelQuery;
    private int channelFilter, cachedChannelFilter = -1, cachedChannelPage = -1, cachedChannelCount = -1;
    private int palettePage = PALETTE_MACHINES;
    private long cachedMachineRegistryVersion = Long.MIN_VALUE;
    private long paletteSlotsVersion;
    private boolean editing, pending, syncInFlight, loaded, requested, returnAfterCommit, suppressAutoEdit, fitPending = true;
    private boolean statePending;
    private boolean editStale, reloadConfirmed;
    private int syncBusyCooldown;
    private long editVersion, stateVersion;
    private int rightX, lastMachine = -1, lastChannel = -1;
    private long lastMachineClick, lastChannelClick;
    private String feedback = "";
    private byte[] acknowledgedDraft, sentDraft;
    private long cachedDraftBytesVersion = Long.MIN_VALUE;
    private CloudworksLineDraft cachedDraftObject;
    private byte[] cachedDraftBytes;
    private MekanismImageButton undo, redo, batchDelete, copySelection;
    private boolean diagnostics, deleteConfirmed, openNeedsAfterBegin;
    private Set<UUID> deleteSelection = Collections.emptySet();
    private long blueprintVersion;
    private Runnable afterSync;
    private boolean afterSyncDiscardsDraft;
    private CloudworksGuiData budgetSnapshot;

    public GuiCloudworksLine(ContainerCloudworks container, InventoryPlayer inventory, UUID lineId, String lineName) {
        super(container);
        this.container = container;
        this.inventory = inventory;
        line = lineId;
        editVersion = container.editResultVersion();
        blueprintVersion = container.blueprintVersion();
    }

    private String tr(String key) {
        return GuiLineWindow.tr(key);
    }

    private void resetDoubleClicks() {
        lastMachine = lastChannel = -1;
        lastMachineClick = lastChannelClick = 0;
    }

    @Override
    public void initGui() {
        xSize = width;
        ySize = height;
        rightX = xSize - 113;
        editControls.clear();
        observerControls.clear();
        windowControls.clear();
        super.initGui();
        refreshControls();
    }

    @Override
    protected void initPinnedWindows() {
        // Overview windows and their virtual slots do not belong to this full-window screen.
    }

    @Override
    public void onGuiClosed() {
        if (!switchingToJEI) model.allowClientActions(false);
        super.onGuiClosed();
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();
        int left = editing ? 28 : 5;
        int right = xSize - 5;
        canvas = addButton(new GuiLineCanvas(this, left, 28, Math.max(1, right - left), Math.max(1, ySize - 47),
                viewport, model, this::configuration, () -> container.guiData().graph,
                () -> editing && !pending && !container.blueprintBusy(),
                this::openNodeProperties, this::openLinkProperties, container::sampleTick));
        canvas.batchMove(this::moveSelection);
        canvas.diagnostics(diagnostics);
        icon(5, 5, "button/back.png", "back_cloud", this::exit);
        reload = addButton(new MekanismButton(this, rightX, 52, 106, 18,
                new TextComponentString(tr("reload_edit")), this::reloadDraft, null));
        editControls.add(windowIcon(5, 31, "button/rename.png", "identity", GuiLineIdentityWindow.class,
                () -> openWindow(GuiLineIdentityWindow.class, () -> new GuiLineIdentityWindow(this, model))));
        editControls.add(windowIcon(5, 52, "button/auto_toggle.png", "state_control",
                GuiLineStateWindow.class, this::openState));
        editControls.add(windowIcon(5, 73, "upgrade.png", "upgrades", GuiLineUpgradeWindow.class,
                () -> openWindow(GuiLineUpgradeWindow.class, () -> new GuiLineUpgradeWindow(this, model))));
        editControls.add(windowIcon(5, 94, "button/inventory.png", "warehouses", GuiCloudWarehousesWindow.class,
                () -> openWindow(GuiCloudWarehousesWindow.class,
                        () -> new GuiCloudWarehousesWindow(this, (xSize - 334) / 2, 8, container))));
        editControls.add(windowIcon(5, 115, "tabs/energy_info.png", "energy_gates", GuiLineEnergyWindow.class,
                () -> openWindow(GuiLineEnergyWindow.class,
                        () -> new GuiLineEnergyWindow(this, container, model))));
        observerControls.add(windowIcon(26, 5, "button/inventory.png", "warehouses", GuiCloudWarehousesWindow.class,
                () -> openWindow(GuiCloudWarehousesWindow.class,
                        () -> new GuiCloudWarehousesWindow(this, (xSize - 334) / 2, 8, container))));
        observerControls.add(windowIcon(47, 5, "upgrade.png", "upgrade_view", GuiLineUpgradeViewWindow.class,
                () -> openWindow(GuiLineUpgradeViewWindow.class,
                        () -> new GuiLineUpgradeViewWindow(this, container, line))));
        observerControls.add(windowIcon(68, 5, "button/auto_toggle.png", "state_control",
                GuiLineStateWindow.class, this::openState));
        observerControls.add(windowIcon(89, 5, "button/inventory.png", "machine_usage",
                GuiLineMachineUsageWindow.class, this::openMachineUsage));
        observerControls.add(windowIcon(110, 5, "button/inventory.png", "blueprint_library",
                GuiLineBlueprintWindow.class, this::openBlueprints));
        observerControls.add(icon(xSize - 44, 5, "button/exclamation.png", "diagnostics", () -> {
            diagnostics = !diagnostics;
            canvas.diagnostics(diagnostics);
        }));
        observerControls.add(icon(xSize - 23, 5, "button/rename.png", "edit_mode", this::beginEdit));
        for (int i = 0; i < 4; i++)
            observerControls.add(addButton(CloudworksLineGauges.gauge(this, container, i, xSize - 91 + i * 21, 33)));
        editControls.add(windowIcon(5, 136, "button/inventory.png", "palette_nodes", GuiLineNodePaletteWindow.class,
                () -> openWindow(GuiLineNodePaletteWindow.class, this::createPaletteWindow)));
        editControls.add(windowIcon(5, 157, "button/inventory.png", "machine_usage",
                GuiLineMachineUsageWindow.class, this::openMachineUsage));
        undo = icon(26, 5, "button/left.png", "undo", () -> history(true));
        redo = icon(47, 5, "button/right.png", "redo", () -> history(false));
        batchDelete = icon(68, 5, "button/clear_sides.png", "batch_delete", this::deleteSelected);
        copySelection = icon(89, 5, "button/encode_formula.png", "copy_blueprint",
                () -> export(new LinkedHashSet<>(model.selection)));
        editControls.addAll(Arrays.asList(undo, redo, batchDelete, copySelection));
        confirm = addButton(new MekanismButton(this, rightX, 5, 106, 18,
                new TextComponentString(tr("confirm_edit")), () -> submit(false), null));
        editControls.add(confirm);
    }

    private GuiWindow createPaletteWindow() {
        GuiLineNodePaletteWindow window = new GuiLineNodePaletteWindow(this, xSize - 132);
        int x = window.getRelativeX() + GuiLineNodePaletteWindow.CONTENT_X;
        int y = window.getRelativeY();
        String[] textures = {"button/inventory.png", "items.png", "button/fuzzy.png", "upgrade.png"};
        String[] labels = {"palette_machines", "palette_channels", "palette_special", "palette_support"};
        int[] colors = {0xFFE0E0E0, 0xFF7CCC90, 0xFFE0C477, 0xFF78B1E0};
        int[] pages = {PALETTE_MACHINES, PALETTE_CHANNELS, PALETTE_SPECIAL, PALETTE_SUPPORT};
        paletteTabs.clear();
        for (int index = 0; index < labels.length; index++) {
            final int page = pages[index];
            paletteTabs.add(window.control(new GuiLineResourceTypeTab(this, window.getRelativeX() - 26,
                    y + 2 + index * 28, textures[index], labels[index], colors[index],
                    () -> selectPalettePage(page)).selectedWhen(() -> palettePage == page)));
        }
        paletteTypeButton = window.control(new MekanismButton(this, x + 54, y + GuiLineNodePaletteWindow.TYPE_Y,
                54, 18, new TextComponentString(tr("palette_all")), () -> cyclePaletteType(1),
                () -> cyclePaletteType(-1), null));
        paletteSearch = window.control(new GuiTextField(this, window, x,
                y + GuiLineNodePaletteWindow.SEARCH_Y, 108, 16) {
            @Override
            public void renderToolTip(int mouseX, int mouseY) {
                super.renderToolTip(mouseX, mouseY);
                displayTooltip(new TextComponentString(tr("palette_search")), mouseX, mouseY);
            }
        }.setMaxLength(128));
        paletteSearch.setText(paletteQuery);
        paletteSearch.setResponder(this::searchPalette);
        int gridY = y + GuiLineNodePaletteWindow.GRID_Y;
        paletteContent = window.control(new GuiLinePalette(this, x, gridY,
                window.getHeight() - GuiLineNodePaletteWindow.GRID_Y - 7));
        paletteSlots = paletteContent.control(new GuiCloudSlotScroll(this, x, gridY,
                GuiLineNodePaletteWindow.GRID_COLUMNS, GuiLineNodePaletteWindow.GRID_ROWS,
                this::paletteSlots, this::selectPaletteSlot, this::selectedPaletteIndex, this::paletteSlotTooltip)
                .cacheByVersion(this::paletteSlotVersion).enabledSlots(this::paletteSlotEnabled));
        place = paletteContent.control(new MekanismButton(this, x + 1, gridY + 111, 106, 18,
                new TextComponentString(tr("place_node")), this::place, null));
        remove = paletteContent.control(new MekanismButton(this, x + 1, gridY + 132, 106, 18,
                new TextComponentString(tr("unload_node")), () -> model.remove(isShiftKeyDown()), null));
        paletteBlueprints = paletteContent.control(new MekanismButton(this, x + 1, gridY + 153, 106, 18,
                new TextComponentString(tr("blueprint_library")), this::openBlueprints, null));
        paletteContent.control(new MekanismButton(this, x + 1, gridY + 174, 106, 18,
                new TextComponentString(tr("blueprint_export")), () -> export(Collections.emptySet()), null));
        paletteContent.finish();
        refreshControls();
        return window;
    }

    private String channelLabel(int index) {
        if (index < 0 || index >= model.channelCount()) return "";
        String archetype = model.channelArchetype(index);
        if (!archetype.isEmpty()) {
            CloudworksSpecialNodeArchetype value =
                    CloudworksSpecialNodeArchetypes.get(archetype);
            if (value == null) return tr("special_node_invalid");
            return LangUtils.localize(value.translationKey());
        }
        return tr("channel_" + model.channelMode(index).name().toLowerCase(Locale.ROOT));
    }

    private MekanismImageButton icon(int x, int y, String texture, String title, Runnable action) {
        return addButton(new MekanismImageButton(this, x, y, 18,
                new ResourceLocation("mekanism", "gui/" + texture), action).setTooltip(tr(title)));
    }

    private MekanismImageButton windowIcon(int x, int y, String texture, String title,
                                           Class<? extends GuiWindow> windowType, Runnable action) {
        MekanismImageButton button = icon(x, y, texture, title, action);
        windowControls.put(button, windowType);
        return button;
    }

    private boolean windowControlActive(boolean ready, Class<? extends GuiWindow> windowType) {
        return ready && !getWindows().contains(managedWindows.get(windowType));
    }

    private CloudworksLineDraft configuration() {
        return editing ? model.draft : line.equals(container.guiData().graph.id) ? container.guiData().graph.configuration : null;
    }

    private void beginEdit() {
        if (pending || editing || !loaded || statePending) return;
        pending = true;
        feedback = tr("loading_edit");
        container.requestLineEdit(PacketCloudworksLineEdit.BEGIN, line, null);
        refreshControls();
    }

    private byte[] draftBytes(CloudworksLineDraft draft) {
        if (draft != null && draft == model.draft && draft == cachedDraftObject
                && cachedDraftBytesVersion == model.draftVersion()
                && cachedDraftBytes != null) {
            return cachedDraftBytes;
        }
        CloudworksLineDraft copy = CloudworksLineDraftCodec.copy(draft);
        copy.machines.forEach(machine -> machine.missing = 0);
        byte[] encoded = CloudworksLineDraftCodec.encode(copy);
        if (draft == model.draft) {
            cachedDraftObject = draft;
            cachedDraftBytesVersion = model.draftVersion();
            cachedDraftBytes = encoded;
        }
        return encoded;
    }

    private boolean syncDraft() {
        if (!editing || editStale || syncBusyCooldown > 0 || pending || syncInFlight
                || container.blueprintBusy() || model.draft == null
                || canvas.interacting() || canvas.hasGhost()) return false;
        try {
            byte[] bytes = draftBytes(model.draft);
            if (Arrays.equals(bytes, acknowledgedDraft)) return false;
            sentDraft = bytes;
            container.requestLineEdit(PacketCloudworksLineEdit.SYNC, line, model.draft);
            syncInFlight = true;
            return true;
        } catch (RuntimeException failure) {
            feedback = tr("draft_too_large");
            return false;
        }
    }

    private void flushThen(Runnable action) {
        if (pending || container.blueprintBusy() || afterSync != null) return;
        afterSync = action;
        afterSyncDiscardsDraft = false;
        runAfterSyncIfReady();
    }

    private void runAfterSyncIfReady() {
        if (afterSync == null || pending || syncInFlight || container.blueprintBusy()
                || canvas.interacting() || canvas.hasGhost()) return;
        if (!afterSyncDiscardsDraft) {
            boolean changed;
            try {
                changed = !Arrays.equals(draftBytes(model.draft), acknowledgedDraft);
            } catch (RuntimeException failure) {
                feedback = tr("draft_too_large");
                if (!afterSyncDiscardsDraft) afterSync = null;
                return;
            }
            if (changed) {
                syncDraft();
                return;
            }
        }
        Runnable action = afterSync;
        afterSync = null;
        afterSyncDiscardsDraft = false;
        action.run();
    }

    private void history(boolean back) {
        if (!editing || pending || container.blueprintBusy() || canvas.hasGhost()) return;
        closeDraftWindows();
        boolean changed = back ? model.undo() : model.redo();
        if (changed) {
            deleteConfirmed = false;
            feedback = "";
            syncDraft();
        }
        refreshControls();
    }

    private void moveSelection(int dx, int dy) {
        if (!editing || pending || container.blueprintBusy()) return;
        if (model.moveSelection(dx, dy)) syncDraft();
    }

    private void deleteSelected() {
        if (model.selection.isEmpty()) return;
        if (!deleteConfirmed || !deleteSelection.equals(model.selection)) {
            deleteSelection = new LinkedHashSet<>(model.selection);
            deleteConfirmed = true;
            boolean waiting = model.draft.machines.stream().anyMatch(machine ->
                    model.selection.contains(machine.id) && machine.waiting != null)
                    || model.draft.channels.stream().anyMatch(channel ->
                    model.selection.contains(channel.id) && channel.waiting != null);
            feedback = tr(waiting ? "batch_delete_pending_confirm" : "confirm_delete");
            return;
        }
        deleteConfirmed = false;
        if (model.removeSelection()) syncDraft();
    }

    private void export(Set<UUID> nodes) {
        flushThen(() -> container.requestBlueprint(PacketCloudworksBlueprint.EXPORT, line, null, "", null, 0, 0, nodes));
    }

    private void openBlueprints() {
        if (!editing) {
            if (pending || !loaded || container.blueprintBusy()) return;
            openWindow(GuiLineBlueprintWindow.class,
                    () -> new GuiLineBlueprintWindow(this, container, line, false, this::startGhost));
            return;
        }
        flushThen(() -> openWindow(GuiLineBlueprintWindow.class,
                () -> new GuiLineBlueprintWindow(this, container, line, true, this::startGhost)));
    }

    private void startGhost(CloudworksBlueprint blueprint) {
        CloudworksLineDraft group = blueprint.draft();
        feedback = LangUtils.localizeWithFormat("cloudworks.editor.blueprint_summary",
                group.name, group.machines.size() + group.channels.size(), group.links.size(), group.channels.size());
        canvas.ghost(blueprint, (x, y) -> {
            closeDraftWindows();
            container.requestBlueprint(PacketCloudworksBlueprint.PLACE, line, null, "", blueprint, x, y, Collections.emptySet());
        });
    }

    private void submit(boolean returnToCloud) {
        if (!editing || pending || container.blueprintBusy() || canvas.hasGhost()) return;
        if (syncInFlight || draftChanged()) {
            flushThen(() -> submit(returnToCloud));
            return;
        }
        submitNow(returnToCloud);
    }

    private boolean draftChanged() {
        try {
            return !Arrays.equals(draftBytes(model.draft), acknowledgedDraft);
        } catch (RuntimeException failure) {
            feedback = tr("draft_too_large");
            return true;
        }
    }

    private void submitNow(boolean returnToCloud) {
        if (!editing || pending || container.blueprintBusy() || canvas.hasGhost()) return;
        closeDraftWindows();
        canvas.release(-1, -1);
        returnAfterCommit = returnToCloud;
        pending = true;
        feedback = tr("saving");
        container.requestLineEdit(PacketCloudworksLineEdit.COMMIT, line, model.draft);
        refreshControls();
    }

    private void reloadDraft() {
        if (!editing || !editStale || pending) return;
        if (!reloadConfirmed) {
            reloadConfirmed = true;
            reload.setMessage(new TextComponentString(tr("confirm_reload")));
            feedback = tr("reload_discards_draft");
            return;
        }
        closeDraftWindows();
        canvas.release(-1, -1);
        Runnable reload = () -> {
            if (!editing || !editStale || pending) return;
            pending = true;
            reloadConfirmed = false;
            feedback = tr("loading_edit");
            container.requestLineEdit(PacketCloudworksLineEdit.RELOAD, line, null);
            refreshControls();
        };
        if (syncInFlight) {
            afterSync = reload;
            afterSyncDiscardsDraft = true;
        } else reload.run();
    }

    private void exit() {
        if (pending) return;
        if (editing) submit(true);
        else backToCloud();
    }

    private void backToCloud() {
        closeWindows();
        managedWindows.clear();
        container.selectCacheView(-2);
        container.sendAction(Operation.CACHE_VIEW, null, "-1");
        mc.displayGuiScreen(new GuiCloudworksCloud(container, inventory));
    }

    /** On-demand catalogue switch: only the node-upgrade window needs the full module catalogue. */
    public void setCatalogRequested(boolean enabled) {
        container.sendAction(Operation.CATALOG_VIEW, null, enabled ? "1" : "0");
    }

    private void closeWindows() {
        List<GuiWindow> open = new ArrayList<>();
        getWindows().forEach(open::add);
        open.forEach(GuiWindow::close);
        managedWindows.clear();
    }

    private void closeDraftWindows() {
        List<GuiWindow> open = new ArrayList<>();
        for (GuiWindow window : getWindows()) {
            if (window instanceof GuiLineIdentityWindow || window instanceof GuiLineUpgradeWindow
                    || window instanceof GuiLineEnergyWindow || window instanceof GuiLineChannelWindow
                    || window instanceof GuiLineInfiniteResourceWindow
                    || window instanceof GuiLineLinkWindow || window instanceof GuiLineNodeUpgradeWindow
                    || window instanceof GuiLineMachineWindow || window instanceof GuiLineBlueprintNeedsWindow
                    || window instanceof GuiLineNodePaletteWindow) {
                open.add(window);
            }
        }
        GuiWindow nodeWindow = managedWindows.get(NODE_WINDOW_KEY);
        if (nodeWindow != null && !open.contains(nodeWindow)) open.add(nodeWindow);
        GuiWindow linkWindow = managedWindows.get(LINK_WINDOW_KEY);
        if (linkWindow != null && !open.contains(linkWindow)) open.add(linkWindow);
        open.forEach(GuiWindow::close);
        managedWindows.entrySet().removeIf(entry -> !getWindows().contains(entry.getValue()));
    }

    private void openWindow(Object key, Supplier<GuiWindow> factory) {
        if (pending) return;
        managedWindows.entrySet().removeIf(entry -> !getWindows().contains(entry.getValue()));
        GuiWindow existing = managedWindows.get(key);
        if (existing != null) {
            if (existing instanceof GuiLineBlueprintWindow blueprintWindow) {
                blueprintWindow.setCanPlace(editing && !pending);
            }
            focusWindow(existing);
            return;
        }
        GuiWindow created = factory.get();
        if (created == null) return;
        managedWindows.put(key, created);
        addWindow(created);
    }

    private void replaceWindow(Object key, Supplier<GuiWindow> factory) {
        closeManagedWindow(key);
        openWindow(key, factory);
    }

    private void closeManagedWindow(Object key) {
        GuiWindow window = managedWindows.remove(key);
        if (window != null && getWindows().contains(window)) window.close();
    }

    @Override
    public void addWindow(GuiWindow window) {
        super.addWindow(window);
        refreshControls();
    }

    @Override
    public void removeWindow(GuiWindow window) {
        super.removeWindow(window);
        managedWindows.entrySet().removeIf(entry -> entry.getValue() == window);
        refreshControls();
    }

    private void openMachineUsage() {
        openWindow(GuiLineMachineUsageWindow.class,
                () -> new GuiLineMachineUsageWindow(this, this::configuration,
                        model::machineUsageVersion, this::focusNode));
    }

    private void openState() {
        if (!loaded) return;
        openWindow(GuiLineStateWindow.class, () -> new GuiLineStateWindow(this,
                () -> editing ? model.draft.enabled : container.guiData().graph.enabled,
                () -> !pending && !statePending && configuration() != null,
                enabled -> {
                    if (editing) {
                        model.setEnabled(enabled);
                    } else {
                        statePending = true;
                        stateVersion = container.viewVersion();
                        container.sendAction(enabled ? Operation.START : Operation.STOP, line, "");
                    }
                }, () -> container.guiData().overview.offlineUnlocked,
                () -> container.guiData().overview.offlineProcessingEnabled,
                () -> configuration() != null && configuration().runWhileOwnerOffline,
                enabled -> {
                    if (editing) model.setOffline(enabled);
                    else {
                        statePending = true;
                        stateVersion = container.viewVersion();
                        container.sendAction(Operation.LINE_OFFLINE, line, enabled ? "1" : "0");
                    }
                },
                () -> !editing && mc.player != null
                        && mc.player.getHeldItemMainhand().getItem()
                        == CloudworksItems.LineCapacityCard,
                () -> configuration() == null ? 1 : configuration().nodeBufferMultiplier,
                () -> container.sendAction(Operation.LINE_BUFFER_CAPACITY, line, "")));
    }

    private void focusNode(UUID node) {
        canvas.focusNode(node);
    }

    private void openNodeProperties(UUID nodeId) {
        if (pending) return;
        model.selectedNode = nodeId;
        model.selectedWire = null;
        canvas.release(-1, -1);
        CloudworksLineDraft config = configuration();
        boolean waiting = config != null && (config.machines.stream().anyMatch(machine ->
                machine.id.equals(nodeId) && machine.waiting != null)
                || config.channels.stream().anyMatch(channel ->
                channel.id.equals(nodeId) && channel.waiting != null));
        if (waiting) {
            if (!editing) {
                openNeedsAfterBegin = true;
                beginEdit();
            } else flushThen(() -> openWindow(GuiLineBlueprintNeedsWindow.class,
                    () -> new GuiLineBlueprintNeedsWindow(this, container, line)));
            return;
        }
        if (!loaded || config == null) return;
        Supplier<GuiWindow> window = CloudworksLineWindowFactory.node(this, container, model,
                line, config, nodeId, editing);
        if (window != null) replaceWindow(NODE_WINDOW_KEY, window);
    }

    private void openLinkProperties(UUID linkId) {
        if (pending || !editing) return;
        model.selectedWire = linkId;
        canvas.release(-1, -1);
        Supplier<GuiWindow> window = CloudworksLineWindowFactory.link(this, container, model,
                model.selectedLink());
        if (window != null) replaceWindow(LINK_WINDOW_KEY, window);
    }

    private List<IScrollableSlot> paletteSlots() {
        refreshMachineCategories();
        refreshChannelIndices();
        List<IScrollableSlot> slots = new ArrayList<>();
        if (palettePage == PALETTE_MACHINES) {
            for (int index : visibleMachineIndices)
                slots.add(GuiCloudSlotScroll.slot(model.machines.get(index).stack, model.available(index)));
        } else if (palettePage == PALETTE_SUPPORT) {
            for (int index : supportMachineIndices())
                slots.add(GuiCloudSlotScroll.slot(model.machines.get(index).stack, model.available(index)));
        } else if (channelPalettePage()) {
            for (int index : visibleChannelIndices)
                slots.add(GuiCloudSlotScroll.slot(CloudworksChannelIcons.stack(
                        model.channelArchetype(index), model.channelMode(index)), 1));
        }
        return slots;
    }

    private void selectPalettePage(int page) {
        if (page < PALETTE_MACHINES || page > PALETTE_SUPPORT || page == palettePage) return;
        palettePage = page;
        model.machineSelection = model.channelSelection = -1;
        resetDoubleClicks();
        refreshChannelIndices();
        resetPaletteScroll();
        refreshControls();
    }

    private void searchPalette(String text) {
        String query = CloudworksMachinePalette.normalizeQuery(text);
        if (query.equals(paletteQuery)) return;
        paletteQuery = query;
        rebuildVisibleMachines();
        refreshChannelIndices();
        resetDoubleClicks();
        resetPaletteScroll();
        refreshControls();
    }

    private void resetPaletteScroll() {
        if (paletteSlots != null) paletteSlots.resetScroll();
        if (paletteContent != null) paletteContent.resetScroll();
    }

    private void refreshMachineCategories() {
        long version = model.machineListVersion();
        if (version == cachedMachineRegistryVersion) return;
        cachedMachineRegistryVersion = version;
        rebuildVisibleMachines();
    }

    private void rebuildVisibleMachines() {
        CloudworksMachinePalette projection = CloudworksMachinePalette.project(
                model.machines, selectedMachineCategory, paletteQuery);
        machineCategoryIds.clear();
        machineCategoryIds.addAll(projection.categories.keySet());
        machineCategoryKeys.clear();
        machineCategoryKeys.putAll(projection.categories);
        selectedMachineCategory = projection.selectedCategory;
        visibleMachineIndices = projection.indices;
        if (!visibleMachineIndices.contains(model.machineSelection)) model.machineSelection = -1;
        paletteSlotsVersion++;
    }

    private void cyclePaletteType(int direction) {
        refreshMachineCategories();
        if (palettePage == PALETTE_MACHINES) {
            int current = machineCategoryIds.indexOf(selectedMachineCategory);
            selectedMachineCategory = machineCategoryIds.get(Math.floorMod(current + direction, machineCategoryIds.size()));
            model.machineSelection = -1;
            rebuildVisibleMachines();
        } else if (palettePage == PALETTE_CHANNELS) {
            channelFilter = Math.floorMod(channelFilter + direction, 3);
            model.channelSelection = -1;
            refreshChannelIndices();
        } else {
            return;
        }
        resetDoubleClicks();
        resetPaletteScroll();
        refreshControls();
    }

    private void refreshChannelIndices() {
        int count = model.channelCount();
        if (cachedChannelCount == count && cachedChannelPage == palettePage
                && cachedChannelFilter == channelFilter && Objects.equals(cachedChannelQuery, paletteQuery)) return;
        cachedChannelCount = count;
        cachedChannelPage = palettePage;
        cachedChannelFilter = channelFilter;
        cachedChannelQuery = paletteQuery;
        List<Integer> indices = new ArrayList<>();
        if (channelPalettePage())
            for (int index : model.channelPaletteIndices(palettePage == PALETTE_SPECIAL, channelFilter)) {
                if (CloudworksMachinePalette.matchesSearch(paletteQuery, channelLabel(index), model.channelArchetype(index)))
                    indices.add(index);
            }
        visibleChannelIndices = indices;
        if (!indices.contains(model.channelSelection)) model.channelSelection = -1;
        paletteSlotsVersion++;
    }

    private long paletteSlotVersion() {
        refreshMachineCategories();
        refreshChannelIndices();
        return paletteSlotsVersion;
    }

    private int machineIndexAt(int visible) {
        return visible >= 0 && visible < visibleMachineIndices.size() ? visibleMachineIndices.get(visible) : -1;
    }

    private int channelIndexAt(int visible) {
        return visible >= 0 && visible < visibleChannelIndices.size() ? visibleChannelIndices.get(visible) : -1;
    }

    private int selectedPaletteIndex() {
        if (palettePage == PALETTE_MACHINES) return visibleMachineIndices.indexOf(model.machineSelection);
        if (palettePage == PALETTE_SUPPORT) return supportMachineIndices().indexOf(model.machineSelection);
        if (channelPalettePage()) return visibleChannelIndices.indexOf(model.channelSelection);
        return -1;
    }

    private boolean paletteSlotEnabled(int index) {
        if (palettePage == PALETTE_MACHINES) return model.canPlaceMachine(machineIndexAt(index));
        if (palettePage == PALETTE_SUPPORT) {
            List<Integer> support = supportMachineIndices();
            return index >= 0 && index < support.size() && model.canPlaceAuxiliary(support.get(index));
        }
        return channelPalettePage() && model.channelAvailable(channelIndexAt(index));
    }

    private void selectPaletteSlot(int visible) {
        if (palettePage == PALETTE_MACHINES) {
            int index = machineIndexAt(visible);
            if (index >= 0) selectMachine(index);
        } else if (palettePage == PALETTE_SUPPORT) {
            List<Integer> support = supportMachineIndices();
            if (visible >= 0 && visible < support.size()) selectMachine(support.get(visible));
        } else if (channelPalettePage()) {
            int index = channelIndexAt(visible);
            if (index >= 0) selectChannel(index);
        }
    }

    private List<String> paletteSlotTooltip(int index) {
        if (palettePage == PALETTE_MACHINES)
            return Collections.singletonList(tr("available") + ": " + model.available(machineIndexAt(index)));
        if (palettePage == PALETTE_SUPPORT) {
            List<Integer> support = supportMachineIndices();
            return index >= 0 && index < support.size()
                    ? Collections.singletonList(tr("available") + ": " + model.available(support.get(index)))
                    : Collections.emptyList();
        }
        if (channelPalettePage())
            return Collections.singletonList(channelLabel(channelIndexAt(index)));
        return Collections.emptyList();
    }

    private String paletteTypeLabel() {
        if (palettePage == PALETTE_MACHINES)
            return LangUtils.localize(machineCategoryKeys.getOrDefault(selectedMachineCategory,
                    "cloudworks.editor.palette_all"));
        if (palettePage == PALETTE_SUPPORT) return tr("palette_support");
        if (palettePage == PALETTE_CHANNELS && channelFilter != 0)
            return tr(channelFilter == 1 ? "palette_inputs" : "palette_outputs");
        return tr("palette_all");
    }

    private boolean channelPalettePage() {
        return palettePage == PALETTE_CHANNELS || palettePage == PALETTE_SPECIAL;
    }

    private List<Integer> supportMachineIndices() {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < model.machines.size(); index++)
            if ("cloudworks:support".equals(model.machines.get(index).categoryId)
                    && CloudworksMachinePalette.matchesSearch(paletteQuery,
                    model.machines.get(index).stack.getDisplayName(),
                    model.machines.get(index).stack.getItem().getRegistryName() == null ? ""
                            : model.machines.get(index).stack.getItem().getRegistryName().toString()))
                result.add(index);
        return result;
    }

    private void selectMachine(int index) {
        if (!editing || pending) return;
        model.machineSelection = index;
        model.channelSelection = -1;
        long now = System.currentTimeMillis();
        if (lastMachine == index && now - lastMachineClick < 350) {
            model.placeMachine(canvas.centerX(), canvas.centerY());
            lastMachine = -1;
        } else lastMachine = index;
        lastMachineClick = now;
    }

    private boolean adjustSelectedMachine(int wheel) {
        return editing && !pending && model.adjustSelectedMachine(wheel > 0 ? 1 : -1);
    }

    private void selectChannel(int index) {
        if (!editing || pending) return;
        model.channelSelection = index;
        model.machineSelection = -1;
        long now = System.currentTimeMillis();
        if (lastChannel == index && now - lastChannelClick < 350) {
            model.placeChannel(canvas.centerX(), canvas.centerY());
            lastChannel = -1;
        } else lastChannel = index;
        lastChannelClick = now;
    }

    private void place() {
        if (model.channelSelection >= 0) model.placeChannel(canvas.centerX(), canvas.centerY());
        else if (palettePage == PALETTE_SUPPORT) model.placeAuxiliary(canvas.centerX(), canvas.centerY());
        else model.placeMachine(canvas.centerX(), canvas.centerY());
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (syncBusyCooldown > 0) syncBusyCooldown--;
        int timedOut = container.expireTimedOutRequests();
        if ((timedOut & ContainerCloudworks.TIMED_OUT_EDIT) != 0) {
            if (syncInFlight) {
                syncInFlight = false;
                feedback = tr("request_timeout");
            } else {
                pending = false;
                if (!afterSyncDiscardsDraft) afterSync = null;
                feedback = tr("request_timeout");
            }
        }
        if ((timedOut & ContainerCloudworks.TIMED_OUT_BLUEPRINT) != 0) feedback = tr("request_timeout");
        if (!requested && container.session() != null) {
            container.sendEditorAction(Operation.VIEW_LINE, line, null, "");
            requested = true;
        }
        if (line.equals(container.guiData().graph.id) && container.guiData().graph.configuration != null) {
            loaded = true;
            if (editing && model.draft != null) {
                if (budgetSnapshot != container.guiData()) {
                    budgetSnapshot = container.guiData();
                    model.refreshBudget(budgetSnapshot.graph.configuration, budgetSnapshot);
                }
                Map<UUID, Long> deficits = new HashMap<>();
                container.guiData().graph.configuration.machines.forEach(machine -> deficits.put(machine.id, machine.missing));
                for (CloudworksLineDraft.Machine machine : model.draft.machines)
                    if (machine.waiting != null && deficits.containsKey(machine.id))
                        machine.missing = deficits.get(machine.id);
            }
            if (fitPending) {
                canvas.fit();
                fitPending = false;
            }
            if (!editing && !pending && !suppressAutoEdit && container.guiData().graph.configuration.machines.isEmpty())
                beginEdit();
        }
        if (container.editResultVersion() != editVersion) {
            editVersion = container.editResultVersion();
            boolean syncResponse = container.editResultOperation == PacketCloudworksLineEdit.SYNC;
            if (syncResponse) syncInFlight = false;
            else pending = false;
            if (!container.editError.isEmpty()) {
                feedback = LangUtils.localize(container.editError);
                suppressAutoEdit = true;
                if ("cloudworks.error.stale".equals(container.editError) && editing) editStale = true;
                if (syncResponse && "cloudworks.error.busy".equals(container.editError))
                    syncBusyCooldown = SYNC_BUSY_COOLDOWN_TICKS;
                if (editing && container.editSnapshot != null) {
                    closeDraftWindows();
                    model.accept(container.editSnapshot);
                    acknowledgedDraft = draftBytes(model.draft);
                }
                if (!afterSyncDiscardsDraft) afterSync = null;
            } else if ((container.editResultOperation == PacketCloudworksLineEdit.BEGIN
                    || container.editResultOperation == PacketCloudworksLineEdit.RELOAD) && container.editSnapshot != null) {
                closeDraftWindows();
                model.begin(container.editSnapshot, container.guiData());
                resetDoubleClicks();
                budgetSnapshot = null;
                acknowledgedDraft = draftBytes(model.draft);
                editing = true;
                editStale = reloadConfirmed = false;
                syncBusyCooldown = 0;
                feedback = "";
                setWorldAndResolution(mc, width, height);
                if (openNeedsAfterBegin) {
                    openNeedsAfterBegin = false;
                    openWindow(GuiLineBlueprintNeedsWindow.class,
                            () -> new GuiLineBlueprintNeedsWindow(this, container, line));
                }
            } else if (container.editResultOperation == PacketCloudworksLineEdit.COMMIT) {
                resetDoubleClicks();
                model.clearHistory();
                editing = false;
                editStale = reloadConfirmed = false;
                syncBusyCooldown = 0;
                suppressAutoEdit = true;
                feedback = tr("saved");
                if (returnAfterCommit) {
                    backToCloud();
                    return;
                }
                setWorldAndResolution(mc, width, height);
            } else if (container.editSnapshot != null) {
                CloudworksLineDraft snapshot = container.editSnapshot;
                byte[] authoritative = draftBytes(snapshot);
                if (syncResponse) {
                    boolean localStillMatchesSent = model.draft != null && Arrays.equals(draftBytes(model.draft), sentDraft);
                    if (localStillMatchesSent && !Arrays.equals(authoritative, sentDraft)) {
                        closeDraftWindows();
                        model.accept(snapshot);
                    }
                } else {
                    closeDraftWindows();
                    model.accept(snapshot);
                }
                acknowledgedDraft = authoritative;
                feedback = "";
            }
        }
        if (blueprintVersion != container.blueprintVersion()) {
            blueprintVersion = container.blueprintVersion();
            PacketCloudworksBlueprint.Result result = container.blueprintResult;
            if (result.operation == PacketCloudworksBlueprint.PLACE
                    || result.operation == PacketCloudworksBlueprint.FILL
                    || result.operation == PacketCloudworksBlueprint.CANCEL) model.clearHistory();
            if (!result.error.isEmpty()) feedback = LangUtils.localize(result.error);
            else {
                if (!result.code.isEmpty()) {
                    GuiScreen.setClipboardString(result.code);
                    // One clickable component retains the complete code despite the chat line limit.
                    TextComponentString message = new TextComponentString(tr("blueprint_copied"));
                    message.getStyle().setClickEvent(new ClickEvent(
                            ClickEvent.Action.SUGGEST_COMMAND, result.code));
                    message.appendText(" " + result.code);
                    inventory.player.sendMessage(message);
                    feedback = tr("blueprint_copied");
                }
                if (result.draft != null) {
                    model.accept(result.draft);
                    acknowledgedDraft = draftBytes(model.draft);
                    if (result.operation == PacketCloudworksBlueprint.PLACE)
                        feedback = tr(result.filled ? "blueprint_placed" : "blueprint_waiting");
                }
            }
        }
        if (statePending && container.viewVersion() > stateVersion) {
            statePending = false;
            feedback = container.status().startsWith("cloudworks.error.")
                    ? LangUtils.localize(container.status()) : "";
        }
        String modelNotice = model.takeNotice();
        if (!modelNotice.isEmpty()) feedback = LangUtils.localize(modelNotice);
        refreshControls();
        if (editing) {
            // A queued explicit reload discards the stale draft and remains valid
            // even when ordinary synchronization has been stopped.
            if (afterSyncDiscardsDraft) runAfterSyncIfReady();
            if (!editStale && syncBusyCooldown == 0) {
                runAfterSyncIfReady();
                if (afterSync == null || !syncInFlight) syncDraft();
            }
        }
    }

    private void refreshControls() {
        model.allowClientActions(editing && !pending && !editStale
                && !container.blueprintBusy() && (canvas == null || !canvas.hasGhost()));
        refreshMachineCategories();
        refreshChannelIndices();
        if (reload != null) {
            reload.visible = editing && editStale;
            reload.active = reload.visible && !pending;
        }
        for (GuiElement control : editControls) {
            control.visible = editing;
            control.active = editing && !pending && !container.blueprintBusy() && !canvas.hasGhost();
        }
        for (GuiElement control : observerControls) {
            control.visible = !editing;
            control.active = loaded && !pending && !statePending;
        }
        windowControls.forEach((control, windowType) ->
                control.active = windowControlActive(control.active, windowType));
        if (paletteSlots != null) {
            boolean ready = editing && !pending && !container.blueprintBusy() && !canvas.hasGhost();
            paletteSlots.active = paletteContent.active = ready;
            for (GuiLineResourceTypeTab tab : paletteTabs) {
                tab.visible = true;
                tab.active = ready;
            }
            paletteTypeButton.active = ready && (palettePage == PALETTE_MACHINES || palettePage == PALETTE_CHANNELS);
            paletteTypeButton.setMessage(new TextComponentString(paletteTypeLabel()));
            paletteSearch.active = ready;
            paletteSearch.setEditable(ready);
            paletteBlueprints.active = windowControlActive(ready, GuiLineBlueprintWindow.class);
        }
        for (GuiWindow window : getWindows()) {
            if (window instanceof GuiLineBlueprintWindow blueprintWindow) {
                blueprintWindow.setCanPlace(editing && !pending);
            }
        }
        if (place != null) {
            place.active = editing && !pending && !container.blueprintBusy() && !canvas.hasGhost() && (model.channelSelection >= 0
                    ? model.channelAvailable(model.channelSelection) : model.canPlaceMachine(model.machineSelection));
            remove.active = editing && !pending && !container.blueprintBusy() && !canvas.hasGhost() && model.selectedNode != null;
        }
        if (undo != null && model.draft != null) {
            undo.active = editing && !pending && !container.blueprintBusy() && model.canUndo();
            redo.active = editing && !pending && !container.blueprintBusy() && model.canRedo();
            batchDelete.active &= !model.selection.isEmpty();
            copySelection.active &= !model.selection.isEmpty();
        }
    }

    @Override
    protected void drawForegroundText(int mouseX, int mouseY) {
        int titleMargin = editing ? 113 : 134;
        drawScaledScrollingString(new TextComponentString(tr("title_line")), titleMargin, 9, TextAlignment.CENTER,
                0x404040, Math.max(1, xSize - titleMargin * 2), 3, false, 1, getTimeOpened());
        drawScaledScrollingString(new TextComponentString(feedback), 6, ySize - 12, TextAlignment.LEFT,
                0x404040, Math.max(1, rightX - 12), 3, false, 1, getTimeOpened());
        if (!editing && loaded && line.equals(container.guiData().graph.id)) {
            int clockTick = container.guiData().overview.clockTick;
            drawScrollingString(new TextComponentString(tr("time_" + CloudworksGenerationContext
                            .timeOfDay(clockTick))), xSize - 91, 98,
                    TextAlignment.CENTER, 0xFFFFFF, 86, 2, false);
            drawScrollingString(new TextComponentString(CloudworksGenerationContext
                            .clockTime(clockTick)), xSize - 91, 110,
                    TextAlignment.CENTER, 0xFFFFFF, 86, 2, false);
        }
    }

    private boolean overCanvas(int x, int y) {
        if (!canvas.isMouseOverCheckWindows(x, y)) return false;
        if (!editing && x >= guiLeft + xSize - 91 && y >= guiTop + 33 && y < guiTop + 121) return false;
        return true;
    }

    @Override
    public void mouseClicked(int x, int y, int button) throws IOException {
        super.mouseClicked(x, y, button);
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long elapsed) {
        super.mouseClickMove(x, y, button, elapsed);
    }

    @Override
    public void mouseReleased(int x, int y, int button) {
        super.mouseReleased(x, y, button);
    }

    @Override
    public void handleMouseInput() throws IOException {
        int x = Mouse.getEventX() * width / mc.displayWidth;
        int y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && editing && isShiftKeyDown() && palettePage == PALETTE_MACHINES && paletteSlots != null
                && getWindows().contains(managedWindows.get(GuiLineNodePaletteWindow.class))
                && paletteContent.isMouseOverCheckWindows(x, y)
                && paletteSlots.isMouseOverCheckWindows(x, y)) {
            adjustSelectedMachine(wheel);
        } else if (wheel != 0 && overCanvas(x, y)) canvas.zoom(wheel, x, y);
        else super.handleMouseInput();
    }

    @Override
    public void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE) {
            if (!getWindows().isEmpty()) {
                getWindows().iterator().next().close();
            } else if (canvas.cancelGhost()) {
                feedback = "";
            } else if (!pending && !container.blueprintBusy()) {
                if (editing) submit(false);
                else backToCloud();
            }
            return;
        }
        if (key == Keyboard.KEY_DELETE && editing && !pending && !container.blueprintBusy()
                && !canvas.hasGhost() && getWindows().isEmpty()) {
            if (model.selectedWire != null) {
                model.removeLink(model.selectedWire);
                syncDraft();
            } else deleteSelected();
            return;
        }
        if (editing && !pending && getWindows().isEmpty() && isCtrlKeyDown()) {
            if (key == Keyboard.KEY_Z) {
                history(!isShiftKeyDown());
                return;
            }
            if (key == Keyboard.KEY_Y) {
                history(false);
                return;
            }
        }
        if (key == mc.gameSettings.keyBindInventory.getKeyCode() && getWindows().isEmpty()) {
            exit();
            return;
        }
        super.keyTyped(c, key);
    }
}
