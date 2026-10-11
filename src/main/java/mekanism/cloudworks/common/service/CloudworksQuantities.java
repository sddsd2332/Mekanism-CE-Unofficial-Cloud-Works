package mekanism.cloudworks.common.service;

/** Saturating limits only: stored resource quantities still use exact arithmetic. */
public final class CloudworksQuantities {
    private CloudworksQuantities() {}

    public static long multiplyLimit(long a, long b) {
        if (a < 0 || b < 0) throw new IllegalArgumentException("Negative capacity factor");
        return a != 0 && b > Long.MAX_VALUE / a ? Long.MAX_VALUE : a * b;
    }

    public static long addLimit(long a, long b) {
        if (a < 0 || b < 0) throw new IllegalArgumentException("Negative capacity");
        return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b;
    }

    public static int operationLimit(long count, int threads, int rate) {
        return (int) Math.min(Integer.MAX_VALUE, multiplyLimit(multiplyLimit(count, threads), rate));
    }
}
