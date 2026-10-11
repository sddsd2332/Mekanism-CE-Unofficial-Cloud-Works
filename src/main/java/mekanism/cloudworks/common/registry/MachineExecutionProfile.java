package mekanism.cloudworks.common.registry;

public final class MachineExecutionProfile {
    private final int threads;
    private final double energyPerTick;
    private final double energyCapacity;
    private final int totalTicks;

    public MachineExecutionProfile(int threads, double energyPerTick, int totalTicks) {
        this(threads, energyPerTick, Math.max(energyPerTick * 10D, energyPerTick), totalTicks);
    }

    public MachineExecutionProfile(int threads, double energyPerTick, double energyCapacity, int totalTicks) {
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

    boolean sameDefinition(MachineExecutionProfile other) {
        return other != null && threads == other.threads && energyPerTick == other.energyPerTick
                && energyCapacity == other.energyCapacity && totalTicks == other.totalTicks;
    }
}
