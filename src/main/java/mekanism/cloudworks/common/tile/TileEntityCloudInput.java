package mekanism.cloudworks.common.tile;

import mekanism.cloudworks.common.content.CloudworksPortIo;
import mekanism.cloudworks.common.content.CloudworksPortTypeWatch;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.cloudworks.common.tile.prefab.TileEntityCloudworksPort;

/** Input-only port; its external record and receiving warehouse commit together. */
public class TileEntityCloudInput extends TileEntityCloudworksPort {
    public static final int BUFFER_SIZE = ITEM_SLOTS + TANK_SLOTS;
    private final boolean[] failedLastRound = new boolean[BUFFER_SIZE];
    private final CloudworksPortTypeWatch typeWatch = new CloudworksPortTypeWatch(this::checkResumeConditions);
    private int blockedBufferIndex = -1;
    private boolean resumePending;

    public TileEntityCloudInput() { super("cloud_input", true); }

    @Override protected void serverTick() {
        if (paused) {
            if (resumePending || !typeWatch.isWatching()) {
                resumePending = false;
                checkResumeConditions();
            }
            if (paused) return;
        }
        if (++cycleTicks < transferIntervalTicks()) return;
        cycleTicks = 0;
        try {
            int blocked = binding.transfer(getInventorySlots(null), portTanks(), this::getEnergy,
                    getMaxEnergy(), this::setEnergy, failedLastRound);
            if (blocked >= 0) {
                paused = true;
                blockedBufferIndex = blocked;
                failedLastRound[blocked] = false;
                typeWatch.establish(owner(), CloudworksPortIo.bufferDescriptor(getInventorySlots(null), portTanks(),
                        blocked), CloudworksPlayerDataManager.INSTANCE);
                markDirty();
            }
        } catch (RuntimeException busy) { cycleTicks = transferIntervalTicks() - 1; }
    }

    private void checkResumeConditions() {
        if (!paused || binding == null || !binding.active()) return;
        if (typeWatch.evaluateResume(owner(), getInventorySlots(null), portTanks(), blockedBufferIndex,
                CloudworksPlayerDataManager.INSTANCE)) {
            paused = false;
            blockedBufferIndex = -1;
            typeWatch.release(owner(), CloudworksPlayerDataManager.INSTANCE);
            markDirty();
        }
    }

    @Override protected void bufferChanged() { if (paused) resumePending = true; }
    @Override protected void detachWatch() { typeWatch.release(owner(), CloudworksPlayerDataManager.INSTANCE); }
    public boolean isUploadPaused() { return paused; }
    public int getBlockedBufferIndex() { return blockedBufferIndex; }
    @Override public int getBlockGuiID(net.minecraft.block.Block block, int metadata) { return 1; }
    @Override protected void readPortPacket(io.netty.buffer.ByteBuf data) { blockedBufferIndex = data.readInt() - 1; }
    @Override protected void writePortPacket(mekanism.api.TileNetworkList data) { data.add(blockedBufferIndex + 1); }
}
