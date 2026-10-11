package mekanism.cloudworks.client.gui.line;

import java.util.UUID;
import mekanism.client.gui.IGuiWrapper;
import mekanism.cloudworks.client.gui.element.GuiCloudUpgradeScrollList;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.common.Upgrade;
import net.minecraft.util.text.TextComponentString;

/** Observer upgrade counts come from live GUI snapshots, never from an editor draft. */
public final class GuiLineUpgradeViewWindow extends GuiLineWindow {
    public GuiLineUpgradeViewWindow(IGuiWrapper gui, ContainerCloudworks container, UUID line) {
        super(gui, 30, 74, 222, 84, "upgrade_view", type("upgrades"));
        addChild(new GuiCloudUpgradeScrollList(gui, relativeX + 8, relativeY + 32, 44,
                upgrade -> upgrade == Upgrade.SPEED ? container.guiData().overview.speedUpgradePool
                        : container.guiData().overview.energyUpgradePool, selected -> {}));
        addChild(new GuiCloudUpgradeScrollList(gui, relativeX + 115, relativeY + 32, 44,
                upgrade -> !line.equals(container.guiData().graph.id) ? 0
                        : upgrade == Upgrade.SPEED ? container.guiData().graph.speed
                        : container.guiData().graph.energyUpgrades, selected -> {}));
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawScrollingString(new TextComponentString(tr("cloud_pool")), 8, 21,
                TextAlignment.LEFT, 0x404040, 99, 3, false);
        drawScrollingString(new TextComponentString(tr("line_pool")), 115, 21,
                TextAlignment.LEFT, 0x404040, 99, 3, false);
    }
}
