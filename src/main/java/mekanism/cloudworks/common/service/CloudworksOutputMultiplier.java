package mekanism.cloudworks.common.service;

import java.math.BigDecimal;

/** Thousandths are exact persistent values; no fractional-output debt is accumulated. */
public final class CloudworksOutputMultiplier {
    public static final int SCALE = 1000;
    public static final int MAX_UNITS = 1024 * SCALE;

    private CloudworksOutputMultiplier() {}

    public static int units(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid output multiplier");
        int units;
        try {
            units = BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(SCALE)).intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Output multiplier requires at most three decimal places", invalid);
        }
        return validate(units);
    }

    public static int validate(int units) {
        if (units < SCALE || units > MAX_UNITS) throw new IllegalArgumentException("Invalid output multiplier");
        return units;
    }

    public static String format(int units) {
        return BigDecimal.valueOf(validate(units), 3).stripTrailingZeros().toPlainString();
    }

    public static long whole(long amount, int units) {
        if (amount < 0) throw new IllegalArgumentException("Negative output amount");
        return Math.addExact(Math.multiplyExact(amount / SCALE, units), amount % SCALE * units / SCALE);
    }

    public static int remainder(long amount, int units) {
        return (int) (amount % SCALE * units % SCALE);
    }

    public static long maximum(long amount, int units, int operations) {
        return Math.multiplyExact(Math.addExact(whole(amount, units), remainder(amount, units) == 0 ? 0 : 1), operations);
    }

    public static long mix(long seed) {
        seed = (seed ^ (seed >>> 30)) * 0xbf58476d1ce4e5b9L;
        seed = (seed ^ (seed >>> 27)) * 0x94d049bb133111ebL;
        return seed ^ (seed >>> 31);
    }

    public static long fingerprint(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) hash = (hash ^ value.charAt(index)) * 0x100000001b3L;
        return mix(hash);
    }
}
