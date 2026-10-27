package mekanism.cloudworks.client.gui.element;

import java.util.List;
import java.util.function.Supplier;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.scroll.GuiScrollBar;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid.Entry;
import mekanism.cloudworks.common.inventory.container.ContainerCloudworksTeamRecovery;
import mekanism.cloudworks.common.network.CloudworksNetwork;
import mekanism.cloudworks.common.network.PacketCloudworksTeamRecovery;
import mekanism.cloudworks.common.network.PacketCloudworksTeamRecovery.Operation;
import mekanism.common.content.qio.QIOResourceEntry;
import mekanism.common.content.qio.QIOResourceKind;
import mekanism.common.network.qio.PacketQIOViewerAction;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Take-only 8×3 view of the team recovery buffer: plain left click takes one
 * stack unit, Shift+left click takes up to the per-slot cap (items 8192,
 * chemicals 8192000), and nothing can ever be deposited. Fluid and gas entries
 * require a compatible carried container, matching the native QIO panel.
 */
@SideOnly(Side.CLIENT)
public class GuiTeamRecoveryResourceGrid extends GuiCloudQIOResourceGrid {

    private static final int COLUMNS = 7;
    private static final int ROWS = 3;

    private final ContainerCloudworksTeamRecovery container;

    public GuiTeamRecoveryResourceGrid(IGuiWrapper gui, int x, int y,
            Supplier<List<Entry>> entries, ContainerCloudworksTeamRecovery container) {
        super(gui, x, y, COLUMNS, ROWS, entries);
        this.container = container;
    }

    @Override
    protected boolean isValidClickButton(int button) {
        return button == 0 || button == 1 || button == 2;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOverCheckWindows(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        Entry entry = entryAt(mouseX, mouseY);
        if (entry == null) {
            // Keep the scrollbar and the window's child elements interactive.
            return super.mouseClicked(mouseX, mouseY, button);
        }

        QIOResourceEntry resource = entry.resourceEntry();
        ItemStack held = minecraft.player.inventory.getItemStack();
        QIOResourceKind kind = resource.getKind();
        if (kind == QIOResourceKind.FLUID) {
            // A fluid entry is only actionable with a container that can accept
            // this exact fluid. With an empty cursor the server scans the
            // player's inventory and offhand, matching the native QIO panel.
            if (button == 0 && (held.isEmpty()
                    || PacketQIOViewerAction.canTakeFluidIntoHeldStack(resource, held))) {
                onClick(mouseX, mouseY, button);
            }
            return true;
        }
        if (kind == QIOResourceKind.GAS) {
            // Same rule as the native QIO panel for gas containers.
            if (button == 0 && (held.isEmpty()
                    || PacketQIOViewerAction.canTakeGasIntoHeldStack(resource, held))) {
                onClick(mouseX, mouseY, button);
            }
            return true;
        }
        if (kind == QIOResourceKind.ITEM) {
            if (GuiScreen.isShiftKeyDown()) {
                onClick(mouseX, mouseY, button);
            } else if (held.isEmpty() || (button == 0 || button == 2)
                    && PacketQIOViewerAction.canTakeIntoHeldStack(resource, held)) {
                onClick(mouseX, mouseY, button);
            }
            return true;
        }

        // There is no generic 1.12 container interaction for custom families.
        return true;
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        Entry entry = entryAt(mouseX, mouseY);
        if (entry == null) {
            return;
        }
        Operation operation = GuiScreen.isShiftKeyDown() ? Operation.TAKE_ALL : Operation.TAKE_STACK;
        long amount = operation == Operation.TAKE_ALL ? 0 : requestedAmount(entry, button);
        if (amount <= 0) {
            return;
        }
        // Carry the current page so the server response keeps the view in place.
        int offset = Math.max(0, container.getTileEntity().getRecoveryOffset());
        CloudworksNetwork.CHANNEL.sendToServer(new PacketCloudworksTeamRecovery.Request(
                container.windowId, operation, entry.descriptor(), offset, amount));
    }

    private long requestedAmount(Entry entry, int button) {
        QIOResourceEntry resource = entry.resourceEntry();
        long stored = resource.getAmount();
        if (resource.getKind() != QIOResourceKind.ITEM) {
            return stored;
        }
        ItemStack item = resource.getItem();
        long max = Math.min(stored, item.isEmpty() ? 0 : item.getMaxStackSize());
        if (button == 1) {
            return Math.max(1, max / 2);
        } else if (button == 2) {
            return Math.min(1, max);
        }
        return max;
    }

    /**
     * True when the visible window is within one grid row of the last loaded entry — the
     * signal for the owning window to pull the next server page before the player reaches it.
     */
    public boolean nearScrollEnd() {
        int firstRow = -1;
        for (GuiElement child : children()) {
            if (child instanceof GuiScrollBar scrollBar) {
                firstRow = scrollBar.getCurrentSelection();
                break;
            }
        }
        List<Entry> entries = getSortedEntries();
        if (firstRow < 0 || entries.isEmpty()) {
            return true;
        }
        int totalRows = (entries.size() + COLUMNS - 1) / COLUMNS;
        return firstRow + ROWS + 1 >= totalRows;
    }
}
