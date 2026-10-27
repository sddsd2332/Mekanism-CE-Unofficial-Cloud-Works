package mekanism.cloudworks.common.integration.ic2;

import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.api.work.*;
import mekanism.cloudworks.common.integration.CloudworksUpgradeCurves;

/** Storage is an absolute EU addition, and deliberately claims no efficiency axis. */
public final class CloudworksIc2StorageUpgrade implements ICustomMachineModule<CloudworksIc2StorageUpgrade> {
    private final double euToJ;
    public CloudworksIc2StorageUpgrade(double factor) { euToJ = factor; }
    @Override public String installationGroup() { return "ic2:upgrade_slots"; }
    @Override public int installationSlotsPerMachine() { return 4; }
    @Override public int installationItemsPerSlot() { return 64; }
    @Override public boolean affectsCapacity() { return true; }
    @Override public WorkContribution onPreWork(IMachineModule<CloudworksIc2StorageUpgrade> module, PreWorkContext context) {
        return WorkContribution.builder().capacityAddition(euToJ * CloudworksUpgradeCurves.snapshot().ic2EnergyStorage()
                * module.getEffectiveInstalledCount()).build();
    }
}
