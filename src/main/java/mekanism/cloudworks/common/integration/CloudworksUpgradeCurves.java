package mekanism.cloudworks.common.integration;

/**
 * One-shot frozen snapshot of the RandomComplement upgrade curve overrides.
 * Reflection touches only this class, only during integration registration;
 * workers never see anything but the frozen numbers. When the mod is absent
 * (or its fields move) every value falls back to the RandomComplement
 * defaults the integrations were designed against.
 */
public final class CloudworksUpgradeCurves {

    public static final String RANDOM_COMPLEMENT_MOD_ID = "random_complement";
    private static final String RC_CONFIG_CLASS = "com.circulation.random_complement.RCConfig";

    public static final double DEFAULT_IC2_OVERCLOCKER_TIME = 0.7D;
    public static final double DEFAULT_IC2_OVERCLOCKER_ENERGY = 1.6D;
    public static final int DEFAULT_IC2_ENERGY_STORAGE = 10_000;
    public static final int DEFAULT_TE_SOCKET_LIMIT = 1;
    /** Native Thermal Expansion coils add 10% energy per coil; RC can switch that off. */
    public static final double TE_COIL_ENERGY_PER_AUGMENT = 0.1D;

    private static volatile Snapshot snapshot = new Snapshot(DEFAULT_IC2_OVERCLOCKER_TIME,
            DEFAULT_IC2_OVERCLOCKER_ENERGY, DEFAULT_IC2_ENERGY_STORAGE, DEFAULT_TE_SOCKET_LIMIT,
            true, false);

    private CloudworksUpgradeCurves() {}

    /** Captures the live values once; later calls return the first frozen snapshot. */
    public static synchronized Snapshot capture(boolean randomComplementLoaded) {
        Snapshot current = snapshot;
        if (current.captured() || !randomComplementLoaded) return current;
        snapshot = readLiveValues();
        return snapshot;
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    /** Test seam: installs an explicit snapshot without touching reflection. */
    static Snapshot install(Snapshot values) {
        snapshot = values;
        return snapshot;
    }

    private static Snapshot readLiveValues() {
        try {
            Class<?> config = Class.forName(RC_CONFIG_CLASS);
            Object ic2 = config.getField("IC2").get(null);
            Object cofh = config.getField("COFHCORE").get(null);
            Object te5 = config.getField("TE5").get(null);
            double time = clampPositive(ic2.getClass().getField("overclockerTime").getDouble(ic2),
                    DEFAULT_IC2_OVERCLOCKER_TIME);
            double energy = clampPositive(ic2.getClass().getField("overclockerEnergy").getDouble(ic2),
                    DEFAULT_IC2_OVERCLOCKER_ENERGY);
            int storage = (int) clampPositive(ic2.getClass().getField("energyStorageEnergy").getInt(ic2),
                    DEFAULT_IC2_ENERGY_STORAGE);
            int sockets = (int) clampPositive(cofh.getClass().getField("SocketLimitModified").getInt(cofh),
                    DEFAULT_TE_SOCKET_LIMIT);
            boolean coilEnergy;
            try {
                coilEnergy = te5.getClass().getField("IncreasedEnergyConsumption").getBoolean(te5);
            } catch (ReflectiveOperationException moved) {
                coilEnergy = false;
            }
            return new Snapshot(time, energy, storage, sockets, coilEnergy, true);
        } catch (ReflectiveOperationException | ClassCastException failure) {
            // A moved field or a new config layout must not break registration;
            // the native Thermal Expansion defaults are always safe to run on.
            return new Snapshot(DEFAULT_IC2_OVERCLOCKER_TIME, DEFAULT_IC2_OVERCLOCKER_ENERGY,
                    DEFAULT_IC2_ENERGY_STORAGE, DEFAULT_TE_SOCKET_LIMIT, true, false);
        }
    }

    private static double clampPositive(double value, double fallback) {
        return Double.isFinite(value) && value > 0D ? value : fallback;
    }

    /** Immutable frozen values; the only upgrade data workers ever read. */
    public static final class Snapshot {
        private final double ic2OverclockerTime;
        private final double ic2OverclockerEnergy;
        private final int ic2EnergyStorage;
        private final int teSocketLimit;
        private final boolean teCoilEnergy;
        private final boolean captured;

        public Snapshot(double ic2OverclockerTime, double ic2OverclockerEnergy,
                int ic2EnergyStorage, int teSocketLimit, boolean teCoilEnergy, boolean captured) {
            if (!Double.isFinite(ic2OverclockerTime) || ic2OverclockerTime <= 0D
                    || !Double.isFinite(ic2OverclockerEnergy) || ic2OverclockerEnergy <= 0D
                    || ic2EnergyStorage < 0 || teSocketLimit < 1)
                throw new IllegalArgumentException("Invalid RandomComplement upgrade curve");
            this.ic2OverclockerTime = ic2OverclockerTime;
            this.ic2OverclockerEnergy = ic2OverclockerEnergy;
            this.ic2EnergyStorage = ic2EnergyStorage;
            this.teSocketLimit = teSocketLimit;
            this.teCoilEnergy = teCoilEnergy;
            this.captured = captured;
        }

        public double ic2OverclockerTime() { return ic2OverclockerTime; }
        public double ic2OverclockerEnergy() { return ic2OverclockerEnergy; }
        public int ic2EnergyStorage() { return ic2EnergyStorage; }
        public int teSocketLimit() { return teSocketLimit; }
        public boolean teCoilEnergy() { return teCoilEnergy; }
        public boolean captured() { return captured; }

        /** Coil energy step: the native 10% stands unless RandomComplement disables it. */
        public double teCoilEnergyPerAugment() {
            return teCoilEnergy ? TE_COIL_ENERGY_PER_AUGMENT : 0D;
        }
    }
}
