package mekanism.cloudworks.client.gui;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.gauge.GuiEnergyGauge;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import mekanism.common.util.LangUtils;
import mekanism.generators.client.gui.element.GuiStateTexture;
import mekanism.generators.common.MekanismGenerators;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

@SideOnly(Side.CLIENT)
public class GuiCloudEnergyWindow extends mekanism.client.gui.element.window.GuiWindow {

    private final ContainerCloudworks container;

    public GuiCloudEnergyWindow(IGuiWrapper gui, int x, int y, ContainerCloudworks container) {
        super(gui, x, y, 76, 171, new SelectedWindowData(CloudworksWindowTypes.CLOUD_ENERGY));
        this.container = container;
        interactionStrategy = InteractionStrategy.ALL;

        addGauge(gui, 8, 30, GuiEnergyGauge.GaugeColor.RED, () -> container.guiData().overview.receivingEnergy,
                () -> container.guiData().overview.receivingCapacity, () -> false);
        addGauge(gui, 29, 30, GuiEnergyGauge.GaugeColor.NORMAL, () -> container.guiData().overview.energy,
                () -> container.guiData().overview.unlimited ? Double.MAX_VALUE : container.guiData().overview.maxEnergy,
                () -> container.guiData().overview.unlimited);
        addGauge(gui, 50, 30, GuiEnergyGauge.GaugeColor.BLUE, () -> container.guiData().overview.sendingEnergy,
                () -> container.guiData().overview.sendingCapacity, () -> false);

        addChild(new GuiStateTexture(gui, relativeX + 9, relativeY + 96, () -> container.guiData().overview.receivingToCloud, texture("wind_on.png"), texture("wind_off.png")));
        addChild(new GuiStateTexture(gui, relativeX + 9, relativeY + 117, () -> container.guiData().overview.cloudToSending, texture("wind_on.png"), texture("wind_off.png")));

        addChild(new MekanismButton(gui, relativeX + 29, relativeY + 95, 39, 18, new TextComponentString(tr("flow_r2c")), this::toggleReceivingGate, null));
        addChild(new MekanismButton(gui, relativeX + 29, relativeY + 116, 39, 18, new TextComponentString(tr("flow_c2s")), this::toggleSendingGate, null));

        VirtualInventoryContainerSlot slot = container.getTerminalSlot(ContainerCloudworks.SLOT_ENERGY_IN);
        if (slot != null) {
            addChild(new GuiVirtualSlot(this, SlotType.NORMAL, gui, relativeX + 8, relativeY + 137, slot));
        }
        addChild(new MekanismButton(gui, relativeX + 29, relativeY + 137, 39, 18, new TextComponentString(tr("energy_upload")), this::uploadEnergy, null));
    }

    private void addGauge(IGuiWrapper gui, int x, int y, GuiEnergyGauge.GaugeColor color,
            DoubleSupplier energy, DoubleSupplier capacity, BooleanSupplier unlimited) {
        addChild(new GuiEnergyGauge(new GuiEnergyGauge.IEnergyInfoHandler() {
            @Override
            public double getEnergy() {
                return unlimited.getAsBoolean() ? Double.MAX_VALUE : energy.getAsDouble();
            }

            @Override
            public double getMaxEnergy() {
                return Math.max(1D, capacity.getAsDouble());
            }
        }, GuiEnergyGauge.Type.STANDARD.asGaugeType(), gui,
                relativeX + x, relativeY + y).withColor(color));
    }

    private ResourceLocation texture(String name) {
        return new ResourceLocation(MekanismGenerators.MODID, "gui/" + name);
    }

    private String tr(String key) {
        return LangUtils.localize("gui.cloudworks." + key);
    }

    private void toggleReceivingGate() {
        container.sendAction(Operation.ENERGY_FLOW, null, "r2c=" + (container.guiData().overview.receivingToCloud ? 0 : 1));
    }

    private void toggleSendingGate() {
        container.sendAction(Operation.ENERGY_FLOW, null, "c2s=" + (container.guiData().overview.cloudToSending ? 0 : 1));
    }

    private void uploadEnergy() {
        container.sendAction(Operation.ENERGY_UPLOAD_CONFIRM, null, "");
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawTitleText(new TextComponentString(tr("cloud_energy")), 18);
    }
}
