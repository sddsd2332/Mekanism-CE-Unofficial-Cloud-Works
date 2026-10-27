package mekanism.cloudworks.client.gui.element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

import mekanism.api.EnumColor;
import mekanism.api.gas.GasStack;
import mekanism.api.qio.client.QIOResourceRenderer;
import mekanism.api.qio.client.QIOResourceRendererRegistry;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.scroll.GuiScrollBar;
import mekanism.client.jei.interfaces.IJEIIngredientHelper;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.MekanismLang;
import mekanism.common.content.qio.QIOAmount;
import mekanism.common.content.qio.QIOResourceEntry;
import mekanism.common.content.qio.QIOResourceKind;
import mekanism.common.util.LangUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.UnitDisplayUtils;
import mekanism.common.util.text.TextUtils;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import java.util.Arrays;
import mekanism.common.Mekanism;

/**
 * QIO terminal resource grid presentation for read-only Cloudworks snapshots.
 */
public class GuiCloudQIOResourceGrid extends GuiElement implements IJEIIngredientHelper {

    private static final ResourceLocation SLOTS = MekanismUtils.getResource(
            MekanismUtils.ResourceType.GUI_SLOT, "slots.png");
    private static final ResourceLocation SLOTS_DARK = MekanismUtils.getResource(
            MekanismUtils.ResourceType.GUI_SLOT, "slots_dark.png");

    private final int columns;
    private final int rows;
    private final Supplier<List<Entry>> entriesSupplier;
    private final GuiScrollBar scrollBar;
    private List<Entry> cachedSortedEntries = Collections.emptyList();


    public GuiCloudQIOResourceGrid(IGuiWrapper gui, int x, int y, int columns, int rows,
                                   Supplier<List<Entry>> entriesSupplier) {
        super(gui, x, y, columns * 18 + 18, rows * 18);
        this.columns = Math.max(1, columns);
        this.rows = Math.max(1, rows);
        this.entriesSupplier = entriesSupplier;
        scrollBar = addChild(new GuiScrollBar(gui, relativeX + this.columns * 18, relativeY, this.rows * 18, this::getTotalRows, () -> this.rows));
        refreshSortedEntries();
        active = true;
    }

    @Override
    public void tick() {
        super.tick();
        refreshSortedEntries();
    }

    @Override
    public void drawBackground(int mouseX, int mouseY, float partialTicks) {
        super.drawBackground(mouseX, mouseY, partialTicks);
        List<Entry> entries = getSortedEntries();
        minecraft.renderEngine.bindTexture(entries.isEmpty() ? SLOTS_DARK : SLOTS);
        for (int rowStart = 0; rowStart < rows; rowStart += 16) {
            int rowCount = Math.min(16, rows - rowStart);
            GuiUtils.blit(relativeX, relativeY + rowStart * 18, 0, 0,
                    columns * 18, rowCount * 18, 288, 288);
        }
        int first = scrollBar.getCurrentSelection() * columns;
        int visibleSlots = columns * rows;
        for (int slot = 0; slot < visibleSlots; slot++) {
            int index = first + slot;
            if (index >= entries.size()) {
                break;
            }
            renderEntry(entries.get(index).resource, relativeX + slot % columns * 18,
                    relativeY + slot / columns * 18);
        }
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        List<Entry> entries = getSortedEntries();
        int first = scrollBar.getCurrentSelection() * columns;
        int hoveredSlot = getSlot(mouseX, mouseY);
        for (int visible = 0; visible < columns * rows; visible++) {
            int index = first + visible;
            if (index >= entries.size()) {
                break;
            }
            QIOResourceEntry entry = entries.get(index).resource;
            int x = relativeX + visible % columns * 18;
            int y = relativeY + visible / columns * 18;
            CloudworksSlotRenderer.draw(x, y, isSelected(entry), true, visible == hoveredSlot);
        }
    }

    protected boolean isSelected(QIOResourceEntry entry) {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return isMouseOverCheckWindows(mouseX, mouseY) && scrollBar.adjustScroll(delta)
                || super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void renderToolTip(int mouseX, int mouseY) {
        MekanismRenderer.resetGuiRenderState();
        super.renderToolTip(mouseX, mouseY);
        int slot = getSlot(mouseX, mouseY);
        List<Entry> entries = getSortedEntries();
        int index = slot < 0 ? -1 : scrollBar.getCurrentSelection() * columns + slot;
        if (index < 0 || index >= entries.size()) {
            return;
        }
        QIOResourceEntry entry = entries.get(index).resource;
        ResolvedRenderer rendered = resolveRenderer(entry);
        if (rendered != null) {
            List<String> tooltip = new ArrayList<>();
            List<String> renderedLines = rendered.renderer.getTooltip(rendered.resource);
            if (renderedLines != null) {
                tooltip.addAll(renderedLines);
            }
            if (tooltip.isEmpty()) {
                tooltip.add(getResourceName(entry));
            }
            tooltip.add(getStoredTooltip(entry.getExactAmount()));
            displayTooltips(tooltip, mouseX, mouseY);
        } else if (entry.getKind() == QIOResourceKind.ITEM && !entry.getItem().isEmpty()) {
            gui().renderItemTooltipWithExtra(entry.getItem(), mouseX, mouseY,
                    Collections.singletonList(getStoredTooltip(entry.getExactAmount())));
        } else {
            displayTooltips(Arrays.asList(getResourceName(entry),
                    getStoredTooltip(entry.getExactAmount())), mouseX, mouseY);
        }
        MekanismRenderer.resetGuiRenderState();
    }

    @Override
    public Object getIngredient(double mouseX, double mouseY) {
        int slot = getSlot(mouseX, mouseY);
        List<Entry> entries = getSortedEntries();
        int index = slot < 0 ? -1 : scrollBar.getCurrentSelection() * columns + slot;
        if (index < 0 || index >= entries.size()) {
            return null;
        }
        QIOResourceEntry entry = entries.get(index).resource;
        ResolvedRenderer rendered = resolveRenderer(entry);
        if (rendered != null) {
            return rendered.renderer.getIngredient(rendered.resource);
        }
        QIOResourceKind kind = entry.getKind();
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case ITEM -> entry.getItem();
            case FLUID -> entry.getFluid();
            case GAS -> entry.getGas();
            default -> null;
        };
    }

    protected List<Entry> getSortedEntries() {
        return cachedSortedEntries;
    }

    private void refreshSortedEntries() {
        List<Entry> entries = entriesSupplier.get();
        if (entries == null || entries.isEmpty()) {
            cachedSortedEntries = Collections.emptyList();
            return;
        }
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(entry -> getResourceName(entry.resource),
                String.CASE_INSENSITIVE_ORDER));
        cachedSortedEntries = Collections.unmodifiableList(sorted);
    }

    private int getTotalRows() {
        return (getSortedEntries().size() + columns - 1) / columns;
    }

    protected Entry entryAt(double mouseX, double mouseY) {
        int slot = getSlot(mouseX, mouseY);
        List<Entry> entries = getSortedEntries();
        int index = slot < 0 ? -1 : scrollBar.getCurrentSelection() * columns + slot;
        return index < 0 || index >= entries.size() ? null : entries.get(index);
    }

    public void resetScroll() {
        scrollBar.resetScroll();
    }

    private int getSlot(double mouseX, double mouseY) {
        if (mouseX < getX() || mouseY < getY()
                || mouseX >= getX() + columns * 18 || mouseY >= getY() + rows * 18) {
            return -1;
        }
        int slotX = (int) ((mouseX - getGuiLeft() - relativeX) / 18);
        int slotY = (int) ((mouseY - getGuiTop() - relativeY) / 18);
        if (slotX < 0 || slotY < 0 || slotX >= columns || slotY >= rows) {
            return -1;
        }
        int slotStartX = getX() + slotX * 18 + 1;
        int slotStartY = getY() + slotY * 18 + 1;
        if (mouseX < slotStartX || mouseX >= slotStartX + 16
                || mouseY < slotStartY || mouseY >= slotStartY + 16
                || !checkWindows(mouseX, mouseY)) {
            return -1;
        }
        return slotY * columns + slotX;
    }

    private void renderEntry(QIOResourceEntry entry, int x, int y) {
        boolean rendered = renderWithRegisteredRenderer(entry, x + 1, y + 1);
        QIOResourceKind kind = entry.getKind();
        if (!rendered && kind == QIOResourceKind.ITEM && !entry.getItem().isEmpty()) {
            gui().renderItemWithOverlay(entry.getItem(), x + 1, y + 1, 1, "");
            rendered = true;
        }
        if (!rendered) {
            int color = resourceColor(entry);
            GuiUtils.fill(x + 3, y + 3, x + 15, y + 15, color);
            String marker = "?";
            if (kind == QIOResourceKind.FLUID) {
                FluidStack fluid = entry.getFluid();
                if (fluid != null) {
                    GuiUtils.drawFluidBarSprite(x, y, 18, 18, 16, fluid, true);
                    marker = "F";
                }
            } else if (kind == QIOResourceKind.GAS) {
                GasStack gas = entry.getGas();
                if (gas != null) {
                    GuiUtils.drawGasBarSprite(x, y, 18, 18, 16, gas, true);
                    marker = "G";
                }
            }
            GlStateManager.pushMatrix();
            GlStateManager.translate(x + 5, y + 4, 100);
            GlStateManager.scale(0.75F, 0.75F, 0.75F);
            getFont().drawString(marker, 0, 0, 0xFFFFFFFF);
            GlStateManager.popMatrix();
        }
        String count = getCountText(entry.getExactAmount());
        if (count != null) {
            renderSlotText(count, x + 1, y + 1, entry.getExactAmount().isZero()
                    ? 0xFFFFFF55 : 0xFFFFFFFF);
        }
    }

    private boolean renderWithRegisteredRenderer(QIOResourceEntry entry, int x, int y) {
        ResolvedRenderer resolved = resolveRenderer(entry);
        if (resolved == null) {
            return false;
        }
        GlStateManager.pushMatrix();
        try {
            resolved.renderer.render(resolved.resource, x, y);
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        } finally {
            GlStateManager.popMatrix();
            MekanismRenderer.resetColor();
        }
    }

    @SuppressWarnings("unchecked")
    private ResolvedRenderer resolveRenderer(QIOResourceEntry entry) {
        if (!entry.getDescriptor().isResolved()) {
            return null;
        }
        QIOResourceRenderer<?> renderer = QIOResourceRendererRegistry.INSTANCE.get(
                entry.getDescriptor().getCodecId());
        if (renderer == null) {
            return null;
        }
        Object resource = entry.getDescriptor().resolve();
        if (resource == null || !renderer.getValueClass().isInstance(resource)) {
            return null;
        }
        return new ResolvedRenderer((QIOResourceRenderer<Object>) renderer, resource);
    }

    protected String getResourceName(QIOResourceEntry entry) {
        ResolvedRenderer rendered = resolveRenderer(entry);
        if (rendered != null) {
            try {
                String name = rendered.renderer.getDisplayName(rendered.resource);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            } catch (RuntimeException | LinkageError ignored) {
                if (Mekanism.logger != null)
                    Mekanism.logger.debug("Could not resolve Cloudworks QIO resource display name {}",
                            entry.getDescriptor().getCodecId(), ignored);
            }
        }
        QIOResourceKind kind = entry.getKind();
        if (kind == null) {
            return entry.getDescriptor().getCodecId().toString();
        }
        switch (kind) {
            case ITEM -> {
                ItemStack item = entry.getItem();
                if (!item.isEmpty()) return item.getDisplayName();
            }
            case FLUID -> {
                FluidStack fluid = entry.getFluid();
                if (fluid != null && fluid.getFluid() != null) return fluid.getLocalizedName();
                return LangUtils.localize("gui.qio.resource.unknown_fluid");
            }
            case GAS -> {
                GasStack gas = entry.getGas();
                if (gas != null && gas.getGas() != null) return gas.getGas().getLocalizedName();
                return LangUtils.localize("gui.qio.resource.unknown_gas");
            }
            default -> {
            }
        }
        return entry.getDescriptor().getCodecId().toString();
    }

    protected String getStoredTooltip(QIOAmount amount) {
        return MekanismLang.QIO_STORED_COUNT.translateColored(EnumColor.GREY, EnumColor.INDIGO,
                TextUtils.format(amount.toBigInteger())).getFormattedText();
    }

    private String getCountText(QIOAmount count) {
        if (count.isZero()) {
            return "0";
        }
        if (!count.isExpanded() && count.longValueClamped() == 1) {
            return null;
        }
        if (!count.isExpanded()) {
            long value = count.longValueClamped();
            return value < 10_000 ? Long.toString(value) : UnitDisplayUtils.getDisplay(value, 1);
        }
        String digits = count.toString();
        String decimals = digits.substring(1, Math.min(3, digits.length()));
        return digits.charAt(0) + (decimals.isEmpty() ? "" : "." + decimals)
                + "e" + (digits.length() - 1);
    }

    private int resourceColor(QIOResourceEntry entry) {
        int hash = entry.getUUID().hashCode();
        return 0xFF000000 | (80 + (hash & 0x7F)) << 16
                | (80 + ((hash >>> 8) & 0x7F)) << 8 | 80 + ((hash >>> 16) & 0x7F);
    }

    private void renderSlotText(String text, int x, int y, int color) {
        GlStateManager.pushMatrix();
        MekanismRenderer.resetColor();
        float scale = 0.6F;
        int width = getFont().getStringWidth(text);
        if (width > 0) {
            scale = Math.min(1, 16F / (width * scale)) * scale;
        }
        float yAdd = 4 - scale * 4;
        GlStateManager.translate(x + 16 - width * scale, y + 9 + yAdd, 200);
        GlStateManager.scale(scale, scale, scale);
        getFont().drawString(text, 0, 0, color);
        GlStateManager.popMatrix();
    }

    public static final class Entry {
        private final QIOResourceEntry resource;

        public QIOResourceDescriptor descriptor() {
            return resource.getDescriptor();
        }

        /**
         * Exposes the complete entry for interaction rules that need the
         * resource kind and stored amount, matching the native QIO grid
         * selection helpers.
         */
        public QIOResourceEntry resourceEntry() {
            return resource;
        }

        public Entry(QIOResourceDescriptor descriptor, long amount) {
            UUID id = UUID.nameUUIDFromBytes(descriptor.write().toString().getBytes(StandardCharsets.UTF_8));
            resource = QIOResourceEntry.of(id, descriptor, QIOAmount.of(amount));
        }

        public Entry(QIOResourceEntry resource) {
            this.resource = resource;
        }
    }

    private static final class ResolvedRenderer {
        private final QIOResourceRenderer<Object> renderer;
        private final Object resource;

        private ResolvedRenderer(QIOResourceRenderer<Object> renderer, Object resource) {
            this.renderer = renderer;
            this.resource = resource;
        }
    }
}
