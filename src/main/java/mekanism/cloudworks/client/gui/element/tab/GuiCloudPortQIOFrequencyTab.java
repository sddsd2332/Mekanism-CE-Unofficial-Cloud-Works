package mekanism.cloudworks.client.gui.element.tab;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.custom.GuiFrequencySelector.IGuiColorFrequencySelector;
import mekanism.client.gui.element.tab.GuiQIOFrequencyTab;
import mekanism.client.gui.element.window.GuiQIOFrequencySelectWindow;
import mekanism.cloudworks.common.tile.prefab.TileEntityCloudworksPort;
import mekanism.common.Mekanism;
import mekanism.common.content.qio.QIOFrequency;
import mekanism.common.frequency.Frequency.FrequencyIdentity;
import mekanism.common.frequency.FrequencyType;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.network.PacketSetTileFrequency.SetTileFrequencyMessage;

/** Adapts the electric port to Mek's unmodified QIO frequency tab and selector window. */
public final class GuiCloudPortQIOFrequencyTab extends GuiQIOFrequencyTab<TileEntityCloudworksPort> {
    public GuiCloudPortQIOFrequencyTab(IGuiWrapper gui, TileEntityCloudworksPort tile,
            Supplier<GuiCloudPortQIOFrequencyTab> self) {
        super(gui, tile, self);
        move(0, 56);
        visible = tile.hasQIOUpgrade();
    }

    @Override protected GuiQIOFrequencySelectWindow createWindow(SelectedWindowData data) {
        return new GuiQIOFrequencySelectWindow(gui(), new Selector(dataSource), data);
    }

    @Override public void tick() {
        super.tick();
        visible = dataSource.hasQIOUpgrade();
    }
    @Override public void openPinnedWindows() {
        if (dataSource.hasQIOUpgrade()) super.openPinnedWindows();
    }

    private static final class Selector implements IGuiColorFrequencySelector<QIOFrequency> {
        private final TileEntityCloudworksPort tile;

        private Selector(TileEntityCloudworksPort tile) { this.tile = tile; }
        @Override public void sendSetFrequency(FrequencyIdentity identity) {
            Mekanism.packetHandler.sendToServer(new SetTileFrequencyMessage(true, FrequencyType.QIO, identity, tile));
        }
        @Override public void sendRemoveFrequency(FrequencyIdentity identity) {
            Mekanism.packetHandler.sendToServer(new SetTileFrequencyMessage(false, FrequencyType.QIO, identity, tile));
        }
        @Override public QIOFrequency getFrequency() { return tile.getQIOFrequency(); }
        @Override public List<QIOFrequency> getPublicFrequencies() { return tile.getPublicCache(FrequencyType.QIO); }
        @Override public List<QIOFrequency> getTrustedFrequencies() { return tile.getTrustedCache(FrequencyType.QIO); }
        @Override public List<QIOFrequency> getPrivateFrequencies() { return tile.getPrivateCache(FrequencyType.QIO); }
        @Override public UUID getOwnerUUID() { return tile.owner(); }
        @Override public String getSelfOwnerName() {
            String name = tile.getSecurity().getClientOwner();
            return name == null ? "" : name;
        }
    }
}
