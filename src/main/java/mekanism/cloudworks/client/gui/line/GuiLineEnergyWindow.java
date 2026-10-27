package mekanism.cloudworks.client.gui.line;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiTextureOnlyElement;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;

public final class GuiLineEnergyWindow extends GuiLineWindow {
    private static final SelectedWindowData.WindowType TYPE = type("energy");

    private final CloudworksLineEditorModel model;
    private final GuiTextureOnlyElement receivingOn;
    private final GuiTextureOnlyElement receivingOff;
    private final GuiTextureOnlyElement sendingOn;
    private final GuiTextureOnlyElement sendingOff;

    public GuiLineEnergyWindow(IGuiWrapper gui, ContainerCloudworks container, CloudworksLineEditorModel model) {
        super(gui, 30, 96, 218, 115, "energy_gates", TYPE);
        this.model = model;
        for (int i = 0; i < 4; i++)
            addChild(CloudworksLineGauges.gauge(gui, container, i, relativeX + 68 + i * 21, relativeY + 23));
        // Mekanism Generators is an optional dependency: keep its textures by
        // literal namespace but never load its client classes (the same
        // pattern as GuiCloudEnergyWindow).
        ResourceLocation on = new ResourceLocation("mekanismgenerators", "gui/wind_on.png");
        ResourceLocation off = new ResourceLocation("mekanismgenerators", "gui/wind_off.png");
        addChild(new MekanismButton(gui, relativeX + 8, relativeY + 89, 77, 18,
                new TextComponentString(tr("flow_r2l")), () -> model.setReceivingFlow(!model.draft.receivingFlow), null));
        receivingOn = addChild(new GuiTextureOnlyElement(on, gui, relativeX + 88, relativeY + 90, 16, 16));
        receivingOff = addChild(new GuiTextureOnlyElement(off, gui, relativeX + 88, relativeY + 90, 16, 16));
        addChild(new MekanismButton(gui, relativeX + 115, relativeY + 89, 77, 18,
                new TextComponentString(tr("flow_l2s")), () -> model.setSendingFlow(!model.draft.sendingFlow), null));
        sendingOn = addChild(new GuiTextureOnlyElement(on, gui, relativeX + 195, relativeY + 90, 16, 16));
        sendingOff = addChild(new GuiTextureOnlyElement(off, gui, relativeX + 195, relativeY + 90, 16, 16));
        updateFlowTextures();
    }

    @Override
    public void tick() {
        super.tick();
        updateFlowTextures();
    }

    private void updateFlowTextures() {
        receivingOn.visible = model.draft.receivingFlow;
        receivingOff.visible = !model.draft.receivingFlow;
        sendingOn.visible = model.draft.sendingFlow;
        sendingOff.visible = !model.draft.sendingFlow;
    }
}
