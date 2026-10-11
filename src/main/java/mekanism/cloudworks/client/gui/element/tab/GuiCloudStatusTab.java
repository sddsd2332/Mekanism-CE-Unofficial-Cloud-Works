package mekanism.cloudworks.client.gui.element.tab;

import java.util.function.Supplier;
import mekanism.client.SpecialColors;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.tab.window.GuiWindowCreatorTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.MekanismRenderer;
import mekanism.cloudworks.client.gui.GuiCloudStatusWindow;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.registries.CloudworksItems;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.util.LangUtils;
import mekanism.common.util.MekanismUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

public final class GuiCloudStatusTab extends GuiWindowCreatorTab<Void, GuiCloudStatusTab> {
    private final ContainerCloudworks container;

    public GuiCloudStatusTab(IGuiWrapper gui, int y, ContainerCloudworks container, Supplier<GuiCloudStatusTab> self) {
        super(MekanismUtils.getResource(MekanismUtils.ResourceType.GUI, "null.png"),
                gui, null, -26, y, 26, 18, true, self);
        this.container = container;
    }

    @Override protected Integer getTabColor() { return SpecialColors.TAB_CONFIGURATION.argb(); }
    @Override protected ITextComponent getTooltipText() {
        return new TextComponentString(LangUtils.localize("cloudworks.cloud.tab_status"));
    }
    @Override protected GuiWindow createWindow(SelectedWindowData data) {
        return new GuiCloudStatusWindow(gui(), (getGuiWidth() - 244) / 2, 8, container);
    }
    @Override protected SelectedWindowData getNextWindowData() {
        return new SelectedWindowData(CloudworksWindowTypes.CLOUD_STATUS);
    }
    @Override protected void drawBackgroundOverlay() {
        gui().renderItemWithOverlay(new ItemStack(CloudworksItems.ActivationCard),
                getButtonX() + (innerWidth - 14) / 2, getButtonY() + (innerHeight - 14) / 2, 0.875F, null);
        MekanismRenderer.resetColor();
    }
}
