package mekanism.cloudworks.client.gui.line;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import mekanism.client.gui.IGuiWrapper;
import mekanism.cloudworks.client.gui.element.CloudUpgradeEntry;
import mekanism.cloudworks.client.gui.element.GuiCloudUpgradeScrollList;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.common.Upgrade;
import net.minecraft.util.text.TextComponentString;

/** Observer upgrade counts come from live GUI snapshots, never from an editor draft. */
public final class GuiLineUpgradeViewWindow extends GuiLineWindow {
    private final ContainerCloudworks container;
    private final UUID line;
    private final List<CloudUpgradeEntry> types = Arrays.asList(
            CloudUpgradeEntry.nativeType(Upgrade.SPEED), CloudUpgradeEntry.nativeType(Upgrade.ENERGY));
    private final GuiCloudUpgradeScrollList cloudList, lineList;

    public GuiLineUpgradeViewWindow(IGuiWrapper gui, ContainerCloudworks container, UUID line) {
        super(gui, 30, 74, GuiLineUpgradeWindow.WIDTH, 84, "upgrade_view", type("upgrades"));
        this.container = container;
        this.line = line;
        cloudList = addChild(new GuiCloudUpgradeScrollList(gui, relativeX + GuiLineUpgradeWindow.CLOUD_X,
                relativeY + GuiLineUpgradeWindow.LIST_Y, GuiLineUpgradeWindow.LIST_HEIGHT,
                () -> installedEntries(true), entry -> count(entry, true), entry -> select(true, entry)));
        lineList = addChild(new GuiCloudUpgradeScrollList(gui, relativeX + GuiLineUpgradeWindow.LINE_X,
                relativeY + GuiLineUpgradeWindow.LIST_Y, GuiLineUpgradeWindow.LIST_HEIGHT,
                () -> installedEntries(false), entry -> count(entry, false), entry -> select(false, entry)));
    }

    private List<CloudUpgradeEntry> installedEntries(boolean cloud) {
        List<CloudUpgradeEntry> installed = new ArrayList<>();
        for (CloudUpgradeEntry entry : types) if (count(entry, cloud) > 0) installed.add(entry);
        return installed;
    }

    private int count(CloudUpgradeEntry entry, boolean cloud) {
        boolean speed = "speed".equals(entry.wireId);
        if (cloud) return container.upgradePool(speed);
        if (!line.equals(container.guiData().graph.id)) return 0;
        return speed ? container.guiData().graph.speed : container.guiData().graph.energyUpgrades;
    }

    private void select(boolean cloud, CloudUpgradeEntry entry) {
        if (entry != null) (cloud ? lineList : cloudList).clearSelection();
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawScrollingString(new TextComponentString(tr("cloud_pool")), GuiLineUpgradeWindow.CLOUD_X, 21,
                TextAlignment.LEFT, 0x404040, GuiLineUpgradeWindow.COLUMN_WIDTH, 3, false);
        drawScrollingString(new TextComponentString(tr("line_pool")), GuiLineUpgradeWindow.LINE_X, 21,
                TextAlignment.LEFT, 0x404040, GuiLineUpgradeWindow.COLUMN_WIDTH, 3, false);
    }
}
