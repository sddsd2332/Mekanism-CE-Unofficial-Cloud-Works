package mekanism.cloudworks.client.gui.element.tab;

import mekanism.client.SpecialColors;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.tab.window.GuiWindowCreatorTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.MekanismRenderer;
import mekanism.cloudworks.client.gui.GuiCloudTeamWindow;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.util.LangUtils;
import mekanism.common.util.MekanismUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

import java.util.function.Supplier;

public final class GuiCloudTeamTab extends GuiWindowCreatorTab<Void, GuiCloudTeamTab> {
    private final ContainerCloudworks container;

    public GuiCloudTeamTab(IGuiWrapper gui, int y, ContainerCloudworks container,
                           Supplier<GuiCloudTeamTab> self) {
        super(MekanismUtils.getResource(MekanismUtils.ResourceType.GUI, "amplifier_entity.png"),
                gui, null, gui.getWidth(), y, 26, 18, false, self);
        this.container = container;
    }

    @Override
    protected Integer getTabColor() {
        return SpecialColors.TAB_CONFIGURATION.argb();
    }

    @Override
    protected ITextComponent getTooltipText() {
        return new TextComponentString(LangUtils.localize("cloudworks.cloud.tab_team"));
    }

    @Override
    protected GuiWindow createWindow(SelectedWindowData data) {
        return new GuiCloudTeamWindow(gui(), (getGuiWidth() - GuiCloudTeamWindow.WIDTH) / 2,
                Math.max(-getGuiTop(), (getGuiHeight() - GuiCloudTeamWindow.HEIGHT) / 2), container);
    }

    @Override
    protected SelectedWindowData getNextWindowData() {
        return new SelectedWindowData(CloudworksWindowTypes.CLOUD_TEAM);
    }
}
