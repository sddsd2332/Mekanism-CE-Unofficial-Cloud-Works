package mekanism.cloudworks.common.tile;

import java.util.Map;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.content.CloudworksPortIo;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.cloudworks.common.tile.prefab.TileEntityCloudworksPort;

/** Output-only port. A full buffer waits for local extraction before requesting again. */
public class TileEntityCloudOutput extends TileEntityCloudworksPort {
    private boolean resumePending;

    public TileEntityCloudOutput() { super("cloud_output", false); }

    @Override protected void serverTick() {
        if (paused) {
            if (resumePending) { resumePending = false; checkResume(); }
            if (paused) return;
        }
        if (++cycleTicks < transferIntervalTicks()) return;
        cycleTicks = 0;
        try {
            paused = binding.transfer(getInventorySlots(null), portTanks(), this::getEnergy,
                    getMaxEnergy(), this::setEnergy, null) >= 0;
            if (paused) markDirty();
        } catch (RuntimeException busy) { cycleTicks = transferIntervalTicks() - 1; }
    }

    private void checkResume() {
        try {
            Map<QIOResourceDescriptor, Long> available = CloudworksPlayerDataManager.INSTANCE.sendingSnapshot(owner());
            boolean supported = available.keySet().stream().anyMatch(CloudworksPortIo::isSupportedDownloadResource);
            boolean energyAvailable = CloudworksPlayerDataManager.INSTANCE.sendingEnergy(owner()) > 0;
            if ((!supported && !energyAvailable)
                    || !CloudworksPortIo.buildRequest(available, getInventorySlots(null), portTanks()).isEmpty()
                    || energyAvailable && getEnergy() < getMaxEnergy()) {
                paused = false;
                markDirty();
            }
        } catch (RuntimeException unavailable) { resumePending = true; }
    }

    @Override protected void bufferChanged() { if (paused) resumePending = true; }
    @Override public void onLoad() { super.onLoad(); resumePending = true; }
    public boolean isDownloadPaused() { return paused; }
    @Override public int getBlockGuiID(net.minecraft.block.Block block, int metadata) { return 2; }
}
