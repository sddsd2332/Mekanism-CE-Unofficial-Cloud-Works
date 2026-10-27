package mekanism.cloudworks.common.tile;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.api.transmitters.TransmissionType;
import mekanism.cloudworks.common.content.CloudworksPortIo;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.cloudworks.common.tile.prefab.TileEntityCloudworksPort;
import mekanism.common.integration.ic2.IEnergyInIC2OutputWrapper;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.component.TileComponentConfig;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;
import mekanism.cloudworks.common.content.CloudworksHybridTank;
import net.minecraft.block.Block;

/**
 * Output-only port. A full buffer waits for local extraction before requesting again.
 */
public class TileEntityCloudOutput extends TileEntityCloudworksPort implements IEnergyInIC2OutputWrapper {
    private boolean resumePending;

    public TileEntityCloudOutput() {
        super("cloud_output", false);
        configComponent.addItemSlotInfo(DataType.OUTPUT, getInventorySlots(null));
        configComponent.addFluidSlotInfo(DataType.OUTPUT,
                portTanks().stream().map(CloudworksHybridTank::fluidView)
                        .collect(Collectors.toList()));
        configComponent.addGasSlotInfo(DataType.OUTPUT,
                portTanks().stream().map(CloudworksHybridTank::gasView)
                        .collect(Collectors.toList()));
        configComponent.getConfigInfo(TransmissionType.ENERGY).addSlotInfo(DataType.OUTPUT,
                TileComponentConfig.createInfo(TransmissionType.ENERGY, false, true,
                        Collections.singletonList(getMainEnergyContainer())));
        configComponent.getConfigInfo(TransmissionType.ENERGY).fill(DataType.OUTPUT);
        for (TransmissionType transmission : new TransmissionType[]{TransmissionType.ITEM,
                TransmissionType.FLUID, TransmissionType.GAS})
            configComponent.getConfigInfo(transmission).fill(DataType.OUTPUT);
        ejectorComponent.setOutputData(configComponent, TransmissionType.ITEM, TransmissionType.ENERGY,
                TransmissionType.FLUID, TransmissionType.GAS);
    }

    @Override
    protected void serverTick() {
        if (paused) {
            if (resumePending) {
                resumePending = false;
                checkResume();
            }
            if (paused) return;
        }
        if (++cycleTicks < transferIntervalTicks()) return;
        cycleTicks = 0;
        try {
            paused = binding.transfer(getInventorySlots(null), portTanks(), this::getEnergy,
                    getMaxEnergy(), this::setEnergy, null) >= 0;
            if (paused) markDirty();
        } catch (RuntimeException busy) {
            cycleTicks = transferIntervalTicks() - 1;
        }
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
        } catch (RuntimeException unavailable) {
            resumePending = true;
        }
    }

    @Override
    protected void bufferChanged() {
        if (paused) resumePending = true;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        resumePending = true;
    }

    public boolean isDownloadPaused() {
        return paused;
    }

    @Override
    public int getBlockGuiID(Block block, int metadata) {
        return 2;
    }
}
