package mekanism.cloudworks.client.gui.element;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.slot.GuiSlot;
import mekanism.client.gui.element.scroll.GuiScrollBar;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.inventory.ISlotClickHandler.IScrollableSlot;
import mekanism.common.lib.inventory.HashedItem;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.UnitDisplayUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;

import static mekanism.client.gui.GuiUtils.blit;
import static mekanism.client.gui.GuiUtils.fill;

/**
 * Mekanism's GuiSlotScroll with a small Cloudworks-specific tooltip hook and
 * selected-slot rendering.
 */
public class GuiCloudSlotScroll extends GuiElement {

    private static final ResourceLocation SLOTS = MekanismUtils.getResource(
            MekanismUtils.ResourceType.GUI_SLOT, "slots.png");
    private static final ResourceLocation SLOTS_DARK = MekanismUtils.getResource(
            MekanismUtils.ResourceType.GUI_SLOT, "slots_dark.png");

    private final int xSlots;
    private final int ySlots;
    private final Supplier<List<IScrollableSlot>> slotList;
    private final IntConsumer clickHandler;
    private final IntSupplier selectedIndex;
    private final Function<Integer, List<String>> tooltipLines;
    private final GuiScrollBar scrollBar;
    private java.util.function.IntPredicate enabledSlot = index -> true;

    public GuiCloudSlotScroll enabledSlots(java.util.function.IntPredicate predicate) {
        enabledSlot = predicate;
        return this;
    }

    public GuiCloudSlotScroll(IGuiWrapper gui, int x, int y, int xSlots, int ySlots,
            Supplier<List<IScrollableSlot>> slotList, IntConsumer clickHandler,
            IntSupplier selectedIndex, Function<Integer, List<String>> tooltipLines) {
        super(gui, x, y, xSlots * 18 + 18, ySlots * 18);
        this.xSlots = xSlots;
        this.ySlots = ySlots;
        this.slotList = slotList;
        this.clickHandler = clickHandler;
        this.selectedIndex = selectedIndex;
        this.tooltipLines = tooltipLines;
        scrollBar = addChild(new GuiScrollBar(gui, relativeX + xSlots * 18, y, ySlots * 18,
                () -> (int) Math.ceil((double) getSlotList().size() / xSlots), () -> ySlots));
    }

    @Override
    public void drawBackground(int mouseX, int mouseY, float partialTicks) {
        super.drawBackground(mouseX, mouseY, partialTicks);
        List<IScrollableSlot> list = getSlotList();
        minecraft.renderEngine.bindTexture(list.isEmpty() ? SLOTS_DARK : SLOTS);
        blit(relativeX, relativeY, 0, 0, xSlots * 18, ySlots * 18, 288, 288);
        int slotStart = scrollBar.getCurrentSelection() * xSlots;
        for (int i = 0; i < xSlots * ySlots; i++) {
            int slot = slotStart + i;
            if (slot >= list.size()) {
                break;
            }
            int slotX = relativeX + (i % xSlots) * 18;
            int slotY = relativeY + (i / xSlots) * 18;
            if (slot == selectedIndex.getAsInt()) {
                fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, GuiSlot.DEFAULT_HOVER_COLOR);
                MekanismRenderer.resetColor();
            }
            ItemStack stack = list.get(slot).getInternalStack();
            if (!stack.isEmpty()) {
                gui().renderItemWithOverlay(stack, slotX + 1, slotY + 1, 1, "");
                renderSlotCount(list.get(slot).count(), slotX + 1, slotY + 1);
            }
            if (!enabledSlot.test(slot)) {
                fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0xA0808080);
                MekanismRenderer.resetColor();
            }
        }
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        int slot = slotAt(mouseX, mouseY);
        if (slot >= 0 && slot == selectedIndex.getAsInt()) {
            int local = slot - scrollBar.getCurrentSelection() * xSlots;
            int slotX = relativeX + local % xSlots * 18 + 1;
            int slotY = relativeY + local / xSlots * 18 + 1;
            fill(slotX, slotY, slotX + 16, slotY + 16, GuiSlot.DEFAULT_HOVER_COLOR);
            MekanismRenderer.resetColor();
        }
    }

    @Override
    public void renderToolTip(int mouseX, int mouseY) {
        super.renderToolTip(mouseX, mouseY);
        int slot = slotAt(mouseX, mouseY);
        List<IScrollableSlot> list = getSlotList();
        if (slot < 0 || slot >= list.size()) {
            return;
        }
        ItemStack stack = list.get(slot).getInternalStack();
        List<String> extra = tooltipLines.apply(slot);
        if (extra == null || extra.isEmpty()) {
            gui().renderItemTooltip(stack, mouseX, mouseY);
        } else {
            gui().renderItemTooltipWithExtra(stack, mouseX, mouseY, extra);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return isMouseOverCheckWindows(mouseX, mouseY) && scrollBar.adjustScroll(delta)
                || super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        int slot = slotAt(mouseX, mouseY);
        if (slot >= 0 && button == 0 && enabledSlot.test(slot)) {
            clickHandler.accept(slot);
        }
    }

    private List<IScrollableSlot> getSlotList() {
        List<IScrollableSlot> list = slotList.get();
        return list == null ? Collections.emptyList() : list;
    }

    private int slotAt(double mouseX, double mouseY) {
        int slotX = (int) ((mouseX - getX()) / 18);
        int slotY = (int) ((mouseY - getY()) / 18);
        int slotStartX = getX() + slotX * 18 + 1;
        int slotStartY = getY() + slotY * 18 + 1;
        if (mouseX < slotStartX || mouseX >= slotStartX + 16
                || mouseY < slotStartY || mouseY >= slotStartY + 16
                || slotX < 0 || slotY < 0 || slotX >= xSlots || slotY >= ySlots
                || !checkWindows(mouseX, mouseY)) {
            return -1;
        }
        int slot = (slotY + scrollBar.getCurrentSelection()) * xSlots + slotX;
        return slot < getSlotList().size() ? slot : -1;
    }

    private void renderSlotCount(long count, int x, int y) {
        if (count <= 1) {
            return;
        }
        String text = count < 10_000 ? Long.toString(count) : UnitDisplayUtils.getDisplay(count, 1);
        MekanismRenderer.resetColor();
        float scale = 0.6F;
        int width = getFont().getStringWidth(text);
        scale = Math.min(1, 16F / (width * scale)) * scale;
        drawTextWithScale(new TextComponentString(text), x + 16 - width * scale,
                y + 9, 0xFFFFFF, scale);
    }

    public static IScrollableSlot slot(ItemStack stack, long count) {
        return new IScrollableSlot() {
            private final HashedItem item = HashedItem.create(stack);

            @Override
            public HashedItem item() {
                return item;
            }

            @Override
            public java.util.UUID itemUUID() {
                return null;
            }

            @Override
            public long count() {
                return count;
            }
        };
    }
}
