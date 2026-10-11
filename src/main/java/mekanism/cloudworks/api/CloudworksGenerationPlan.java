package mekanism.cloudworks.api;

/** Immutable numerical generator contract. Native configuration is read only when frozen. */
public final class CloudworksGenerationPlan {
    public enum Source { CONSTANT, SOLAR, WIND, HEAT }
    private static final CloudworksGenerationPlan ZERO = new CloudworksGenerationPlan(0, Source.CONSTANT);
    private final double peak;
    private final Source source;

    private CloudworksGenerationPlan(double peak, Source source) {
        if (!Double.isFinite(peak) || peak < 0)
            throw new IllegalArgumentException("Invalid frozen generation");
        this.peak = peak;
        this.source = source;
    }

    public static CloudworksGenerationPlan constant(double amount) {
        return amount == 0 ? ZERO : new CloudworksGenerationPlan(amount, Source.CONSTANT);
    }

    public static CloudworksGenerationPlan solar(double peak) {
        return new CloudworksGenerationPlan(peak, Source.SOLAR);
    }

    public static CloudworksGenerationPlan wind(double amount) {
        return new CloudworksGenerationPlan(amount, Source.WIND);
    }

    public static CloudworksGenerationPlan heat(double amount) {
        return new CloudworksGenerationPlan(amount, Source.HEAT);
    }

    public Source source() { return source; }
    public double peak() { return peak; }

    public double generation(int clockTick) {
        if (source != Source.SOLAR) return peak;
        return peak * solarBrightness(clockTick);
    }

    public static double solarBrightness(int clockTick) {
        int tick = Math.floorMod(clockTick - 1, 24000) + 1;
        double brightness;
        if (tick <= 12000) brightness = 1;
        else if (tick <= 13800) brightness = 1D - (tick - 12000D) / 1800D;
        else if (tick <= 22200) brightness = 0;
        else brightness = (tick - 22200D) / 1800D;
        return Math.max(0, Math.min(1, brightness));
    }
}
