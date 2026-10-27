package mekanism.cloudworks.client.gui.element;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import mekanism.api.EnumColor;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.client.gui.element.scroll.GuiInstallableScrollList;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.util.MekanismUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import java.util.function.ToIntFunction;

/**
 * Installed-upgrade-list presentation over the mixed entry set: native
 * speed/energy rows plus the server-projected custom types (8.5-B3).
 */
public final class GuiCloudMixedUpgradeScrollList extends GuiInstallableScrollList<CloudUpgradeEntry> {

    private static final ResourceLocation UPGRADE_SELECTION = MekanismUtils.getResource(
            MekanismUtils.ResourceType.GUI, "upgrade_selection.png");
    private static final int TEXTURE_WIDTH = 100;
    private static final int TEXTURE_HEIGHT = 36;

    private final ToIntFunction<CloudUpgradeEntry> poolCount;
    private final Consumer<CloudUpgradeEntry> selectionChanged;
    private final Supplier<List<CloudUpgradeEntry>> entries;

    public GuiCloudMixedUpgradeScrollList(IGuiWrapper gui, int x, int y, int height,
            Supplier<List<CloudUpgradeEntry>> entries,
            ToIntFunction<CloudUpgradeEntry> poolCount,
            Consumer<CloudUpgradeEntry> selectionChanged) {
        super(gui, x, y, height, GuiElementHolder.HOLDER, GuiElementHolder.HOLDER_SIZE,
                UPGRADE_SELECTION, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        this.entries = entries;
        this.poolCount = poolCount;
        this.selectionChanged = selectionChanged;
    }

    @Override
    protected List<CloudUpgradeEntry> getCurrentInstalled() {
        List<CloudUpgradeEntry> installed = entries.get();
        if (selectedType != null) {
            int index = installed.indexOf(selectedType);
            // Mek's row renderer compares selection by reference, not by upgrade id.
            setSelected(index < 0 ? null : installed.get(index));
        }
        return installed;
    }

    @Override
    protected void drawName(CloudUpgradeEntry entry, int y) {
        drawNameText(y, new TextComponentString(entry.name + " ×" + poolCount.applyAsInt(entry)), titleTextColor(), 1F);
    }

    @Override
    protected ItemStack getRenderStack(CloudUpgradeEntry entry) {
        return entry.stack;
    }

    @Nullable
    @Override
    protected EnumColor getColor(CloudUpgradeEntry entry) {
        return entry.color;
    }

    @Override
    protected void setSelected(@Nullable CloudUpgradeEntry newSelection) {
        if (selectedType != newSelection) {
            selectedType = newSelection;
            selectionChanged.accept(newSelection);
        }
    }

    @Override
    public void renderToolTip(int mouseX, int mouseY) {
        super.renderToolTip(mouseX, mouseY);
        if (mouseX < getX() + 1 || mouseX >= getX() + barXShift - 1) {
            return;
        }
        List<CloudUpgradeEntry> current = getCurrentInstalled();
        int index = getCurrentSelection() + (int) ((mouseY - getY() - 1) / elementHeight);
        if (mouseY < getY() + 1 || index < 0 || index >= current.size()) {
            return;
        }
        CloudUpgradeEntry entry = current.get(index);
        List<String> lines = new ArrayList<>(1 + entry.description.size());
        lines.add(entry.name + " ×" + poolCount.applyAsInt(entry));
        lines.addAll(entry.description);
        displayTooltips(lines, mouseX, mouseY, getGuiWidth());
    }

    @Override
    protected void renderElements(int mouseX, int mouseY, float partialTicks) {
        super.renderElements(mouseX, mouseY, partialTicks);
        MekanismRenderer.resetColor();
    }
}
