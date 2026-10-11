package mekanism.cloudworks.client.gui.element;

import java.util.List;
import java.util.function.Supplier;
import mekanism.api.gas.GasStack;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.scroll.GuiScrollList;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fluids.FluidStack;

public final class GuiCloudResourceList extends GuiScrollList {

    private final Supplier<List<Entry>> entries;

    public GuiCloudResourceList(IGuiWrapper gui, int x, int y, int width, int height,
            Supplier<List<Entry>> entries) {
        super(gui, x, y, width, height, 20, GuiInnerScreen.SCREEN, GuiInnerScreen.SCREEN_SIZE);
        this.entries = entries;
    }

    @Override
    protected int getMaxElements() {
        return entries.get().size();
    }

    @Override
    public boolean hasSelection() {
        return false;
    }

    @Override
    protected void setSelected(int index) {
    }

    @Override
    public void clearSelection() {
    }

    @Override
    protected void renderElements(int mouseX, int mouseY, float partialTicks) {
        List<Entry> list = entries.get();
        int first = getCurrentSelection();
        int count = Math.min(getFocusedElements(), Math.max(0, list.size() - first));
        for (int row = 0; row < count; row++) {
            Entry entry = list.get(first + row);
            int rowY = relativeY + 2 + row * elementHeight;
            if (mouseX >= getX() + 1 && mouseX < getX() + barXShift - 1
                    && mouseY >= getY() + 1 + row * elementHeight
                    && mouseY < getY() + 1 + (row + 1) * elementHeight) {
                GuiUtils.fill(relativeX + 1, rowY - 1, relativeX + barXShift - 1,
                        rowY + elementHeight - 1, 0x405F737D);
            }
            renderResource(entry.resource, relativeX + 3, rowY);
            String name = getFont().trimStringToWidth(displayName(entry.resource),
                    Math.max(8, barXShift - 72));
            drawScrollingString(new TextComponentString(name), 22, rowY - relativeY + 1,
                    TextAlignment.LEFT, 0xFF4CDBB4, barXShift - 72, 3, false);
            String amount = "×" + entry.amount;
            drawScrollingString(new TextComponentString(amount), 22, rowY - relativeY + 1,
                    TextAlignment.RIGHT, 0xFFB6E7D7, barXShift - 26, 3, false);
        }
    }

    private void renderResource(QIOResourceDescriptor resource, int x, int y) {
        if (resource == null) return;
        try {
            switch (resource.getFamily()) {
                case "item" -> {
                    ItemStack item = resource.resolveItemStackTemplate();
                    if (item != null && !item.isEmpty()) {
                        gui().renderItemWithOverlay(item, x, y, 1, "");
                    }
                }
                case "fluid" -> {
                    FluidStack fluid = resource.resolveFluidStackTemplate();
                    if (fluid != null) GuiUtils.drawFluidBarSprite(x - 1, y - 1, 18, 18, 16, fluid, true);
                }
                case "gas" -> {
                    GasStack gas = resource.resolveGasStackTemplate();
                    if (gas != null) GuiUtils.drawGasBarSprite(x - 1, y - 1, 18, 18, 16, gas, true);
                }
                default -> GuiUtils.fill(x + 2, y + 2, x + 14, y + 14, 0xFF727272);
            }
        } catch (RuntimeException ignored) {
            GuiUtils.fill(x + 2, y + 2, x + 14, y + 14, 0xFF727272);
        }
    }

    private String displayName(QIOResourceDescriptor resource) {
        if (resource == null) return "?";
        try {
            switch (resource.getFamily()) {
                case "item" -> {
                    ItemStack item = resource.resolveItemStackTemplate();
                    if (item != null && !item.isEmpty()) return item.getDisplayName();
                }
                case "fluid" -> {
                    FluidStack fluid = resource.resolveFluidStackTemplate();
                    if (fluid != null) return fluid.getLocalizedName();
                }
                case "gas" -> {
                    GasStack gas = resource.resolveGasStackTemplate();
                    if (gas != null && gas.getGas() != null) return gas.getGas().getLocalizedName();
                }
                default -> {
                }
            }
        } catch (RuntimeException ignored) {
        }
        return resource.getCodecId().toString();
    }

    public static final class Entry {
        public final QIOResourceDescriptor resource;
        public final long amount;

        public Entry(QIOResourceDescriptor resource, long amount) {
            this.resource = resource;
            this.amount = amount;
        }
    }
}
