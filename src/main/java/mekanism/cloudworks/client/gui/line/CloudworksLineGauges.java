package mekanism.cloudworks.client.gui.line;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.gauge.GuiEnergyGauge;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import java.util.ArrayList;
import java.util.List;

public final class CloudworksLineGauges {
    private CloudworksLineGauges() {
    }

    public static GuiEnergyGauge gauge(IGuiWrapper gui, ContainerCloudworks container, int index, int x, int y) {
        GuiEnergyGauge.GaugeColor color = index == 0 ? GuiEnergyGauge.GaugeColor.RED
                : index == 3 ? GuiEnergyGauge.GaugeColor.BLUE : GuiEnergyGauge.GaugeColor.NORMAL;
        return new GuiEnergyGauge(new GuiEnergyGauge.IEnergyInfoHandler() {
            @Override
            public double getEnergy() {
                CloudworksGuiData data = container.guiData();
                return switch (index) {
                    case 0 -> data.overview.receivingEnergy;
                    case 1 -> data.overview.unlimited ? Double.MAX_VALUE : data.overview.energy;
                    case 2 -> data.graph.energy;
                    default -> data.overview.sendingEnergy;
                };
            }

            @Override
            public double getMaxEnergy() {
                CloudworksGuiData data = container.guiData();
                return Math.max(1, switch (index) {
                    case 1 -> data.overview.unlimited ? Double.MAX_VALUE : data.overview.maxEnergy;
                    case 2 -> data.graph.maxEnergy;
                    case 0 -> data.overview.receivingCapacity;
                    default -> data.overview.sendingCapacity;
                });
            }
        }, GuiEnergyGauge.Type.STANDARD.asGaugeType(), gui, x, y) {
            @Override
            public List<String> getTooltipText() {
                List<String> lines = new ArrayList<>();
                lines.add(GuiLineWindow.tr(switch (index) {
                    case 0 -> "receiving_energy";
                    case 1 -> "cloud_energy_pool";
                    case 2 -> "line_energy_pool";
                    default -> "sending_energy";
                }));
                lines.addAll(super.getTooltipText());
                return lines;
            }
        }.withColor(color);
    }
}
