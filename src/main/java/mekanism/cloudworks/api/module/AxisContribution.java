package mekanism.cloudworks.api.module;

/**
 * One axis's multiplicative share of an upgrade effect, per the axis-priority
 * decomposition contract: time, per-tick power and capacity multipliers that
 * compose by multiplication across the two selected axis sources. The neutral
 * contribution is all ones.
 */
public final class AxisContribution {
    public static final AxisContribution NEUTRAL = new AxisContribution(1D, 1D, 1D);

    public final double time, power, capacity;

    private AxisContribution(double time, double power, double capacity) {
        if (!Double.isFinite(time) || !Double.isFinite(power) || !Double.isFinite(capacity)
                || time < 0D || power < 0D || capacity < 0D)
            throw new IllegalArgumentException("Axis contribution must be finite and non-negative");
        this.time = time;
        this.power = power;
        this.capacity = capacity;
    }

    public static AxisContribution of(double time, double power, double capacity) {
        if (time == 1D && power == 1D && capacity == 1D) return NEUTRAL;
        return new AxisContribution(time, power, capacity);
    }
}
