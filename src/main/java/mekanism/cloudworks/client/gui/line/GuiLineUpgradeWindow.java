package mekanism.cloudworks.client.gui.line;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.cloudworks.client.gui.element.CloudUpgradeEntry;
import mekanism.cloudworks.client.gui.element.GuiCloudUpgradeScrollList;
import mekanism.common.Upgrade;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.TextComponentString;

public final class GuiLineUpgradeWindow extends GuiLineWindow {
    static final int WIDTH = 240;
    static final int CLOUD_X = 8, LINE_X = 124, COLUMN_WIDTH = 108;
    static final int LIST_Y = 32, LIST_HEIGHT = 44;
    private static final SelectedWindowData.WindowType TYPE = type("upgrades");
    private final CloudworksLineEditorModel model;
    private final List<CloudUpgradeEntry> entries = Arrays.asList(
            CloudUpgradeEntry.nativeType(Upgrade.SPEED), CloudUpgradeEntry.nativeType(Upgrade.ENERGY));
    private final GuiCloudUpgradeScrollList cloudList, lineList;
    private CloudUpgradeEntry left, right;
    private final MekanismButton add, remove;

    public GuiLineUpgradeWindow(IGuiWrapper gui, CloudworksLineEditorModel model) {
        super(gui, 30, 74, WIDTH, 106, "upgrades", TYPE);
        this.model = model;
        cloudList = addChild(new GuiCloudUpgradeScrollList(gui, relativeX + CLOUD_X, relativeY + LIST_Y, LIST_HEIGHT,
                () -> installedEntries(true), entry -> model.pool("speed".equals(entry.wireId)),
                selected -> select(true, selected)));
        lineList = addChild(new GuiCloudUpgradeScrollList(gui, relativeX + LINE_X, relativeY + LIST_Y, LIST_HEIGHT,
                () -> installedEntries(false), entry -> model.installed("speed".equals(entry.wireId)),
                selected -> select(false, selected)));
        add = addChild(new MekanismButton(gui, relativeX + CLOUD_X, relativeY + 81, COLUMN_WIDTH, 18,
                new TextComponentString(tr("add_upgrade")),
                () -> transfer(true), null));
        remove = addChild(new MekanismButton(gui, relativeX + LINE_X, relativeY + 81, COLUMN_WIDTH, 18,
                new TextComponentString(tr("uninstall")),
                () -> transfer(false), null));
        tick();
    }

    private List<CloudUpgradeEntry> installedEntries(boolean cloud) {
        List<CloudUpgradeEntry> installed = new ArrayList<>();
        for (CloudUpgradeEntry entry : entries) {
            boolean speed = "speed".equals(entry.wireId);
            if ((cloud ? model.pool(speed) : model.installed(speed)) > 0) installed.add(entry);
        }
        return installed;
    }

    private void select(boolean cloud, CloudUpgradeEntry selected) {
        if (cloud) {
            left = selected;
            if (selected != null) lineList.clearSelection();
        } else {
            right = selected;
            if (selected != null) cloudList.clearSelection();
        }
        updateEnabledButtons();
    }

    private void transfer(boolean install) {
        CloudUpgradeEntry selected = install ? left : right;
        if (selected == null) return;
        boolean speed = "speed".equals(selected.wireId);
        model.upgrade(speed, install, GuiScreen.isShiftKeyDown());
        if ((install ? model.pool(speed) : model.installed(speed)) == 0)
            (install ? cloudList : lineList).clearSelection();
        updateEnabledButtons();
    }

    @Override
    public void tick() {
        super.tick();
        updateEnabledButtons();
    }

    private void updateEnabledButtons() {
        boolean speed = left != null && "speed".equals(left.wireId);
        add.active = left != null && model.pool(speed) > 0
                && model.installed(speed) < (speed ? model.draft.maxSpeed : model.draft.maxEnergy);
        remove.active = right != null && model.installed("speed".equals(right.wireId)) > 0;
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawScrollingString(new TextComponentString(tr("cloud_pool")), CLOUD_X, 21,
                TextAlignment.LEFT, 0x404040, COLUMN_WIDTH, 3, false);
        drawScrollingString(new TextComponentString(tr("line_pool")), LINE_X, 21,
                TextAlignment.LEFT, 0x404040, COLUMN_WIDTH, 3, false);
    }
}
