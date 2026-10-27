package mekanism.cloudworks.api.module;

/** Pure numerical result of evaluating a machine's upgrade policy. */
public final class MachineUpgradeEffect {
    private final double time;
    private final double energy;
    private final double capacity;
    private final double secondaryChanceMultiplier;
    private final boolean configured;

    public MachineUpgradeEffect(double time, double energy, double capacity, boolean configured) {
        this(time, energy, capacity, configured, 1D);
    }

    public MachineUpgradeEffect(double time, double energy, double capacity, boolean configured,
            double secondaryChanceMultiplier) {
        if (!Double.isFinite(time) || !Double.isFinite(energy) || !Double.isFinite(capacity)
                || !Double.isFinite(secondaryChanceMultiplier)
                || time < 0 || energy < 0 || capacity < 0 || secondaryChanceMultiplier < 0)
            throw new IllegalArgumentException("Invalid machine upgrade effect");
        this.time = time;
        this.energy = energy;
        this.capacity = capacity;
        this.secondaryChanceMultiplier = secondaryChanceMultiplier;
        this.configured = configured;
    }

    public double time() { return time; }
    public double energy() { return energy; }
    public double capacity() { return capacity; }
    public double secondaryChanceMultiplier() { return secondaryChanceMultiplier; }
    public boolean configured() { return configured; }
}
