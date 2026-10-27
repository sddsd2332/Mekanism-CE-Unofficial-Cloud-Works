package mekanism.cloudworks.api.machine;

import java.util.Objects;
import mekanism.cloudworks.api.module.MachineUpgradePolicy;

public final class MachineExecutionProfile {
    private final int threads;
    private final double energyPerTick;
    private final double energyCapacity;
    private final int totalTicks;
    private final MachineUpgradePolicy upgradePolicy;
    /**
     * Line-level participation (axis-priority plan); null means "derive the
     * default from the node policy family" — an explicit resolver rule, not a
     * delegation of the node curve.
     */
    private final MachineLineUpgradePolicy lineUpgradePolicy;

    public MachineExecutionProfile(int threads, double energyPerTick, int totalTicks) {
        this(threads, energyPerTick, Math.max(energyPerTick * 10D, energyPerTick), totalTicks);
    }

    public MachineExecutionProfile(int threads, double energyPerTick, double energyCapacity, int totalTicks) {
        this(threads, energyPerTick, energyCapacity, totalTicks, MachineUpgradePolicy.MEKANISM);
    }

    public MachineExecutionProfile(int threads, double energyPerTick, double energyCapacity, int totalTicks,
            MachineUpgradePolicy upgradePolicy) {
        this(threads, energyPerTick, energyCapacity, totalTicks, upgradePolicy, null);
    }

    public MachineExecutionProfile(int threads, double energyPerTick, double energyCapacity, int totalTicks,
            MachineUpgradePolicy upgradePolicy, MachineLineUpgradePolicy lineUpgradePolicy) {
        // Registration boundary: non-finite energy is a registration bug and must
        // refuse here, not flow into snapshots or settlements as NaN.
        if (!Double.isFinite(energyPerTick) || energyPerTick < 0D)
            throw new IllegalArgumentException("Non-finite energy per tick");
        if (!Double.isFinite(energyCapacity) || energyCapacity < 0D)
            throw new IllegalArgumentException("Non-finite energy capacity");
        this.threads = Math.max(1, threads);
        this.energyPerTick = energyPerTick;
        this.energyCapacity = energyCapacity;
        this.totalTicks = Math.max(1, totalTicks);
        this.upgradePolicy = Objects.requireNonNull(upgradePolicy, "upgradePolicy");
        this.lineUpgradePolicy = lineUpgradePolicy;
    }

    public int threads() {
        return threads;
    }

    public double energyPerTick() {
        return energyPerTick;
    }

    public double energyCapacity() {
        return energyCapacity;
    }

    public int totalTicks() {
        return totalTicks;
    }

    public MachineUpgradePolicy upgradePolicy() {
        return upgradePolicy;
    }

    /** Declared line participation, or null to derive the default from the node policy family. */
    public MachineLineUpgradePolicy lineUpgradePolicy() {
        return lineUpgradePolicy;
    }

    public MachineExecutionProfile withUpgradePolicy(MachineUpgradePolicy policy) {
        return new MachineExecutionProfile(threads, energyPerTick, energyCapacity, totalTicks, policy, lineUpgradePolicy);
    }

    public MachineExecutionProfile withLineUpgradePolicy(MachineLineUpgradePolicy policy) {
        return new MachineExecutionProfile(threads, energyPerTick, energyCapacity, totalTicks, upgradePolicy, policy);
    }

    /** Numeric profile replacement inherits existing policies unless A is explicitly overridden. */
    public MachineExecutionProfile inheritUpgradePolicies(MachineExecutionProfile source) {
        return new MachineExecutionProfile(threads, energyPerTick, energyCapacity, totalTicks,
                source.upgradePolicy, lineUpgradePolicy == null ? source.lineUpgradePolicy : lineUpgradePolicy);
    }

    public boolean sameDefinition(MachineExecutionProfile other) {
        return other != null && threads == other.threads && energyPerTick == other.energyPerTick
                && energyCapacity == other.energyCapacity && totalTicks == other.totalTicks
                && upgradePolicy.equals(other.upgradePolicy)
                && Objects.equals(lineUpgradePolicy, other.lineUpgradePolicy);
    }
}
