package mekanism.cloudworks.client.gui;

import java.util.List;

import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.gauge.GaugeType;
import mekanism.client.gui.element.gauge.GuiEnergyGauge;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid.Entry;
import mekanism.cloudworks.client.gui.element.GuiTeamRecoveryResourceGrid;
import mekanism.cloudworks.common.inventory.container.ContainerCloudworksTeamRecovery;
import mekanism.cloudworks.common.network.CloudworksNetwork;
import mekanism.cloudworks.common.network.PacketCloudworksTeamRecovery;
import mekanism.cloudworks.common.network.PacketCloudworksTeamRecovery.Operation;
import mekanism.cloudworks.common.tile.TileEntityCloudworksTeamRecovery;
import mekanism.common.content.qio.QIOResourceEntry;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Team recovery main screen: direct take-only 8×3 resource grid with QIO-style
 * scrolling and page requests. The grid is the main content; no child resource
 * window or custom cloud motif is used.
 */
@SideOnly(Side.CLIENT)
public class GuiCloudworksTeamRecovery extends GuiConfigurableTile<TileEntityCloudworksTeamRecovery, ContainerCloudworksTeamRecovery> {

    private final List<Entry> entries = new ArrayList<>();
    private GuiTeamRecoveryResourceGrid resourceGrid;
    private int lastViewVersion = Integer.MIN_VALUE;
    private int lastOffset = -1;
    private int requestedOffset = -1;

    public GuiCloudworksTeamRecovery(EntityPlayer player, TileEntityCloudworksTeamRecovery tile) {
        super(tile, new ContainerCloudworksTeamRecovery(player.inventory, tile));
        dynamicSlots = true;
        titleLabelY = 4;
        tile.applyRecoveryState(Collections.emptyList(), -1, 0, -1);
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();
        resourceGrid = addButton(new GuiTeamRecoveryResourceGrid(this, 8, 14,
                () -> entries, (ContainerCloudworksTeamRecovery) getContainer()));
        addButton(new GuiEnergyGauge(tileEntity.getMainEnergyContainer(), GaugeType.STANDARD, this, 151, 14));
        addButton(new GuiEnergyTab(this, tileEntity.getMainEnergyContainer(), () -> true));
    }

    private void requestPage(int offset) {
        int checked = Math.max(0, offset);
        if (checked == requestedOffset) return;
        requestedOffset = checked;
        CloudworksNetwork.CHANNEL.sendToServer(new PacketCloudworksTeamRecovery.Request(getContainer().windowId, Operation.PAGE, null, checked));
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        int viewVersion = tileEntity.getRecoveryViewVersion();
        int offset = tileEntity.getRecoveryOffset();
        if (offset >= 0 && (viewVersion != lastViewVersion || offset != lastOffset)) {
            boolean switchedPages = lastOffset >= 0 && offset != lastOffset;
            lastViewVersion = viewVersion;
            lastOffset = offset;
            requestedOffset = -1;
            entries.clear();
            for (QIOResourceEntry entry : tileEntity.getRecoveryEntries()) {
                entries.add(new Entry(entry));
            }
            if (switchedPages) resourceGrid.resetScroll();
        }
        int loaded = tileEntity.getRecoveryEntries().size();
        int next = tileEntity.getRecoveryOffset() + loaded;
        if (loaded > 0 && resourceGrid.nearScrollEnd() && next < tileEntity.getRecoveryTotal()) {
            requestPage(next);
        }
    }

    @Override
    protected void drawForegroundText(int mouseX, int mouseY) {
        drawTitleText(new TextComponentString(tileEntity.getName()), 4);
        renderInventoryText();
        super.drawForegroundText(mouseX, mouseY);
    }
}
