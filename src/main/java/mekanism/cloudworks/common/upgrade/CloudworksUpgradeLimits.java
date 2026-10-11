package mekanism.cloudworks.common.upgrade;

import java.util.stream.LongStream;
import mekanism.common.Upgrade;

public final class CloudworksUpgradeLimits {
    private CloudworksUpgradeLimits() {
    }

    public static int capacity(Upgrade upgrade, LongStream machineCounts) {
        long machines = machineCounts.reduce(0L, (total, count) ->
                Math.min(Integer.MAX_VALUE, total + Math.min(Integer.MAX_VALUE, Math.max(0L, count))));
        int maximum;
        if (mekanism.cloudworks.common.service.CloudworksNativeContracts.isWorker()) {
            int[] settings = mekanism.cloudworks.common.service.CloudworksNativeContracts.upgradeSettings();
            maximum = upgrade == Upgrade.SPEED ? settings[2] : upgrade == Upgrade.ENERGY ? settings[3] : 1;
            return (int) Math.min(Integer.MAX_VALUE, machines * maximum);
        }
        try {
            maximum = upgrade.getMaxInstalled();
        } catch (Throwable unavailable) {
            maximum = upgrade == Upgrade.SPEED || upgrade == Upgrade.ENERGY ? 8 : 1;
        }
        return (int) Math.min(Integer.MAX_VALUE, machines * maximum);
    }
}
