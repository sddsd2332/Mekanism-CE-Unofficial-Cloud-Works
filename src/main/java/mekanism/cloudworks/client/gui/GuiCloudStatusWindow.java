package mekanism.cloudworks.client.gui;

import java.util.ArrayList;
import java.util.List;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.cloudworks.api.CloudworksGenerationContext;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.Overview;
import mekanism.common.util.LangUtils;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

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
        add(lines, "height", data.simulatedHeight);
        add(lines, "time", LangUtils.localize("cloudworks.editor.time_" + CloudworksGenerationContext.timeOfDay(data.clockTick))
                + " " + CloudworksGenerationContext.clockTime(data.clockTick));
        add(lines, "offline", yesNo(data.offlineUnlocked));
        add(lines, "offline_allowed", yesNo(data.offlineProcessingEnabled));
        add(lines, "lava", data.lavaHeatCards);
        add(lines, "nether", yesNo(data.netherEnvironment));
        add(lines, "work_rate", data.workRate);
        add(lines, "lines", data.total + " / " + data.unlocked + " / " + data.maximum);
        add(lines, "nodes", data.maxMachineNodes + " / " + data.machineNodeLimit);
        add(lines, "machines_per_node", data.maxMachinesPerNode);
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
