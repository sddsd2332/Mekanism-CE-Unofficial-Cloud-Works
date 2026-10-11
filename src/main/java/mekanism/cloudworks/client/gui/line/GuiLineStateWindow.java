package mekanism.cloudworks.client.gui.line;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.util.text.TextComponentString;

public final class GuiLineStateWindow extends GuiLineWindow {
    private static final SelectedWindowData.WindowType TYPE = type("state");
    private final MekanismButton start, stop, offlineOn, offlineOff;
    private final BooleanSupplier running, ready, offlineUnlocked, offlineRunning, offlineAllowed;

    public GuiLineStateWindow(IGuiWrapper gui, BooleanSupplier running, BooleanSupplier ready,
            Consumer<Boolean> setState, BooleanSupplier offlineUnlocked, BooleanSupplier offlineAllowed, BooleanSupplier offlineRunning,
            Consumer<Boolean> setOffline) {
        super(gui, 30, 52, 154, windowHeight(offlineUnlocked.getAsBoolean()), "state_control", TYPE);
        this.running = running;
        this.ready = ready;
        this.offlineUnlocked = offlineUnlocked;
        this.offlineRunning = offlineRunning;
        this.offlineAllowed = offlineAllowed;
        start = addChild(new MekanismButton(gui, relativeX + 8, relativeY + 23, 66, 18,
                new TextComponentString(tr("start")), () -> setState.accept(true), null));
        stop = addChild(new MekanismButton(gui, relativeX + 80, relativeY + 23, 66, 18,
                new TextComponentString(tr("stop")), () -> setState.accept(false), null));
        offlineOn = addChild(new MekanismButton(gui, relativeX + 8, relativeY + 44, 66, 18,
                new TextComponentString(tr("offline_on")), () -> setOffline.accept(true), null));
        offlineOff = addChild(new MekanismButton(gui, relativeX + 80, relativeY + 44, 66, 18,
                new TextComponentString(tr("offline_off")), () -> setOffline.accept(false), null));
        tick();
    }

    public static int windowHeight(boolean unlocked) { return unlocked ? 70 : 49; }

    @Override
    public void tick() {
        super.tick();
        start.active = ready.getAsBoolean() && !running.getAsBoolean();
        stop.active = ready.getAsBoolean() && running.getAsBoolean();
        boolean unlocked = offlineUnlocked.getAsBoolean();
        height = windowHeight(unlocked);
        offlineOn.visible = offlineOff.visible = unlocked;
        offlineOn.active = unlocked && offlineAllowed.getAsBoolean() && ready.getAsBoolean() && !offlineRunning.getAsBoolean();
        offlineOff.active = unlocked && ready.getAsBoolean() && offlineRunning.getAsBoolean();
    }
}
