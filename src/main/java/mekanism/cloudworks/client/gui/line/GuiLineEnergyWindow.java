package mekanism.cloudworks.client.gui.line;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.generators.client.gui.element.GuiStateTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;

public final class GuiLineEnergyWindow extends GuiLineWindow {
    private static final SelectedWindowData.WindowType TYPE = type("energy");

    public GuiLineEnergyWindow(IGuiWrapper gui, ContainerCloudworks container, CloudworksLineEditorModel model) {
        super(gui, 30, 96, 218, 115, "energy_gates", TYPE);
        for (int i = 0; i < 4; i++)
            addChild(CloudworksLineGauges.gauge(gui, container, i, relativeX + 68 + i * 21, relativeY + 23));
        ResourceLocation on = new ResourceLocation("mekanismgenerators", "gui/wind_on.png");
        ResourceLocation off = new ResourceLocation("mekanismgenerators", "gui/wind_off.png");
        addChild(new MekanismButton(gui, relativeX + 8, relativeY + 89, 77, 18,
                new TextComponentString(tr("flow_r2l")), () -> model.setReceivingFlow(!model.draft.receivingFlow), null));
        addChild(new GuiStateTexture(gui, relativeX + 88, relativeY + 90, () -> model.draft.receivingFlow, on, off));
        addChild(new MekanismButton(gui, relativeX + 115, relativeY + 89, 77, 18,
                new TextComponentString(tr("flow_l2s")), () -> model.setSendingFlow(!model.draft.sendingFlow), null));
        addChild(new GuiStateTexture(gui, relativeX + 195, relativeY + 90, () -> model.draft.sendingFlow, on, off));
    }
}
