package mekanism.cloudworks.api.environment;

import java.util.*;
import mekanism.api.qio.resource.QIOResourceDescriptor;

/** Immutable numerical generator contract. Native configuration is read only when frozen. */
public final class CloudworksGenerationPlan {
    public enum Source { CONSTANT, SOLAR, WIND, HEAT, UNSUPPORTED }
    private static final CloudworksGenerationPlan ZERO = new CloudworksGenerationPlan(0, Source.CONSTANT);
    private static final CloudworksGenerationPlan UNSUPPORTED = new CloudworksGenerationPlan(0, Source.UNSUPPORTED);
    private final double peak;
    private final Source source;
    private final double electricityFraction;
    private final List<ResourceOutput> resourceOutputs;
    private final int startTick, endTick;

    private CloudworksGenerationPlan(double peak, Source source) {
        this(peak, source, 1, Collections.emptyList(), 0, 24000);
    }

    private CloudworksGenerationPlan(double peak, Source source, double electricityFraction, List<ResourceOutput> outputs,
            int startTick, int endTick) {
        if (!Double.isFinite(peak) || peak < 0)
            throw new IllegalArgumentException("Invalid frozen generation");
        this.peak = peak;
        this.source = source;
        if (!Double.isFinite(electricityFraction) || electricityFraction < 0 || electricityFraction > 1)
            throw new IllegalArgumentException("Invalid electric generation fraction");
        this.electricityFraction = electricityFraction;
        resourceOutputs = Collections.unmodifiableList(new ArrayList<>(outputs));
        this.startTick = startTick; this.endTick = endTick;
    }

    public static CloudworksGenerationPlan constant(double amount) {
        return amount == 0 ? ZERO : new CloudworksGenerationPlan(amount, Source.CONSTANT);
    }
    public static CloudworksGenerationPlan unsupported() { return UNSUPPORTED; }

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
    public double electricityFraction() { return electricityFraction; }
    public List<ResourceOutput> resourceOutputs() { return resourceOutputs; }

    public CloudworksGenerationPlan withElectricityFraction(double fraction) {
        return new CloudworksGenerationPlan(peak, source, fraction, resourceOutputs, startTick, endTick);
    }

    /** Rates are resource units per raw generated joule, before the electric fraction. */
    public CloudworksGenerationPlan withResourceOutput(QIOResourceDescriptor resource, double unitsPerEnergy) {
        return withResourceOutput(resource, unitsPerEnergy, "");
    }

    public CloudworksGenerationPlan withResourceOutput(QIOResourceDescriptor resource, double unitsPerEnergy, String port) {
        if (resourceOutputs.size() >= 64) throw new IllegalArgumentException("Too many generation outputs");
        for (ResourceOutput output : resourceOutputs)
            if (output.resource.equals(resource)) throw new IllegalArgumentException("Duplicate generation resource");
        List<ResourceOutput> next = new ArrayList<>(resourceOutputs);
        next.add(new ResourceOutput(resource, unitsPerEnergy, port));
        return new CloudworksGenerationPlan(peak, source, electricityFraction, next, startTick, endTick);
    }

    /** Inclusive start, exclusive end in Minecraft day ticks; windows may wrap past midnight. */
    public CloudworksGenerationPlan during(int startTick, int endTick) {
        if (startTick < 0 || startTick >= 24000 || endTick < 0 || endTick > 24000 || startTick == endTick)
            throw new IllegalArgumentException("Invalid generation time window");
        return new CloudworksGenerationPlan(peak, source, electricityFraction, resourceOutputs, startTick, endTick);
    }

    public boolean active(int clockTick) {
        int tick = Math.floorMod(clockTick - 1, 24000);
        return startTick < endTick ? tick >= startTick && tick < endTick : tick >= startTick || tick < endTick;
    }

    public static final class ResourceOutput {
        private final QIOResourceDescriptor resource;
        private final double unitsPerEnergy;
        private final String port;
        private ResourceOutput(QIOResourceDescriptor resource, double unitsPerEnergy, String port) {
            if (resource == null || !resource.isResolved() || !Double.isFinite(unitsPerEnergy) || unitsPerEnergy <= 0)
                throw new IllegalArgumentException("Invalid generation resource output");
            this.resource = resource;
            this.unitsPerEnergy = unitsPerEnergy;
            this.port = port == null ? "" : port;
            if (this.port.length() > 128) throw new IllegalArgumentException("Generation output port is too long");
        }
        public QIOResourceDescriptor resource() { return resource; }
        public double unitsPerEnergy() { return unitsPerEnergy; }
        public String port() { return port; }
    }

    public double generation(int clockTick) {
        if (!active(clockTick)) return 0;
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

    /**
     * Returns whether a sunlight-gated recipe cannot run at this simulated
     * tick. Native solar machines stop in rain or thunderstorms in a
     * rain-capable biome; CloudWorks represents that missing biome axis with
     * the immutable environment flags.
     */
    public static boolean sunlightBlocked(int clockTick, CloudworksGenerationContext environment) {
        return solarBrightness(clockTick) <= 0D
                || environment.rainEnvironment() || environment.thunderEnvironment();
    }
}
