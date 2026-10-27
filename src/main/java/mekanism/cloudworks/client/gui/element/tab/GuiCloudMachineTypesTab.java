package mekanism.cloudworks.client.gui.element.tab;

import java.util.function.Supplier;
import java.util.function.Consumer;

import mekanism.client.SpecialColors;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.tab.window.GuiWindowCreatorTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.MekanismRenderer;
import mekanism.cloudworks.client.gui.GuiCloudMachineTypesWindow;
import mekanism.cloudworks.client.gui.GuiCloudCacheWindow;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.common.MekanismBlocks;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.util.LangUtils;
import mekanism.common.util.MekanismUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

/** Opens the client-local machine upload catalogue. */
public final class GuiCloudMachineTypesTab extends GuiWindowCreatorTab<Void, GuiCloudMachineTypesTab> {
    private final Supplier<GuiCloudCacheWindow> cacheWindow;
    private final Consumer<GuiCloudMachineTypesWindow> onOpen, onClose;

    public GuiCloudMachineTypesTab(IGuiWrapper gui, int x, int y,
            Supplier<GuiCloudMachineTypesTab> self, Supplier<GuiCloudCacheWindow> cacheWindow,
            Consumer<GuiCloudMachineTypesWindow> onOpen, Consumer<GuiCloudMachineTypesWindow> onClose) {
        super(MekanismUtils.getResource(MekanismUtils.ResourceType.GUI, "null.png"),
                gui, null, x, y, 26, 18, true, self);
        this.cacheWindow = cacheWindow;
        this.onOpen = onOpen == null ? ignored -> {} : onOpen;
        this.onClose = onClose == null ? ignored -> {} : onClose;
    }

    @Override
    protected Integer getTabColor() {
        return SpecialColors.TAB_CONFIGURATION.argb();
    }

    @Override
    protected ITextComponent getTooltipText() {
        return new TextComponentString(LangUtils.localize("cloudworks.cloud.tab_machine_types"));
    }

    @Override
    protected GuiWindow createWindow(SelectedWindowData data) {
        GuiCloudCacheWindow parent = cacheWindow.get();
        int x = parent == null ? (getGuiWidth() - 180) / 2 : parent.getRelativeX() - 180 - 8;
        int y = parent == null ? 8 : parent.getRelativeY();
        GuiCloudMachineTypesWindow window = new GuiCloudMachineTypesWindow(gui(),
                x, y, onClose);
        onOpen.accept(window);
        return window;
    }

    @Override
    protected SelectedWindowData getNextWindowData() {
        return new SelectedWindowData(CloudworksWindowTypes.CLOUD_MACHINE_TYPES);
    }

    @Override
    protected void drawBackgroundOverlay() {
        gui().renderItemWithOverlay(new ItemStack(MekanismBlocks.MachineBlock, 1, 10),
                getButtonX() + (innerWidth - 14) / 2, getButtonY() + (innerHeight - 14) / 2, 0.875F, null);
        MekanismRenderer.resetColor();
    }
}
