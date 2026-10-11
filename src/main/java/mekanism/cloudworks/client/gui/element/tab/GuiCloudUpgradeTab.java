package mekanism.cloudworks.client.gui.element.tab;

import mekanism.client.SpecialColors;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.tab.window.GuiWindowCreatorTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.MekanismRenderer;
import mekanism.cloudworks.client.gui.GuiCloudUpgradeWindow;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.common.Upgrade;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.util.LangUtils;
import mekanism.common.util.MekanismUtils;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

import java.util.function.Supplier;

public final class GuiCloudUpgradeTab extends GuiWindowCreatorTab<Void, GuiCloudUpgradeTab> {
    private final ContainerCloudworks container;

    public GuiCloudUpgradeTab(IGuiWrapper gui, int y, ContainerCloudworks container,
                              Supplier<GuiCloudUpgradeTab> self) {
        super(MekanismUtils.getResource(MekanismUtils.ResourceType.GUI, "null.png"),
                gui, null, -26, y, 26, 18, true, self);
        this.container = container;
    }

    @Override
    protected Integer getTabColor() {
        return SpecialColors.TAB_CONFIGURATION.argb();
    }

    @Override
    protected ITextComponent getTooltipText() {
        return new TextComponentString(LangUtils.localize("cloudworks.cloud.tab_upgrade"));
    }

    @Override
    protected GuiWindow createWindow(SelectedWindowData data) {
        return new GuiCloudUpgradeWindow(gui(), (getGuiWidth() - 198) / 2, 8, container);
    }

    @Override
    protected SelectedWindowData getNextWindowData() {
        return CloudworksWindowTypes.CLOUD_UPGRADE_DATA;
    }

    @Override
    protected void drawBackgroundOverlay() {
        int iconX = getButtonX() + (innerWidth - 14) / 2;
        int iconY = getButtonY() + (innerHeight - 14) / 2;
        gui().renderItemWithOverlay(Upgrade.SPEED.getStack(), iconX, iconY, 0.875F, null);
        MekanismRenderer.resetColor();
    }
}
