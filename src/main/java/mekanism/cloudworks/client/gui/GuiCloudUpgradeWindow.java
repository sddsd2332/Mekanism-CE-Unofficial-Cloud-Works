package mekanism.cloudworks.client.gui;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.button.DigitalButton;
import mekanism.client.gui.element.custom.GuiSupportedUpgrades;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.cloudworks.client.gui.element.GuiCloudUpgradeScrollList;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import mekanism.common.MekanismLang;
import mekanism.common.Upgrade;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.TextComponentString;
import mekanism.common.util.LangUtils;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@SideOnly(Side.CLIENT)
public class GuiCloudUpgradeWindow extends GuiWindow {

    private static final int WIDTH = 198;

    private final ContainerCloudworks container;
    private final Map<Upgrade, WrappedTextRenderer> upgradeText = new LinkedHashMap<>();
    private final WrappedTextRenderer noSelection =
            new WrappedTextRenderer(this, MekanismLang.UPGRADE_NO_SELECTION.translate());
    private final GuiCloudUpgradeScrollList scrollList;
    private final GuiInnerScreen rightScreen;
    private final DigitalButton removeButton;
    private Upgrade selectedUpgrade;
    private long selectedAt;

    public GuiCloudUpgradeWindow(IGuiWrapper gui, int x, int y, ContainerCloudworks container) {
        super(gui, x, y, WIDTH, windowHeight(gui), CloudworksWindowTypes.CLOUD_UPGRADE_DATA);
        this.container = container;
        interactionStrategy = InteractionStrategy.ALL;

        scrollList = addChild(new GuiCloudUpgradeScrollList(gui, relativeX + 6, relativeY + 18, 50,
                this::poolCount, this::setSelection));
        addChild(new GuiSupportedUpgrades(gui, relativeX + 6, relativeY + 68, new java.util.LinkedHashSet<>(Arrays.asList(Upgrade.SPEED, Upgrade.ENERGY))));
        rightScreen = addChild(new GuiInnerScreen(gui, scrollList.getRelativeRight(), relativeY + 18, 59, 50));
        removeButton = addChild(new DigitalButton(gui, scrollList.getRelativeRight() + 1,
                relativeY + 54, 56, 12, MekanismLang.UPGRADE_UNINSTALL,
                (element, mouseX, mouseY) -> {
                    downloadSelectedUpgrade();
                    return true;
                }).setTooltip(MekanismLang.UPGRADE_UNINSTALL_TOOLTIP.translate()));

        addTerminalSlot(ContainerCloudworks.SLOT_UPGRADE_IN,
                rightScreen.getRelativeRight() + 2, relativeY + 18);
        addTerminalSlot(ContainerCloudworks.SLOT_UPGRADE_OUT,
                rightScreen.getRelativeRight() + 2, relativeY + 72);
        updateEnabledButtons();
    }

    private static int windowHeight(IGuiWrapper gui) {
        return 76 + Math.max(18, 12 * GuiSupportedUpgrades.calculateNeededRows(gui));
    }

    private void addTerminalSlot(int index, int x, int y) {
        mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot slot = container.getTerminalSlot(index);
        if (slot != null) {
            addChild(new GuiVirtualSlot(this, SlotType.NORMAL, gui(), x, y, slot));
        }
    }

    private int poolCount(Upgrade upgrade) {
        return container.upgradePool(upgrade == Upgrade.SPEED);
    }

    private void setSelection(Upgrade upgrade) {
        if (selectedUpgrade != upgrade) {
            selectedUpgrade = upgrade;
            selectedAt = GuiElement.getMillis();
            updateEnabledButtons();
        }
    }

    private void updateEnabledButtons() {
        removeButton.active = selectedUpgrade != null && poolCount(selectedUpgrade) > 0;
    }

    private void downloadSelectedUpgrade() {
        if (selectedUpgrade != null) {
            container.sendAction(Operation.UPGRADE_DOWNLOAD, null, (selectedUpgrade == Upgrade.SPEED ? "speed" : "energy") + "," + (GuiScreen.isShiftKeyDown() ? 1 : 0));
        }
    }

    @Override
    public void tick() {
        super.tick();
        updateEnabledButtons();
        if (selectedUpgrade != null && poolCount(selectedUpgrade) == 0) {
            scrollList.clearSelection();
        }
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawTitleText(new TextComponentString(LangUtils.localize(
                "gui.cloudworks.cloud_upgrade")), 5);
        if (selectedUpgrade == null) {
            noSelection.renderWithScale(rightScreen.getRelativeX() + 2, rightScreen.getRelativeY() + 2,
                    screenTextColor(), rightScreen.getWidth() - 4, 0.8F);
            return;
        }
        int count = poolCount(selectedUpgrade);
        WrappedTextRenderer text = upgradeText.computeIfAbsent(selectedUpgrade,
                upgrade -> new WrappedTextRenderer(this, MekanismLang.UPGRADE_TYPE.translate(upgrade.getName())));
        int screenWidth = rightScreen.getWidth() - 4;
        int lines = text.renderWithScale(rightScreen.getRelativeX() + 2, rightScreen.getRelativeY() + 2,
                screenTextColor(), screenWidth, 0.6F);
        int textY = 4 + 6 * lines;
        rightScreen.drawScaledScrollingString(new TextComponentString(
                LangUtils.localizeWithFormat("gui.cloudworks.upgrade_pool_count", count)),
                2, textY, TextAlignment.LEFT, screenTextColor(), screenWidth, 2, false, 0.6F, selectedAt);
        textY += 7;
        rightScreen.drawScaledScrollingString(new TextComponentString(selectedUpgrade.getDescription()), 2, textY, TextAlignment.LEFT, screenTextColor(), screenWidth, 2, false, 0.6F, selectedAt);
    }

    @Override
    public boolean hasPersistentData() {
        return true;
    }
}
