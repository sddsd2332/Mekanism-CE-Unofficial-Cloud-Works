package mekanism.cloudworks.client.gui.line;

import java.util.UUID;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.cloudworks.common.recipe.CloudworksFusionNodeContracts;
import net.minecraft.util.text.TextComponentString;

/** An editor property: changing injection updates this node's contract and keeps its stored heat. */
public final class GuiLineFusionSettingsWindow extends GuiLineWindow {
    private final CloudworksLineEditorModel model;
    private final UUID node;
    private final GuiTextField injection;
    private final MekanismButton apply;
    public GuiLineFusionSettingsWindow(IGuiWrapper gui, CloudworksLineEditorModel model, UUID node, int current) {
        super(gui, 45, 40, 210, 112, "fusion_settings", type("machine"));
        this.model = model; this.node = node;
        injection = addChild(new GuiTextField(gui, relativeX + 8, relativeY + 49, 109, 18).setMaxLength(10));
        injection.setText(Integer.toString(current));
        apply = addChild(new MekanismButton(gui, relativeX + 123, relativeY + 49, 79, 18,
                new TextComponentString(tr("fusion_apply")), this::apply, null));
    }
    private boolean valid() {
        try { CloudworksFusionNodeContracts.validateInjection(Integer.parseInt(injection.getText().trim())); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private void apply() {
        if (!valid()) return;
        model.setFusionInjectionRate(node, Integer.parseInt(injection.getText().trim())); close();
    }
    @Override public void tick() { super.tick(); apply.active = valid(); }
    @Override public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawScrollingString(new TextComponentString(tr("fusion_injection") + " (0–" + CloudworksFusionNodeContracts.maximumInjection() + ")"),
                8, 27, TextAlignment.LEFT, 0x404040, 194, 2, false);
        drawScrollingString(new TextComponentString(tr("fusion_injection_hint")), 8, 83,
                TextAlignment.LEFT, 0x404040, 194, 2, false);
    }
}
