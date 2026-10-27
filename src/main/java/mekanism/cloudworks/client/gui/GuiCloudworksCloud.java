package mekanism.cloudworks.client.gui;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.client.gui.element.tab.GuiSecurityTab;
import mekanism.client.gui.element.window.GuiPlayerInventoryWindow;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.cloudworks.client.gui.element.GuiCloudLineList;
import mekanism.cloudworks.client.gui.element.tab.GuiCloudCacheTab;
import mekanism.cloudworks.client.gui.element.tab.GuiCloudEnergyTab;
import mekanism.cloudworks.client.gui.element.tab.GuiCloudRecycleTab;
import mekanism.cloudworks.client.gui.element.tab.GuiCloudUpgradeTab;
import mekanism.cloudworks.client.gui.element.tab.GuiCloudWarehousesTab;
import mekanism.cloudworks.client.gui.element.tab.GuiCloudStatusTab;
import mekanism.cloudworks.client.gui.element.tab.GuiCloudTeamTab;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.registries.CloudworksItems;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.common.util.LangUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import javax.annotation.Nullable;
import mekanism.cloudworks.common.CloudworksCommonProxy;
import mekanism.cloudworks.common.content.CloudworksTerminalHost;
import mekanism.cloudworks.common.tile.TileEntityCloudDashboard;
import org.lwjgl.input.Keyboard;

/**
 * 云端总览屏（主 GUI 双屏结构的第一屏, 384×212, no player inventory): a QIO
 * crafting-monitor style production-line directory. Left edge carries the seven
 * cloud tabs; the body lists every line with
 * its state, node count and energy; clicking a row selects it, double-clicking
 * (or 返回键 on a selected row) swaps to the full-screen line editor while the
 * shared container keeps syncing.
 */
@SideOnly(Side.CLIENT)
public final class GuiCloudworksCloud extends GuiMekanism<ContainerCloudworks> {

    private static final int DIRECTORY_X = 8;
    private static final int DIRECTORY_Y = 44;
    private static final int DIRECTORY_W = 368;
    private static final int DIRECTORY_H = 152;
    private static final int ROW_HEIGHT = 26;
    private static final int TITLE_Y = 6;
    private static final long DOUBLE_CLICK_MS = 350;

    private final ContainerCloudworks container;
    private final InventoryPlayer inventory;
    private final Deque<Edit> edits = new ArrayDeque<>();
    private final List<GuiCloudLineList.Entry> rows = new ArrayList<>();
    private GuiCloudCacheTab cacheTab;
    private GuiCloudEnergyTab energyTab;
    private GuiCloudUpgradeTab upgradeTab;
    private GuiCloudWarehousesTab warehousesTab;
    private GuiCloudRecycleTab recycleTab;
    private GuiCloudStatusTab statusTab;
    private GuiCloudTeamTab teamTab;
    private GuiPlayerInventoryWindow inventoryWindow;
    private SelectedWindowData activeInventoryContext = CloudworksWindowTypes.CLOUD_CACHE_DATA;
    private boolean pinnedWindowsInitialized;

    private LineFilter filter = LineFilter.ALL;
    private CloudworksGuiData pageSnapshot = CloudworksGuiData.EMPTY;
    private long seenVersion = -1, sentVersion = -1;
    private boolean waiting;
    private Operation inFlight;
    private String feedback = "";
    private int scrollOffset;   // row pixels scrolled
    private UUID selectedLine;
    private UUID confirmDelete;
    private long lastClickTime = -1;
    private int lastClickIndex = -1;
    private MekanismButton filterButton, actionButton;
    private GuiCloudLineList lineList;

    public GuiCloudworksCloud(InventoryPlayer inventory) {
        this(inventory, null);
    }

    /**
     * Host-aware form: the dashboard tile hosts the terminal slots, the portable
     * terminal path binds them to the held item. Mirrors the server-side
     * construction so vanilla slot sync lines up.
     */
    public GuiCloudworksCloud(InventoryPlayer inventory, @Nullable TileEntityCloudDashboard dashboard) {
        this(createContainer(inventory, dashboard), inventory);
    }

    private static ContainerCloudworks createContainer(InventoryPlayer inventory,
            @Nullable TileEntityCloudDashboard dashboard) {
        if (dashboard != null) return new ContainerCloudworks(inventory, dashboard);
        CloudworksTerminalHost host =
                CloudworksCommonProxy.heldTerminalHost(inventory.player);
        boolean team = inventory.player.getHeldItemMainhand().getItem() == CloudworksItems.TeamTerminal;
        return new ContainerCloudworks(inventory, host, null, team);
    }

    /** Screen-swap form: reuses the already-open container so sync never breaks. */
    public GuiCloudworksCloud(ContainerCloudworks container, InventoryPlayer inventory) {
        super(container);
        this.container = container;
        this.inventory = inventory;
        xSize = 384;
        ySize = 212;
    }

    private String tr(String key) { return LangUtils.localize("cloudworks.cloud." + key); }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();
        filterButton = addButton(new MekanismButton(this, DIRECTORY_X, 20, 96, 14,
              new TextComponentString(filter.label()), this::cycleFilter, null, null));
        actionButton = addButton(new MekanismButton(this, xSize - 92, 20, 84, 14,
              new TextComponentString(tr("create")), this::createOrDelete, null,
              getOnHover(() -> new TextComponentString(actionHover()))));
        lineList = addButton(new GuiCloudLineList(this, DIRECTORY_X, DIRECTORY_Y, DIRECTORY_W, DIRECTORY_H,
              () -> rows, () -> selectedLine, this::selectLine, entry -> openLine(entry.id, entry.name)));
        cacheTab = addButton(new GuiCloudCacheTab(this, 6, container, () -> cacheTab));
        energyTab = addButton(new GuiCloudEnergyTab(this, 32, container, () -> energyTab));
        upgradeTab = addButton(new GuiCloudUpgradeTab(this, 58, container, () -> upgradeTab));
        warehousesTab = addButton(new GuiCloudWarehousesTab(this, 84, container, () -> warehousesTab));
        recycleTab = addButton(new GuiCloudRecycleTab(this, 110, container, () -> recycleTab));
        statusTab = addButton(new GuiCloudStatusTab(this, 136, container, () -> statusTab));
        if (container.isTeamTerminal())
            teamTab = addButton(new GuiCloudTeamTab(this, 6, container, () -> teamTab));
        setTabsReady();
        GuiEnergyTab energyStatusTab = new GuiEnergyTab(this, this::getEnergyTabText);
        energyStatusTab.move(0, 25);
        addButton(energyStatusTab);
        if (container.dashboard() != null)
            addButton(new GuiSecurityTab<>(this, container.dashboard(), 6));
    }

    @Override
    protected void initPinnedWindows() {
        if (container.session() != null) {
            super.initPinnedWindows();
            pinnedWindowsInitialized = true;
        }
    }

    private void setTabsReady() {
        boolean ready = container.session() != null;
        cacheTab.active = ready && getWindows().stream().noneMatch(window -> window instanceof GuiCloudCacheWindow);
        energyTab.active = ready && getWindows().stream().noneMatch(window -> window instanceof GuiCloudEnergyWindow);
        upgradeTab.active = ready && getWindows().stream().noneMatch(window -> window instanceof GuiCloudUpgradeWindow);
        warehousesTab.active = ready && getWindows().stream().noneMatch(window -> window instanceof GuiCloudWarehousesWindow);
        recycleTab.active = ready && getWindows().stream().noneMatch(window -> window instanceof GuiCloudRecycleWindow);
        statusTab.active = ready && getWindows().stream().noneMatch(window -> window instanceof GuiCloudStatusWindow);
        if (teamTab != null)
            teamTab.active = ready && getWindows().stream().noneMatch(window -> window instanceof GuiCloudTeamWindow);
    }

    @Override
    public void addWindow(GuiWindow window) {
        SelectedWindowData context = inventoryContext(window);
        if (context != null) {
            activeInventoryContext = context;
            ensureInventoryOpen(window);
        }
        super.addWindow(window);
    }

    private void ensureInventoryOpen(GuiWindow anchor) {
        if (inventoryWindow != null) {
            focusWindow(inventoryWindow);
            return;
        }
        int x = Math.max(-guiLeft, Math.min(anchor.getRelativeRight() + 4,
                width - guiLeft - GuiPlayerInventoryWindow.WIDTH));
        int y = Math.max(-guiTop, Math.min(anchor.getRelativeY(),
                height - guiTop - GuiPlayerInventoryWindow.HEIGHT));
        inventoryWindow = new GuiPlayerInventoryWindow(this, x, y,
                CloudworksWindowTypes.CLOUD_INVENTORY_DATA, () -> activeInventoryContext,
                container.getPlayerInventorySlots(), null, () -> inventoryWindow = null);
        super.addWindow(inventoryWindow);
    }

    @Override
    public void setSelectedWindow(SelectedWindowData selectedWindow) {
        if (CloudworksWindowTypes.isTerminalInventoryWindow(selectedWindow)) {
            activeInventoryContext = selectedWindow;
        }
        super.setSelectedWindow(selectedWindow);
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        SelectedWindowData context = inventoryContext(getWindowHovering(mouseX, mouseY));
        if (context != null) {
            // Select the layer before GuiVirtualSlot sends its first click packet.
            setSelectedWindow(context);
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void removeWindow(GuiWindow window) {
        super.removeWindow(window);
        if (window == inventoryWindow) {
            inventoryWindow = null;
        }
        SelectedWindowData closedContext = inventoryContext(window);
        if (closedContext == null) {
            return;
        }
        SelectedWindowData nextContext = null;
        for (GuiWindow remaining : getWindows()) {
            nextContext = inventoryContext(remaining);
            if (nextContext != null) {
                break;
            }
        }
        if (nextContext == null) {
            GuiPlayerInventoryWindow inventory = inventoryWindow;
            inventoryWindow = null;
            if (inventory != null) {
                inventory.close();
            }
        } else if (closedContext.equals(activeInventoryContext)) {
            activeInventoryContext = nextContext;
            if (inventoryWindow != null && getWindows().iterator().next() == inventoryWindow) {
                inventoryWindow.onFocused();
            }
        }
    }

    private static SelectedWindowData inventoryContext(GuiWindow window) {
        if (window instanceof GuiCloudCacheWindow) {
            return CloudworksWindowTypes.CLOUD_CACHE_DATA;
        }
        if (window instanceof GuiCloudEnergyWindow) {
            return CloudworksWindowTypes.CLOUD_ENERGY_DATA;
        }
        if (window instanceof GuiCloudUpgradeWindow) {
            return CloudworksWindowTypes.CLOUD_UPGRADE_DATA;
        }
        return null;
    }

    private List<ITextComponent> getEnergyTabText() {
        List<ITextComponent> info = new ArrayList<>();
        boolean unlimited = pageSnapshot.overview.unlimited;
        double energy = pageSnapshot.overview.energy;
        double maxEnergy = pageSnapshot.overview.maxEnergy;
        info.add(new TextComponentString(LangUtils.localize("gui.cloudworks.energy_pool") + ": " + (unlimited
              ? tr("unlimited") : MekanismUtils.getEnergyDisplay(energy, maxEnergy))));
        info.add(new TextComponentString(LangUtils.localize("gui.cloudworks.energy_receiving_relay") + ": "
              + MekanismUtils.getEnergyDisplay(pageSnapshot.overview.receivingEnergy)));
        info.add(new TextComponentString(LangUtils.localize("gui.cloudworks.energy_sending_relay") + ": "
              + MekanismUtils.getEnergyDisplay(pageSnapshot.overview.sendingEnergy)));
        return info;
    }

    private void openUpgradeWindow() {
        for (GuiWindow window : new ArrayList<>(getWindows()))
            if (window instanceof GuiCloudUpgradeWindow) { focusWindow(window); return; }
        addWindow(new GuiCloudUpgradeWindow(this, (width - 220) / 2 - guiLeft, 8, container));
    }

    private void openEnergyWindow() {
        for (GuiWindow window : new ArrayList<>(getWindows()))
            if (window instanceof GuiCloudEnergyWindow) { focusWindow(window); return; }
        addWindow(new GuiCloudEnergyWindow(this, (width - 300) / 2 - guiLeft, 8, container));
    }

    private void openRecycleWindow() {
        for (GuiWindow window : new ArrayList<>(getWindows()))
            if (window instanceof GuiCloudRecycleWindow) { focusWindow(window); return; }
        addWindow(new GuiCloudRecycleWindow(this, (width - 250) / 2 - guiLeft, 8, container));
    }

    private void openCacheWindow() {
        for (GuiWindow window : new ArrayList<>(getWindows()))
            if (window instanceof GuiCloudCacheWindow) { focusWindow(window); return; }
        GuiCloudCacheWindow window = new GuiCloudCacheWindow(this, (width - 344) / 2 - guiLeft, 8, container);
        addWindow(window);
    }

    private void openWarehousesWindow() {
        for (GuiWindow window : new ArrayList<>(getWindows()))
            if (window instanceof GuiCloudWarehousesWindow) { focusWindow(window); return; }
        addWindow(new GuiCloudWarehousesWindow(this, (width - 344) / 2 - guiLeft, 8, container));
    }

    private String actionHover() {
        return selectedLine == null ? tr("create") : tr(isShiftHeld() ? "delete_shift" : "delete");
    }

    private static boolean isShiftHeld() {
        return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
              || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
    }

    private void cycleFilter() {
        LineFilter[] values = LineFilter.values();
        filter = values[(filter.ordinal() + 1) % values.length];
        filterButton.setMessage(new TextComponentString(filter.label()));
        selectedLine = null;
        scrollOffset = 0;
    }

    private void createOrDelete() {
        if (selectedLine == null) {
            queue(Operation.CREATE, null, "");
            return;
        }
        if (isShiftHeld() || selectedLine.equals(confirmDelete)) {
            queue(Operation.DELETE, selectedLine, "");
            confirmDelete = null;
            selectedLine = null;
            return;
        }
        confirmDelete = selectedLine;
        feedback = tr("confirm_delete");
    }

    private void queue(Operation operation, UUID line, String value) {
        edits.add(new Edit(operation, line, value));
        feedback = LangUtils.localize("cloudworks.editor.saving");
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!pinnedWindowsInitialized) {
            initPinnedWindows();
        }
        setTabsReady();
        if (seenVersion != container.viewVersion()) {
            seenVersion = container.viewVersion();
            pageSnapshot = container.guiData();
            if (waiting && seenVersion > sentVersion) {
                waiting = false;
                if (container.status().startsWith("cloudworks.error.")) {
                    edits.clear();
                    feedback = LangUtils.localize(container.status());
                } else {
                    feedback = LangUtils.localize("cloudworks.editor.saved");
                }
            }
            List<CloudworksGuiData.Line> lines = pageSnapshot.overview.lines;
            if (!waiting && inFlight == Operation.DELETE && !container.status().startsWith("cloudworks.error.")) {
                selectedLine = null;
                confirmDelete = null;
            }
            if (!waiting && inFlight == Operation.CREATE && !container.status().startsWith("cloudworks.error.")) {
                CloudworksGuiData.Graph next = pageSnapshot.graph;
                if (next.id != null) openLine(next.id, next.name);
            }
            if (!waiting) inFlight = null;
            rebuildRows(lines);
        }
        if (!waiting && !edits.isEmpty() && container.session() != null) {
            Edit edit = edits.removeFirst();
            sentVersion = container.viewVersion();
            waiting = true;
            inFlight = edit.operation;
            container.sendAction(edit.operation, edit.line, edit.value);
        }
        refreshAction();
    }

    private void refreshAction() {
        boolean selected = selectedLine != null;
        actionButton.setMessage(new TextComponentString(selected ? tr("delete") : tr("create")));
    }

    private void rebuildRows(List<CloudworksGuiData.Line> lines) {
        rows.clear();
        for (CloudworksGuiData.Line entry : lines) {
            if (!entry.available) continue;
            boolean enabled = entry.enabled;
            if (!filter.accepts(enabled)) continue;
            rows.add(new GuiCloudLineList.Entry(entry.id, entry.name, enabled,
                  entry.nodeCount, entry.energy, entry.maxEnergy, entry.icon, entry.markers, entry.runWhileOwnerOffline));
        }
    }

    private void openLine(UUID id, String name) {
        mc.displayGuiScreen(new GuiCloudworksLine(container, inventory, id, name));
    }

    private void selectLine(GuiCloudLineList.Entry entry) {
        selectedLine = entry == null ? null : entry.id;
        confirmDelete = null;
    }

    @Override
    protected void drawForegroundText(int mouseX, int mouseY) {
        drawScaledScrollingString(new TextComponentString(tr("title")), 4, TITLE_Y,
                TextAlignment.LEFT, 0x263D48, xSize - 8, 3, false, 1, getTimeOpened());
        int total = pageSnapshot.overview.total;
        int unlocked = pageSnapshot.overview.unlocked;
        drawScaledScrollingString(new TextComponentString(tr("usage") + " " + total + "/" + unlocked),
                DIRECTORY_X + 110, 24, TextAlignment.LEFT, 0x263D48, 100, 3, false, 1, getTimeOpened());
        if (!feedback.isEmpty()) {
            drawScaledScrollingString(new TextComponentString(feedback), DIRECTORY_X, ySize - 12,
                    TextAlignment.LEFT, 0x263D48, DIRECTORY_W, 3, false, 1, getTimeOpened());
        }
        super.drawForegroundText(mouseX, mouseY);
    }

    private enum LineFilter {
        ALL("filter_all") {
            @Override boolean accepts(boolean enabled) { return true; }
        },
        ACTIVE("filter_active") {
            @Override boolean accepts(boolean enabled) { return enabled; }
        },
        OFF("filter_off") {
            @Override boolean accepts(boolean enabled) { return !enabled; }
        };
        private final String key;

        LineFilter(String key) { this.key = key; }

        abstract boolean accepts(boolean enabled);

        String label() { return LangUtils.localize("cloudworks.cloud." + key); }
    }

    private static final class Edit {
        final Operation operation;
        final UUID line;
        final String value;

        Edit(Operation operation, UUID line, String value) {
            this.operation = operation;
            this.line = line;
            this.value = value;
        }
    }
}
