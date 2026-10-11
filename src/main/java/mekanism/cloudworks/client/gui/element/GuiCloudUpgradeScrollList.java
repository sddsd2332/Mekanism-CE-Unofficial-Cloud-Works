package mekanism.cloudworks.client.gui.element;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import mekanism.api.EnumColor;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.client.gui.element.scroll.GuiInstallableScrollList;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.Upgrade;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;

import javax.annotation.Nullable;
import java.util.function.ToIntFunction;

/**
 * Installed-upgrade-list presentation backed by the cloud upgrade pool.
 */
public final class GuiCloudUpgradeScrollList extends GuiInstallableScrollList<Upgrade> {

    private static final ResourceLocation UPGRADE_SELECTION = MekanismUtils.getResource(
            MekanismUtils.ResourceType.GUI, "upgrade_selection.png");
    private static final int TEXTURE_WIDTH = 100;
    private static final int TEXTURE_HEIGHT = 36;

    private final ToIntFunction<Upgrade> poolCount;
    private final Consumer<Upgrade> selectionChanged;

    public GuiCloudUpgradeScrollList(IGuiWrapper gui, int x, int y, int height,
            ToIntFunction<Upgrade> poolCount, Consumer<Upgrade> selectionChanged) {
        super(gui, x, y, height, GuiElementHolder.HOLDER, GuiElementHolder.HOLDER_SIZE,
                UPGRADE_SELECTION, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        this.poolCount = poolCount;
        this.selectionChanged = selectionChanged;
    }

    @Override
    protected List<Upgrade> getCurrentInstalled() {
        return Arrays.asList(Upgrade.SPEED, Upgrade.ENERGY);
    }

    @Override
    protected void drawName(Upgrade upgrade, int y) {
        String label = upgrade.getName() + " ×" + poolCount.applyAsInt(upgrade);
        drawNameText(y, new TextComponentString(label), titleTextColor(), 1F);
    }

    @Override
    protected ItemStack getRenderStack(Upgrade upgrade) {
        return UpgradeUtils.getStack(upgrade);
    }

    @Nullable
    @Override
    protected EnumColor getColor(Upgrade upgrade) {
        return upgrade.getColor();
    }

    @Override
    protected void setSelected(@Nullable Upgrade newSelection) {
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
        List<Upgrade> upgrades = getCurrentInstalled();
        int index = getCurrentSelection() + (int) ((mouseY - getY() - 1) / elementHeight);
        if (mouseY < getY() + 1 || index < 0 || index >= upgrades.size()) {
            return;
        }
        Upgrade upgrade = upgrades.get(index);
        displayTooltips(Arrays.asList(
                upgrade.getName() + " ×" + poolCount.applyAsInt(upgrade),
                upgrade.getDescription()), mouseX, mouseY, getGuiWidth());
    }

    @Override
    protected void renderElements(int mouseX, int mouseY, float partialTicks) {
        super.renderElements(mouseX, mouseY, partialTicks);
        MekanismRenderer.resetColor();
    }
}
