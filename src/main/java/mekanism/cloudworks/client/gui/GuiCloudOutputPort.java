package mekanism.cloudworks.client.gui;

import mekanism.api.processing.MachineResourceStack;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.element.gauge.GaugeType;
import mekanism.client.gui.element.gauge.GuiEnergyGauge;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.slot.GuiDynamicResourceSlot;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.client.gui.warning.WarningTracker.WarningType;
import mekanism.client.render.MekanismRenderer;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.tile.TileEntityCloudOutput;
import mekanism.cloudworks.common.inventory.container.ContainerCloudOutputPort;
import mekanism.cloudworks.client.gui.element.tab.GuiCloudPortQIOFrequencyTab;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Download-port main screen: the upload screen mirrored — two dynamic buffer
 * previews (items, hybrid tanks — each opens its movable sub-window), the
 * energy gauge and the download countdown (DOWN progress). Downloads are
 * energy-free; a full output buffer pauses the countdown until contents are
 * extracted.
 */
@SideOnly(Side.CLIENT)
public class GuiCloudOutputPort extends GuiConfigurableTile<TileEntityCloudOutput, ContainerCloudOutputPort> {

    private static final ResourceLocation CLOUD_MOTIF = new ResourceLocation("cloudworks", "textures/gui/cloud_motif.png");

    private GuiPortOutItemsWindow itemsWindow;
    private GuiPortOutTanksWindow tanksWindow;
    private GuiCloudPortQIOFrequencyTab qioFrequencyTab;

    public GuiCloudOutputPort(EntityPlayer player, TileEntityCloudOutput tile) {
        super(tile, new ContainerCloudOutputPort(player.inventory, tile));
        dynamicSlots = true;
        titleLabelY = 4;
    }

    private static void mergeResource(List<MachineResourceStack> resources, MachineResourceStack resource) {
        for (int index = 0; index < resources.size(); index++) {
            MachineResourceStack existing = resources.get(index);
            if (existing.sameResource(resource)) {
                resources.set(index, existing.withAmount(existing.amount() + resource.amount()));
                return;
            }
        }
        resources.add(resource);
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();
        qioFrequencyTab = addButton(new GuiCloudPortQIOFrequencyTab(this, tileEntity, () -> qioFrequencyTab));
        addButton(new GuiEnergyGauge(tileEntity.getMainEnergyContainer(), GaugeType.STANDARD, this, 154, 14));
        addButton(new GuiEnergyTab(this, tileEntity.getMainEnergyContainer(), () -> true));
        // Download countdown (DOWN arrows; warning while the output buffer is full.
        addButton(new GuiProgress(tileEntity::getCycleProgress, ProgressType.DOWN, this, 93, 17)
                .warning(WarningType.NO_SPACE_IN_OUTPUT, tileEntity::isDownloadPaused));
        addButton(GuiDynamicResourceSlot.withFrameWidth(this, 110, 26, 40, 1, this::getDisplayedItems)
                .autoFitSpacing(GuiDynamicResourceSlot.DEFAULT_RESOURCE_GAP)
                .borderColor(GuiDynamicResourceSlot.BorderColor.OUTPUT)
                .click((element, mouseX, mouseY) -> openItemsWindow()));
        addButton(GuiDynamicResourceSlot.withFrameWidth(this, 110, 48, 40, 1, this::getDisplayedTanks)
                .autoFitSpacing(GuiDynamicResourceSlot.DEFAULT_RESOURCE_GAP)
                .borderColor(GuiDynamicResourceSlot.BorderColor.OUTPUT_2)
                .click((element, mouseX, mouseY) -> openTanksWindow()));
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTick, int mouseX, int mouseY) {
        super.drawGuiContainerBackgroundLayer(partialTick, mouseX, mouseY);
        MekanismRenderer.bindTexture(CLOUD_MOTIF);
        GuiUtils.blit(guiLeft + 8, guiTop + 20, 0, 0, 105, 43, 105, 43);
        MekanismRenderer.resetColor();
    }

    private List<MachineResourceStack> getDisplayedItems() {
        List<MachineResourceStack> resources = new ArrayList<>();
        for (int slot = 0; slot < tileEntity.getInventorySlots(null).size(); slot++) {
            ItemStack stored = tileEntity.getInventorySlots(null).get(slot).getStack();
            if (stored.isEmpty()) continue;
            MachineResourceStack resource = MachineResourceStack.item("port_item", stored, stored.getCount());
            mergeResource(resources, resource);
        }
        return resources.isEmpty() ? Collections.emptyList() : resources;
    }

    private List<MachineResourceStack> getDisplayedTanks() {
        List<MachineResourceStack> resources = new ArrayList<>();
        for (mekanism.cloudworks.common.content.CloudworksHybridTank tank : tileEntity.portTanks()) {
            FluidStack fluid = tank.getFluid();
            mekanism.api.gas.GasStack gas = tank.getGas();
            if (fluid != null) mergeResource(resources, MachineResourceStack.fluid("port_tank", fluid, fluid.amount));
            else if (gas != null) mergeResource(resources, MachineResourceStack.gas("port_tank", gas, gas.amount));
        }
        return resources.isEmpty() ? Collections.emptyList() : resources;
    }

    private boolean openItemsWindow() {
        if (itemsWindow != null && getWindows().contains(itemsWindow)) {
            focusWindow(itemsWindow);
            return true;
        }
        itemsWindow = new GuiPortOutItemsWindow(this, 8, 8, (ContainerCloudOutputPort) getContainer());
        itemsWindow.setTabListeners(window -> itemsWindow = null, null);
        addWindow(itemsWindow);
        return true;
    }

    private boolean openTanksWindow() {
        if (tanksWindow != null && getWindows().contains(tanksWindow)) {
            focusWindow(tanksWindow);
            return true;
        }
        tanksWindow = new GuiPortOutTanksWindow(this, 8, 8, tileEntity);
        tanksWindow.setTabListeners(window -> tanksWindow = null, null);
        addWindow(tanksWindow);
        return true;
    }

    @Override
    protected void initPinnedWindows() {
        super.initPinnedWindows();
        if (new mekanism.common.inventory.container.SelectedWindowData(CloudworksWindowTypes.PORT_OUT_ITEMS).wasPinned()) {
            openItemsWindow();
        }
        if (new mekanism.common.inventory.container.SelectedWindowData(CloudworksWindowTypes.PORT_OUT_TANKS).wasPinned()) {
            openTanksWindow();
        }
    }

    @Override
    protected void drawForegroundText(int mouseX, int mouseY) {
        drawTitleText(new TextComponentString(tileEntity.getName()), 4);
        renderInventoryText();
        super.drawForegroundText(mouseX, mouseY);
    }
}


