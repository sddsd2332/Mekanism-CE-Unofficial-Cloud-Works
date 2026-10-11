package mekanism.cloudworks.client.gui;

import java.util.List;
import java.util.ArrayList;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.common.util.LangUtils;
import mekanism.common.content.qio.QIOResourceEntry;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

/**
 * 回收站子窗（手动确认制）: bin contents list | mode cycle (转移到中转/转移到输出/清空)
 * + confirm. Confirm on "清空" pops a second confirmation window unless Shift is
 * held (direct clear). Both buttons are disabled while the bin is empty.
 */
@SideOnly(Side.CLIENT)
public class GuiCloudRecycleWindow extends GuiWindow {

    private static final int W = 244, H = 139;
    private final ContainerCloudworks container;
    private final GuiCloudQIOResourceGrid entries;
    private final MekanismButton modeButton, confirmButton;
    private RecycleMode mode = RecycleMode.TO_TRANSFER;
    private boolean armed, modeChanged, savedToTransfer;

    public GuiCloudRecycleWindow(IGuiWrapper gui, int x, int y, ContainerCloudworks container) {
        super(gui, x, y, W, H, new SelectedWindowData(CloudworksWindowTypes.CLOUD_RECYCLE));
        this.container = container;
        savedToTransfer = container.guiData().overview.recycleToTransfer;
        mode = savedToTransfer ? RecycleMode.TO_TRANSFER : RecycleMode.TO_SENDING;
        interactionStrategy = InteractionStrategy.ALL;
        entries = addChild(new GuiCloudQIOResourceGrid(gui, relativeX + 8, relativeY + 20, 12, 5, this::resourceEntries));
        modeButton = addChild(new MekanismButton(gui, relativeX + 8, relativeY + 115, 113, 18,
                new TextComponentTranslation(mode.key()), this::cycleMode, null));
        confirmButton = addChild(new MekanismButton(gui, relativeX + 125, relativeY + 115, 113, 18,
                new TextComponentTranslation("gui.cloudworks.recycle_confirm"), this::confirm, null));
        container.sendAction(Operation.RECYCLE_VIEW, null, "1");
    }

    private String tr(String key) { return LangUtils.localize("gui.cloudworks." + key); }

    private void cycleMode() {
        modeChanged = true;
        RecycleMode[] values = RecycleMode.values();
        mode = values[(mode.ordinal() + 1) % values.length];
        armed = false;
    }

    private void confirm() {
        if (mode == RecycleMode.CLEAR && !armed && !shiftHeld()) {
            armed = true;   // first click arms; second click (or Shift) executes
            return;
        }
        armed = false;
        switch (mode) {
            case TO_TRANSFER -> container.sendAction(Operation.RECYCLE_ROUTE, null, "transfer");
            case TO_SENDING -> container.sendAction(Operation.RECYCLE_ROUTE, null, "sending");
            case CLEAR -> container.sendAction(Operation.RECYCLE_CLEAR, null, "");
        }
    }

    private static boolean shiftHeld() {
        return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
    }

    @Override
    public void tick() {
        super.tick();
        boolean saved = container.guiData().overview.recycleToTransfer;
        if (!modeChanged && saved != savedToTransfer)
            mode = saved ? RecycleMode.TO_TRANSFER : RecycleMode.TO_SENDING;
        savedToTransfer = saved;
        boolean empty = container.guiData().recycle.isEmpty();
        modeButton.active = !empty;
        confirmButton.active = !empty;
        modeButton.setMessage(new TextComponentTranslation(mode.key()));
        confirmButton.setMessage(new TextComponentTranslation(armed ? "gui.cloudworks.recycle_confirm_again"
              : "gui.cloudworks.recycle_confirm"));
    }

    private List<GuiCloudQIOResourceGrid.Entry> resourceEntries() {
        List<GuiCloudQIOResourceGrid.Entry> values = new ArrayList<>();
        for (QIOResourceEntry resource : container.guiData().recycle) {
            values.add(new GuiCloudQIOResourceGrid.Entry(resource));
        }
        return values;
    }

    @Override
    public void close() {
        container.sendAction(Operation.RECYCLE_VIEW, null, "0");
        super.close();
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawTitleText(new TextComponentTranslation("gui.cloudworks.cloud_recycle"), 5);
    }

    private enum RecycleMode {
        TO_TRANSFER("gui.cloudworks.recycle_to_transfer"),
        TO_SENDING("gui.cloudworks.recycle_to_sending"),
        CLEAR("gui.cloudworks.recycle_clear");

        private final String key;

        RecycleMode(String key) { this.key = key; }

        String key() { return key; }
    }
}
