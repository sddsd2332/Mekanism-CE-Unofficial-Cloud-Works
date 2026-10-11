package mekanism.cloudworks.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.cloudworks.client.gui.element.GuiCloudSlotScroll;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.Claim;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.Machine;
import mekanism.common.inventory.ISlotClickHandler.IScrollableSlot;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.util.LangUtils;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Machine cache view: machine types, NBT variants, and the two terminal
 * machine slots. The slot grids use Mekanism's scrolling slot presentation.
 */
@SideOnly(Side.CLIENT)
public class GuiCloudCacheWindow extends GuiWindow {

    private static final int W = 227;
    private static final int H = 163;
    private static final int LIST_Y = 26;
    private static final int LIST_COLUMNS = 5;
    private static final int LIST_ROWS = 6;

    private final ContainerCloudworks container;
    private final MekanismButton uploadButton;
    private final MekanismButton downloadButton;
    private int selectedType = -1;
    private int selectedVariant = -1;

    public GuiCloudCacheWindow(IGuiWrapper gui, int x, int y, ContainerCloudworks container) {
        super(gui, x, y, W, H, new SelectedWindowData(CloudworksWindowTypes.CLOUD_CACHE));
        this.container = container;
        interactionStrategy = InteractionStrategy.ALL;

        addChild(new GuiCloudSlotScroll(gui, relativeX + 8, relativeY + LIST_Y,
                LIST_COLUMNS, LIST_ROWS, this::typeSlots, this::selectType, () -> selectedType,
                this::typeTooltip));
        addChild(new GuiCloudSlotScroll(gui, relativeX + 115, relativeY + LIST_Y,
                LIST_COLUMNS, LIST_ROWS, this::variantSlots, this::selectVariant, () -> selectedVariant,
                ignored -> Collections.emptyList()));

        addTerminalSlot(0, relativeX + 8, relativeY + 139);
        addTerminalSlot(1, relativeX + 115, relativeY + 139);
        uploadButton = addChild(new MekanismButton(gui, relativeX + 29, relativeY + 139, 83, 18,
                new TextComponentString(tr("cache_upload_button")), this::uploadMachines, null));
        downloadButton = addChild(new MekanismButton(gui, relativeX + 136, relativeY + 139, 83, 18,
                new TextComponentString(tr("cache_download_button")), this::downloadSelectedMachine, null)
                );

        container.sendAction(Operation.CACHE_VIEW, null, "types");
    }

    private void addTerminalSlot(int index, int x, int y) {
        mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot slot = container.getTerminalSlot(index);
        if (slot != null) {
            addChild(new GuiVirtualSlot(this, SlotType.NORMAL, gui(), x, y, slot));
        }
    }

    private String tr(String key) {
        return LangUtils.localize("gui.cloudworks." + key);
    }

    private List<IScrollableSlot> typeSlots() {
        List<IScrollableSlot> slots = new ArrayList<>();
        for (Machine entry : cacheTypes()) {
            slots.add(GuiCloudSlotScroll.slot(entry.stack, entry.count));
        }
        return slots;
    }

    private List<IScrollableSlot> variantSlots() {
        List<IScrollableSlot> slots = new ArrayList<>();
        for (Machine entry : cacheVariants()) {
            slots.add(GuiCloudSlotScroll.slot(entry.stack, entry.count));
        }
        return slots;
    }

    private List<String> typeTooltip(int index) {
        List<Machine> entries = cacheTypes();
        if (index < 0 || index >= entries.size()) {
            return Collections.emptyList();
        }
        Machine entry = entries.get(index);
        List<String> lines = new ArrayList<>();
        lines.add(tr("cache_total") + ": " + entry.count);
        lines.add(tr("cache_claimed") + ": " + entry.claimed);
        if (entry.claims.isEmpty()) {
            lines.add(tr("cache_no_claims"));
        } else {
            lines.add(tr("cache_claimed_by") + ":");
            for (Claim claim : entry.claims) {
                String name = claim.name;
                if (name.isEmpty()) {
                    name = claim.line.toString();
                }
                lines.add("  " + name + " ×" + claim.count);
            }
        }
        return lines;
    }

    private List<Machine> cacheTypes() {
        return container.guiData().machineCache.types;
    }

    private List<Machine> cacheVariants() {
        return selectedType >= 0 && container.guiData().machineCache.selectedType == selectedType
                ? container.guiData().machineCache.variants : Collections.emptyList();
    }

    private void selectType(int index) {
        if (index < 0 || index >= cacheTypes().size() || index == selectedType) {
            return;
        }
        selectedType = index;
        selectedVariant = -1;
        container.sendAction(Operation.CACHE_VIEW, null, Integer.toString(index));
    }

    private void selectVariant(int index) {
        if (selectedType >= 0 && index >= 0 && index < cacheVariants().size()) {
            selectedVariant = index;
        }
    }

    private void uploadMachines() {
        container.sendAction(Operation.MACHINE_UPLOAD, null, "");
    }

    private void downloadSelectedMachine() {
        if (selectedType >= 0 && selectedVariant >= 0) {
            container.sendAction(Operation.MACHINE_DOWNLOAD, null,
                    selectedType + "," + selectedVariant + "," + (GuiScreen.isShiftKeyDown() ? 1 : 0));
        }
    }

    @Override
    public void tick() {
        super.tick();
        mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot uploadSlot =
                container.getTerminalSlot(ContainerCloudworks.SLOT_MACHINE_IN);
        uploadButton.active = uploadSlot != null && !uploadSlot.getStackToRender().isEmpty();
        if (selectedType >= cacheTypes().size()) {
            selectedType = -1;
            selectedVariant = -1;
        }
        if (selectedVariant >= cacheVariants().size()) {
            selectedVariant = -1;
        }
        downloadButton.active = selectedType >= 0 && selectedVariant >= 0;
    }

    @Override
    public void close() {
        container.sendAction(Operation.CACHE_VIEW, null, "-1");
        super.close();
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawTitleText(new TextComponentString(tr("cloud_cache")), 5);
        drawScrollingString(new TextComponentString(tr("cache_types")), 8, 16,
                TextAlignment.LEFT, 0x404040, 90, 3, false);
        drawScrollingString(new TextComponentString(tr("cache_variants")), 115, 16,
                TextAlignment.LEFT, 0x404040, 90, 3, false);
    }
}
