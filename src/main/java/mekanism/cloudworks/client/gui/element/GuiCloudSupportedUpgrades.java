package mekanism.cloudworks.client.gui.element;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import mekanism.api.EnumColor;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.module.MachineModuleSource;
import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.client.render.MekanismRenderer;
import mekanism.cloudworks.api.module.CloudworksUpgradeItems;
import mekanism.common.MekanismLang;
import mekanism.common.Upgrade;
import mekanism.common.util.LangUtils;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;

/** Mek-style supported-upgrade strip using Cloudworks' registered item templates. */
public final class GuiCloudSupportedUpgrades extends GuiElement {
    private static final int WIDTH = 167;
    private static final int ELEMENT_SIZE = 12;
    private static final int ROW_WIDTH = WIDTH - 2;
    private static final int ROW_ROOM = ROW_WIDTH / ELEMENT_SIZE;
    private static final TextComponentString TITLE =
            new TextComponentString(LangUtils.localize("gui.upgrades.supported"));
    private final Predicate<Upgrade> supported;
    private final Predicate<CustomUpgradeInfo> customSupported;
    private final Supplier<List<CustomUpgradeInfo>> customTypes;
    private List<CustomUpgradeInfo> seenCustomTypes;
    private List<CloudworksSupportedUpgradeView.Entry> upgrades = Collections.emptyList();
    private final int firstRowStart;
    private final int firstRowRoom;

    public GuiCloudSupportedUpgrades(IGuiWrapper gui, int x, int y) {
        this(gui, x, y, ignored -> true);
    }

    public GuiCloudSupportedUpgrades(IGuiWrapper gui, int x, int y, Predicate<Upgrade> supported) {
        this(gui, x, y, supported, Collections::emptyList, ignored -> true);
    }

    public GuiCloudSupportedUpgrades(IGuiWrapper gui, int x, int y, Predicate<Upgrade> supported,
            Supplier<List<CustomUpgradeInfo>> customTypes,
            Predicate<CustomUpgradeInfo> customSupported) {
        super(gui, x, y, WIDTH, ELEMENT_SIZE * calculateNeededRows(gui, customTypes.get()) + 2);
        this.supported = supported == null ? ignored -> true : supported;
        this.customSupported = customSupported == null ? ignored -> true : customSupported;
        this.customTypes = customTypes;
        firstRowStart = Math.min(gui.getFont().getStringWidth(TITLE.getFormattedText()) + 1, ROW_WIDTH);
        firstRowRoom = Math.max(1, (ROW_WIDTH - firstRowStart) / ELEMENT_SIZE);
        active = true;
    }

    public static int calculateNeededRows(IGuiWrapper gui) {
        return calculateNeededRows(gui, Collections.emptyList());
    }

    /**
     * Client-known upgrade types from the frozen local registry. Capability is a
     * client fact (both sides register the same types); only installed state and
     * cloud pool counts are server facts. Cached: the registry freezes before
     * any GUI exists, and {@link #refreshEntries()} compares supplier results
     * by reference.
     */
    public static List<CustomUpgradeInfo> localCustomTypes() {
        List<CustomUpgradeInfo> cached = LOCAL_CUSTOM_TYPES;
        if (cached == null) {
            List<CustomUpgradeInfo> built = new ArrayList<>();
            for (MachineModuleType<?> type : CloudworksAPI.machineModuleTypes())
                if (type.slot() == MachineModuleType.Slot.CUSTOM
                        && type.acceptsSource(MachineModuleSource.UPGRADE))
                    built.add(CustomUpgradeInfo.fromRegistry(type.id().toString()));
            cached = Collections.unmodifiableList(built);
            LOCAL_CUSTOM_TYPES = cached;
        }
        return cached;
    }

    private static volatile List<CustomUpgradeInfo> LOCAL_CUSTOM_TYPES;

    public static int calculateNeededRows(IGuiWrapper gui, Collection<CustomUpgradeInfo> customTypes) {
        int first = Math.min(gui.getFont().getStringWidth(TITLE.getFormattedText()) + 1, ROW_WIDTH);
        int firstRoom = Math.max(1, (ROW_WIDTH - first) / ELEMENT_SIZE);
        return CloudworksSupportedUpgradeView.rows(CloudworksSupportedUpgradeView.entries(customTypes).size(), firstRoom, ROW_ROOM);
    }

    private void refreshEntries() {
        List<CustomUpgradeInfo> current = customTypes.get();
        if (current != seenCustomTypes) {
            seenCustomTypes = current;
            upgrades = CloudworksSupportedUpgradeView.entries(current);
            height = ELEMENT_SIZE * CloudworksSupportedUpgradeView.rows(upgrades.size(), firstRowRoom, ROW_ROOM) + 2;
        }
    }

    private boolean supported(CloudworksSupportedUpgradeView.Entry entry) {
        return entry.nativeUpgrade == null ? customSupported.test(entry.custom) : supported.test(entry.nativeUpgrade);
    }
    private ItemStack stack(CloudworksSupportedUpgradeView.Entry entry) {
        return entry.nativeUpgrade == null ? entry.custom.item : CloudworksUpgradeItems.currentStack(entry.nativeUpgrade);
    }

    @Override
    public void drawBackground(int mouseX, int mouseY, float partialTicks) {
        super.drawBackground(mouseX, mouseY, partialTicks);
        refreshEntries();
        renderBackgroundTexture(GuiElementHolder.HOLDER, GuiElementHolder.HOLDER_SIZE, GuiElementHolder.HOLDER_SIZE);
        int overlay = (GuiElementHolder.getBackgroundColor() & 0x00FFFFFF) | 0x80000000;
        for (int index = 0; index < upgrades.size(); index++) {
            int[] position = position(index);
            int itemX = relativeX + 1 + position[0], itemY = relativeY + 1 + position[1];
            gui().renderItem(stack(upgrades.get(index)), itemX, itemY, 0.75F);
            if (!supported(upgrades.get(index))) renderUnsupportedOverlay(itemX, itemY, overlay);
        }
        MekanismRenderer.resetGuiRenderState();
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawScrollingString(TITLE, 0, 3, TextAlignment.LEFT, titleTextColor(), 2, false);
    }

    @Override
    public void renderToolTip(int mouseX, int mouseY) {
        super.renderToolTip(mouseX, mouseY);
        refreshEntries();
        for (int index = 0; index < upgrades.size(); index++) {
            int[] position = position(index);
            int itemX = x + 1 + position[0], itemY = y + 1 + position[1];
            if (mouseX < itemX || mouseX >= itemX + ELEMENT_SIZE
                    || mouseY < itemY || mouseY >= itemY + ELEMENT_SIZE) continue;
            CloudworksSupportedUpgradeView.Entry entry = upgrades.get(index);
            ItemStack stack = stack(entry);
            List<String> tooltip = new ArrayList<>();
            String name = stack.isEmpty() ? entry.nativeUpgrade.getName() : stack.getDisplayName();
            if (!supported(entry))
                // Mek-style "Name (Not Supported)" in red; the format key takes the name.
                tooltip.add(MekanismLang.UPGRADE_NOT_SUPPORTED
                        .translateColored(EnumColor.RED, name).getFormattedText());
            else
                tooltip.add(name);
            tooltip.addAll(entry.nativeUpgrade == null
                    ? CloudUpgradeEntry.custom(entry.custom.id).description
                    : Collections.singletonList(entry.nativeUpgrade.getDescription()));
            displayTooltips(tooltip, mouseX, mouseY);
            return;
        }
    }

    private int[] position(int index) {
        int row = index < firstRowRoom ? 0 : 1 + (index - firstRowRoom) / ROW_ROOM;
        if (row == 0) return new int[]{firstRowStart + index * ELEMENT_SIZE, 0};
        int offset = index - firstRowRoom;
        return new int[]{(offset % ROW_ROOM) * ELEMENT_SIZE, row * ELEMENT_SIZE};
    }

    private void renderUnsupportedOverlay(int x, int y, int color) {
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(0, 0, 100);
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GuiUtils.fill(x, y, x + ELEMENT_SIZE, y + ELEMENT_SIZE, color);
        } finally {
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.popMatrix();
            MekanismRenderer.resetColor();
        }
    }
}
