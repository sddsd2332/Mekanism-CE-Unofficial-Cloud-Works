package mekanism.cloudworks.client.gui.line;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.data.CloudworksThermalState;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.common.util.LangUtils;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

/** Uses the authoritative GUI packet sample; no NBT access or native world queries. */
final class GuiLineThermalScreen extends GuiInnerScreen {
    GuiLineThermalScreen(IGuiWrapper gui, int x, int y, ContainerCloudworks container, UUID id) {
        super(gui, x, y, 214, 50, () -> text(container, id));
    }

    private static List<ITextComponent> text(ContainerCloudworks container, UUID id) {
        List<ITextComponent> result = new ArrayList<>();
        for (CloudworksGuiData.Node node : container.guiData().graph.nodes) {
            if (!id.equals(node.id) || node.thermal == null) continue;
            CloudworksThermalState state = node.thermal;
            result.add(new TextComponentString(LangUtils.localizeWithFormat("cloudworks.thermal.temperature",
                    String.format(java.util.Locale.ROOT, "%.2f", state.temperature()))));
            result.add(new TextComponentString(LangUtils.localizeWithFormat("cloudworks.thermal.stored",
                    String.format(java.util.Locale.ROOT, "%.2f", state.heat))));
            result.add(new TextComponentString(LangUtils.localizeWithFormat("cloudworks.thermal.rates",
                    String.format(java.util.Locale.ROOT, "%.2f", state.produced),
                    String.format(java.util.Locale.ROOT, "%.2f", state.consumed),
                    String.format(java.util.Locale.ROOT, "%.2f", state.loss))));
            break;
        }
        return result;
    }
}
