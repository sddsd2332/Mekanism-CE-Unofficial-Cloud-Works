package mekanism.cloudworks.common.upgrade;

import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.common.Upgrade;
import mekanism.common.config.MekanismConfig;

/** MekCEU upgrade formulas evaluated on the owner thread, with line-wide upgrade fractions. */
public final class CloudworksUpgradeEffects {
    private final double time, energy, capacity;
    private final boolean configured;

    private CloudworksUpgradeEffects(double time, double energy, double capacity, boolean configured) {
        this.time = time;
        this.energy = energy;
        this.capacity = capacity;
        this.configured = configured;
    }

    public static CloudworksUpgradeEffects of(ProductionLineData line) {
        int[] settings = settings();
        double speed = fraction(line.speedUpgrades(), line.maxSpeedUpgrades());
        double energy = fraction(line.energyUpgrades(), line.maxEnergyUpgrades());
        boolean configured = settings[0] != 0;
        // MekanismUtils.time/electricity/capacity, normalized over the whole line.
        double energyExponent = 2 * speed - (configured ? Math.min(energy, Math.max(1D, speed)) : energy);
        return new CloudworksUpgradeEffects(Math.pow(settings[1], -speed),
                Math.pow(settings[1], energyExponent), Math.pow(settings[1], energy), configured);
    }

    public static int[] settings() {
        if (mekanism.cloudworks.common.service.CloudworksNativeContracts.isWorker())
            return mekanism.cloudworks.common.service.CloudworksNativeContracts.upgradeSettings();
        try {
            return new int[]{MekanismConfig.current().mekce.EnableUpgradeConfigure.val() ? 1 : 0,
                    MekanismConfig.current().general.maxUpgradeMultiplier.val(),
                    Upgrade.SPEED.getMaxInstalled(), Upgrade.ENERGY.getMaxInstalled()};
        } catch (Throwable unavailable) {
            // Headless fixture runs do not bootstrap Forge's Loader. Keep the
            // MekCEU defaults so pure data tests remain deterministic; a live
            // server always has the configured path above.
            return new int[]{0, 10, 8, 8};
        }
    }

    private static double fraction(int installed, int maximum) {
        return maximum <= 0 ? 0 : (float) Math.min(installed, maximum) / (float) maximum;
    }

    /** Negative ticks use MekCEU's configured operations-per-tick encoding. */
    public int ticks(int baseTicks) {
        return ticks(baseTicks, time, configured);
    }

    /** Pure arithmetic for detached recipe-specific durations. */
    public static int ticks(int baseTicks, double time, boolean configured) {
        double ticks = Math.max(1, baseTicks) * time;
        return configured && ticks < 1 ? -(int) Math.min(Integer.MAX_VALUE, 1 / ticks) : (int) ticks;
    }

    public double timeMultiplier() {
        return time;
    }

    public boolean configured() {
        return configured;
    }

    public double energyPerTick(double baseEnergy) {
        // Per-machine load; Runtime applies machine count, parallelism and workRate separately.
        return Math.min(Double.MAX_VALUE, baseEnergy * energy);
    }

    public double capacity(double baseCapacity) {
        return Math.min(Double.MAX_VALUE, baseCapacity * capacity);
    }
}
