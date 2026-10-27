package mekanism.cloudworks.client.gui;

import java.util.ArrayList;
import java.util.List;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.Overview;
import mekanism.common.util.LangUtils;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import mekanism.cloudworks.common.service.CloudworksOutputMultiplier;

public final class GuiCloudStatusWindow extends GuiWindow {
    private final ContainerCloudworks container;

    public GuiCloudStatusWindow(IGuiWrapper gui, int x, int y, ContainerCloudworks container) {
        super(gui, x, y, 244, 156, CloudworksWindowTypes.CLOUD_STATUS);
        this.container = container;
        interactionStrategy = InteractionStrategy.ALL;
        addChild(new GuiInnerScreen(gui, relativeX + 8, relativeY + 22, 228, 126, this::status)
                .clearFormat().padding(5).spacing(2).textScale(0.8F));
    }

    private List<ITextComponent> status() {
        Overview data = container.guiData().overview;
        List<ITextComponent> lines = new ArrayList<>();
        add(lines, "time", LangUtils.localize("cloudworks.editor.time_" + CloudworksGenerationContext.timeOfDay(data.clockTick))
                + " " + CloudworksGenerationContext.clockTime(data.clockTick));
        add(lines, "offline", yesNo(data.offlineUnlocked));
        add(lines, "offline_allowed", yesNo(data.offlineProcessingEnabled));
        add(lines, "work_rate", data.workRate);
        add(lines, "output_multiplier",
                CloudworksOutputMultiplier.format(data.outputMultiplierUnits) + "x");
        add(lines, "lines", data.total + " / " + data.unlocked + " / " + data.maximum);
        add(lines, "nodes", data.maxMachineNodes + " / " + data.machineNodeLimit);
        add(lines, "machines_per_node", data.maxMachinesPerNode);
        // Generic cloud-effect levels (8.6): addon ids are displayed verbatim; built-in
        // effects keep their dedicated status rows above (no double truth).
        for (CloudworksGuiData.EffectLevel effect : data.effectLevels) {
            String key = "cloudworks.cloud.status_effect_" + effect.id;
            String label = LangUtils.canLocalize(key) ? LangUtils.localize(key) : effect.id;
            lines.add(new TextComponentString(label + ": " + effect.level));
        }
        return lines;
    }

    private static String yesNo(boolean value) {
        return LangUtils.localize("cloudworks.cloud.status_" + (value ? "yes" : "no"));
    }

    private static void add(List<ITextComponent> lines, String key, Object value) {
        lines.add(new TextComponentString(LangUtils.localize("cloudworks.cloud.status_" + key) + ": " + value));
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawTitleText(new TextComponentString(LangUtils.localize("cloudworks.cloud.tab_status")), 6);
    }
}
