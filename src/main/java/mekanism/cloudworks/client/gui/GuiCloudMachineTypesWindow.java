package mekanism.cloudworks.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.machine.CloudworksMachineInfo;
import mekanism.cloudworks.client.gui.element.GuiCloudSlotScroll;
import mekanism.cloudworks.common.content.CloudworksTerminalSlots;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.common.inventory.ISlotClickHandler.IScrollableSlot;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.util.LangUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import java.util.Collections;
import net.minecraft.nbt.NBTTagCompound;

/** Client-local catalogue of concrete machine identities accepted by the upload slot. */
@SideOnly(Side.CLIENT)
public final class GuiCloudMachineTypesWindow extends GuiWindow {
    private static final int WIDTH = 180;
    private static final int HEIGHT = 120;
    private static final int COLUMNS = 8;
    private static final int ROWS = 5;

    private final List<ItemStack> machineTypes;
    private final Consumer<GuiCloudMachineTypesWindow> onClose;
    private boolean closeNotified;

    public GuiCloudMachineTypesWindow(IGuiWrapper gui, int x, int y,
            Consumer<GuiCloudMachineTypesWindow> onClose) {
        super(gui, x, y, WIDTH, HEIGHT, new SelectedWindowData(CloudworksWindowTypes.CLOUD_MACHINE_TYPES));
        this.onClose = onClose == null ? ignored -> {} : onClose;
        interactionStrategy = InteractionStrategy.ALL;
        machineTypes = supportedMachineTypes();
        addChild(new GuiCloudSlotScroll(gui, relativeX + 8, relativeY + 20, COLUMNS, ROWS,
                this::slots, ignored -> {}, () -> -1, ignored -> Collections.emptyList()));
    }

    private static List<ItemStack> supportedMachineTypes() {
        List<ItemStack> result = new ArrayList<>();
        for (CloudworksMachineInfo info : CloudworksAPI.machineInfos()) {
            ItemStack machine = info.machine();
            if (!info.registered() || !CloudworksTerminalSlots.acceptsMachine(machine)) continue;
            machine.setCount(1);
            result.add(machine);
        }
        result.sort(Comparator
                .comparing((ItemStack stack) -> stack.getItem().getRegistryName() == null
                        ? "" : stack.getItem().getRegistryName().toString())
                .thenComparingInt(ItemStack::getItemDamage)
                .thenComparing(GuiCloudMachineTypesWindow::tagSortKey));
        return result;
    }

    private static String tagSortKey(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag == null ? "" : tag.toString();
    }

    private List<IScrollableSlot> slots() {
        List<IScrollableSlot> result = new ArrayList<>(machineTypes.size());
        for (ItemStack stack : machineTypes) result.add(GuiCloudSlotScroll.slot(stack, 1));
        return result;
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawTitleText(new TextComponentString(LangUtils.localize("gui.cloudworks.cloud_machine_types")), 5);
    }

    @Override
    public void close() {
        if (closeNotified) return;
        closeNotified = true;
        super.close();
        onClose.accept(this);
    }
}
