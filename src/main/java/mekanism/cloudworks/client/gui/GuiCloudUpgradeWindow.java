package mekanism.cloudworks.client.gui;

import java.util.ArrayList;
import java.util.List;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.DigitalButton;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.cloudworks.client.gui.element.CloudUpgradeEntry;
import mekanism.cloudworks.client.gui.element.GuiCloudMixedUpgradeScrollList;
import mekanism.cloudworks.client.gui.element.GuiCloudUpgradeDetails;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import mekanism.common.MekanismLang;
import mekanism.common.Upgrade;
import mekanism.common.inventory.container.slot.SlotOverlay;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.TextComponentString;
import mekanism.common.util.LangUtils;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import java.util.stream.Collectors;
import mekanism.cloudworks.client.gui.element.GuiCloudSupportedUpgrades;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;

@SideOnly(Side.CLIENT)
public class GuiCloudUpgradeWindow extends GuiWindow {

    private static final int WIDTH = 198;

    private final ContainerCloudworks container;
    private final GuiCloudMixedUpgradeScrollList scrollList;
    private final DigitalButton removeButton;
    /** Entries are stable per gui-data snapshot so selection highlight survives refreshes. */
    private final CloudworksGuiDataSnapshotHolder entries = new CloudworksGuiDataSnapshotHolder();
    private CloudUpgradeEntry selectedUpgrade;

    public GuiCloudUpgradeWindow(IGuiWrapper gui, int x, int y, ContainerCloudworks container) {
        super(gui, x, y, WIDTH, windowHeight(gui, container), CloudworksWindowTypes.CLOUD_UPGRADE_DATA);
        this.container = container;
        container.sendAction(Operation.CATALOG_VIEW, null, "1");
        interactionStrategy = InteractionStrategy.ALL;

        scrollList = addChild(new GuiCloudMixedUpgradeScrollList(gui, relativeX + 6, relativeY + 18, GuiCloudUpgradeDetails.HEIGHT,
                this::installedEntries, this::poolCount, this::setSelection));
        addChild(new GuiCloudSupportedUpgrades(
                gui, relativeX + 6, relativeY + 68, ignored -> true,
                GuiCloudSupportedUpgrades::localCustomTypes, ignored -> true));
        GuiCloudUpgradeDetails rightScreen = addChild(new GuiCloudUpgradeDetails(gui, scrollList.getRelativeRight(), relativeY + 18,
                GuiCloudUpgradeDetails.CLOUD_LINES,
                () -> selectedUpgrade, upgrade -> new TextComponentString(
                        LangUtils.localizeWithFormat("gui.cloudworks.upgrade_pool_count", upgradeCount(upgrade)))));
        // Cloud transfers have no timed installation stage; retain Mek's idle indicators.
        addChild(new GuiProgress(() -> 0D, ProgressType.INSTALLING, gui,
                rightScreen.getRelativeRight() + 3, relativeY + 37));
        addChild(new GuiProgress(() -> 0D, ProgressType.UNINSTALLING, gui,
                rightScreen.getRelativeRight() + 3, relativeY + 58));
        removeButton = addChild(new DigitalButton(gui, scrollList.getRelativeRight() + 1,
                relativeY + 54, 56, 12, MekanismLang.UPGRADE_UNINSTALL,
                (element, mouseX, mouseY) -> {
                    downloadSelectedUpgrade();
                    return true;
                }).setTooltip(MekanismLang.UPGRADE_UNINSTALL_TOOLTIP.translate()));

        addTerminalSlot(ContainerCloudworks.SLOT_UPGRADE_IN,
                rightScreen.getRelativeRight() + 2, relativeY + 18);
        addTerminalSlot(ContainerCloudworks.SLOT_UPGRADE_OUT,
                rightScreen.getRelativeRight() + 2, relativeY + 72);
        updateEnabledButtons();
    }

    /** Only installed types appear, matching Mek's native upgrade list. */
    private List<CloudUpgradeEntry> installedEntries() {
        return entries.snapshot(container);
    }

    /**
     * Stable height from the local type registry's upper bound: the window opens
     * fully sized instead of growing when the on-demand catalogue arrives. The
     * strip itself still shows only cloud-owned types (no mask — the cloud layer
     * has no "unsupported" concept), so rows may sit partially unused until then.
     */
    private static int windowHeight(IGuiWrapper gui, ContainerCloudworks container) {
        return 76 + Math.max(18, 12 * GuiCloudSupportedUpgrades.calculateNeededRows(
                gui, GuiCloudSupportedUpgrades.localCustomTypes()));
    }

    private void addTerminalSlot(int index, int x, int y) {
        VirtualInventoryContainerSlot slot = container.getTerminalSlot(index);
        if (slot != null) {
            addChild(new GuiVirtualSlot(this, SlotType.NORMAL, gui(), x, y, slot).with(SlotOverlay.UPGRADE));
        }
    }

    private int poolCount(CloudUpgradeEntry entry) {
        return (int) Math.min(Integer.MAX_VALUE, upgradeCount(entry));
    }

    private long upgradeCount(CloudUpgradeEntry entry) {
        if (!entry.customType()) return container.upgradePool("speed".equals(entry.wireId));
        return container.customUpgradeCount(entry.wireId);
    }

    private void setSelection(CloudUpgradeEntry upgrade) {
        selectedUpgrade = upgrade;
        updateEnabledButtons();
    }

    private void updateEnabledButtons() {
        removeButton.active = selectedUpgrade != null && poolCount(selectedUpgrade) > 0;
    }

    private void downloadSelectedUpgrade() {
        if (selectedUpgrade != null) {
            // Native rows send speed/energy; custom rows send the namespaced id (server path: 8.5-B1).
            container.sendAction(Operation.UPGRADE_DOWNLOAD, null,
                    selectedUpgrade.wireId + "," + (GuiScreen.isShiftKeyDown() ? 1 : 0));
        }
    }

    @Override
    public void tick() {
        super.tick();
        updateEnabledButtons();
        if (selectedUpgrade != null && poolCount(selectedUpgrade) == 0) {
            scrollList.clearSelection();
        }
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawTitleText(new TextComponentString(LangUtils.localize(
                "gui.cloudworks.cloud_upgrade")), 5);
    }

    @Override
    public boolean hasPersistentData() {
        return true;
    }

    /** Keeps entry instances stable within a server snapshot. */
    private static final class CloudworksGuiDataSnapshotHolder {
        private CloudworksGuiData seen;
        private final List<CloudUpgradeEntry> entries = new ArrayList<>();

        private List<CloudUpgradeEntry> snapshot(ContainerCloudworks container) {
            CloudworksGuiData data = container.guiData();
            if (data != seen) {
                seen = data;
                entries.clear();
                if (data.overview.speedUpgradePool > 0)
                    entries.add(CloudUpgradeEntry.nativeType(Upgrade.SPEED));
                if (data.overview.energyUpgradePool > 0)
                    entries.add(CloudUpgradeEntry.nativeType(Upgrade.ENERGY));
                List<CloudworksGuiData.CustomUpgrade> custom = container.customUpgrades();
                if (custom != null) entries.addAll(custom.stream()
                        .filter(upgrade -> upgrade.count > 0)
                        .map(CloudUpgradeEntry::custom)
                        .collect(Collectors.toList()));
            }
            return entries;
        }
    }

    @Override
    public void close() {
        container.sendAction(Operation.CATALOG_VIEW, null, "0");
        super.close();
    }
}
