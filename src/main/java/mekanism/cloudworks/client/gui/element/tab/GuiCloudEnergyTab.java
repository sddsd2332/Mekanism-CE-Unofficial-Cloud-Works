package mekanism.cloudworks.client.gui.element.tab;

import java.util.function.Supplier;
import mekanism.client.SpecialColors;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.tab.window.GuiWindowCreatorTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.MekanismRenderer;
import mekanism.cloudworks.client.gui.GuiCloudEnergyWindow;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.common.MekanismBlocks;
import mekanism.common.base.ITierItem;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.tier.BaseTier;
import mekanism.common.util.LangUtils;
import mekanism.common.util.MekanismUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

public final class GuiCloudEnergyTab extends GuiWindowCreatorTab<Void, GuiCloudEnergyTab> {
    private final ContainerCloudworks container;

    public GuiCloudEnergyTab(IGuiWrapper gui, int y, ContainerCloudworks container,
            Supplier<GuiCloudEnergyTab> self) {
        super(MekanismUtils.getResource(MekanismUtils.ResourceType.GUI, "null.png"),
                gui, null, -26, y, 26, 18, true, self);
        this.container = container;
    }

    @Override protected Integer getTabColor() { return SpecialColors.TAB_CONFIGURATION.argb(); }
    @Override protected ITextComponent getTooltipText() {
        return new TextComponentString(LangUtils.localize("cloudworks.cloud.tab_energy"));
    }
    @Override protected GuiWindow createWindow(SelectedWindowData data) {
        return new GuiCloudEnergyWindow(gui(), (getGuiWidth() - 300) / 2, 8, container);
    }
    @Override protected SelectedWindowData getNextWindowData() {
        return CloudworksWindowTypes.CLOUD_ENERGY_DATA;
    }

    @Override
    protected void drawBackgroundOverlay() {
        int iconX = getButtonX() + (innerWidth - 14) / 2;
        int iconY = getButtonY() + (innerHeight - 14) / 2;
        ItemStack stack = new ItemStack(MekanismBlocks.EnergyCube);
        if (stack.getItem() instanceof ITierItem tierItem){
            tierItem.setBaseTier(stack, BaseTier.ULTIMATE);
        }
        gui().renderItemWithOverlay(stack, iconX, iconY, 0.875F, null);
        MekanismRenderer.resetColor();
    }
}
