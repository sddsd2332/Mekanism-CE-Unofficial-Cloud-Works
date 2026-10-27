package mekanism.cloudworks.api.machine;

import java.util.Objects;

/**
 * Line-level upgrade participation (axis-priority plan): whether this machine
 * accepts the production line's shared speed/energy coverage, how many line
 * units each physical machine demands per axis, and nothing else. The coverage
 * fraction itself is a line fact; the curve is owned by the line effect layer
 * and never derived from the machine's own node upgrade policy.
 */
public final class MachineLineUpgradePolicy {
    /** No line upgrade participation on either axis. */
    public static final MachineLineUpgradePolicy NONE = new MachineLineUpgradePolicy(false, false, 0, 0);
    /** Default processing participation: both axes, eight units per machine each. */
    public static final MachineLineUpgradePolicy PROCESSING = new MachineLineUpgradePolicy(true, true, 8, 8);
    /** Speed axis only (solar-style single-axis executors). */
    public static final MachineLineUpgradePolicy SPEED_ONLY = new MachineLineUpgradePolicy(true, false, 8, 0);
    /** Energy axis only. */
    public static final MachineLineUpgradePolicy ENERGY_ONLY = new MachineLineUpgradePolicy(false, true, 0, 8);

    private final boolean speed, energy;
    private final int speedUnitsPerMachine, energyUnitsPerMachine;

    private MachineLineUpgradePolicy(boolean speed, boolean energy,
            int speedUnitsPerMachine, int energyUnitsPerMachine) {
        if (speedUnitsPerMachine < 0 || energyUnitsPerMachine < 0
                || speed && speedUnitsPerMachine == 0 || energy && energyUnitsPerMachine == 0)
            throw new IllegalArgumentException("Line upgrade units must be positive on accepted axes");
        this.speed = speed;
        this.energy = energy;
        this.speedUnitsPerMachine = speed ? speedUnitsPerMachine : 0;
        this.energyUnitsPerMachine = energy ? energyUnitsPerMachine : 0;
    }

    /** Declared participation with per-machine unit demand; zero units refuses the axis. */
    public static MachineLineUpgradePolicy of(boolean speed, boolean energy,
            int speedUnitsPerMachine, int energyUnitsPerMachine) {
        return new MachineLineUpgradePolicy(speed && speedUnitsPerMachine > 0, energy && energyUnitsPerMachine > 0,
                Math.max(0, speedUnitsPerMachine), Math.max(0, energyUnitsPerMachine));
    }

    public boolean acceptsSpeed() { return speed; }
    public boolean acceptsEnergy() { return energy; }
    public int speedUnitsPerMachine() { return speedUnitsPerMachine; }
    public int energyUnitsPerMachine() { return energyUnitsPerMachine; }

    public boolean sameDefinition(MachineLineUpgradePolicy other) {
        return other != null && speed == other.speed && energy == other.energy
                && speedUnitsPerMachine == other.speedUnitsPerMachine
                && energyUnitsPerMachine == other.energyUnitsPerMachine;
    }

    @Override public boolean equals(Object value) {
        return value instanceof MachineLineUpgradePolicy && sameDefinition((MachineLineUpgradePolicy) value);
    }

    @Override public int hashCode() {
        return Objects.hash(speed, energy, speedUnitsPerMachine, energyUnitsPerMachine);
    }
}
