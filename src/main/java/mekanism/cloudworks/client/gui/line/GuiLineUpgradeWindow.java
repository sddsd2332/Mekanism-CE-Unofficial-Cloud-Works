package mekanism.cloudworks.client.gui.line;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.cloudworks.client.gui.element.GuiCloudUpgradeScrollList;
import mekanism.common.Upgrade;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.TextComponentString;

public final class GuiLineUpgradeWindow extends GuiLineWindow {
    private static final SelectedWindowData.WindowType TYPE = type("upgrades");
    private final CloudworksLineEditorModel model;
    private Upgrade left, right;
    private final MekanismButton add, remove;

    public GuiLineUpgradeWindow(IGuiWrapper gui, CloudworksLineEditorModel model) {
        super(gui, 30, 74, 222, 106, "upgrades", TYPE);
        this.model = model;
        addChild(new GuiCloudUpgradeScrollList(gui, relativeX + 8, relativeY + 32, 44,
                upgrade -> model.pool(upgrade == Upgrade.SPEED), selected -> left = selected));
        addChild(new GuiCloudUpgradeScrollList(gui, relativeX + 115, relativeY + 32, 44,
                upgrade -> model.installed(upgrade == Upgrade.SPEED), selected -> right = selected));
        add = addChild(new MekanismButton(gui, relativeX + 8, relativeY + 81, 99, 18,
                new TextComponentString(tr("add_upgrade")), () -> model.upgrade(left == Upgrade.SPEED, true, GuiScreen.isShiftKeyDown()), null));
        remove = addChild(new MekanismButton(gui, relativeX + 115, relativeY + 81, 99, 18,
                new TextComponentString(tr("uninstall")), () -> model.upgrade(right == Upgrade.SPEED, false, GuiScreen.isShiftKeyDown()), null));
        tick();
    }

    @Override
    public void tick() {
        super.tick();
        boolean speed = left == Upgrade.SPEED;
        add.active = left != null && model.pool(speed) > 0
                && model.installed(speed) < (speed ? model.draft.maxSpeed : model.draft.maxEnergy);
        remove.active = right != null && model.installed(right == Upgrade.SPEED) > 0;
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawScrollingString(new TextComponentString(tr("cloud_pool")), 8, 21, TextAlignment.LEFT, 0x404040, 99, 3, false);
        drawScrollingString(new TextComponentString(tr("line_pool")), 115, 21, TextAlignment.LEFT, 0x404040, 99, 3, false);
    }
}
