package mekanism.cloudworks.client.gui.element.tab;

import mekanism.client.SpecialColors;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.tab.window.GuiWindowCreatorTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.MekanismRenderer;
import mekanism.cloudworks.client.gui.GuiCloudCacheWindow;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.common.MekanismBlocks;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.util.LangUtils;
import mekanism.common.util.MekanismUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

import java.util.function.Supplier;

public final class GuiCloudCacheTab extends GuiWindowCreatorTab<Void, GuiCloudCacheTab> {
    private final ContainerCloudworks container;

    public GuiCloudCacheTab(IGuiWrapper gui, int y, ContainerCloudworks container,
                            Supplier<GuiCloudCacheTab> self) {
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
        return new TextComponentString(LangUtils.localize("cloudworks.cloud.tab_cache"));
    }

    @Override
    protected GuiWindow createWindow(SelectedWindowData data) {
        return new GuiCloudCacheWindow(gui(), (getGuiWidth() - 227) / 2, 8, container);
    }

    @Override
    protected SelectedWindowData getNextWindowData() {
        return CloudworksWindowTypes.CLOUD_CACHE_DATA;
    }

    @Override
    protected void drawBackgroundOverlay() {
        int iconX = getButtonX() + (innerWidth - 14) / 2;
        int iconY = getButtonY() + (innerHeight - 14) / 2;
        gui().renderItemWithOverlay(new ItemStack(MekanismBlocks.MachineBlock, 1, 10), iconX, iconY, 0.875F, null);
        MekanismRenderer.resetColor();
    }
}
