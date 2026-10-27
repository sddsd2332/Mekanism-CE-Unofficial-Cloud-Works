package mekanism.cloudworks.api.environment;

import java.util.Objects;
import net.minecraft.world.DimensionType;
import net.minecraft.world.biome.Biome;

/**
 * The game-data side of an environment axis: exactly one of dimension, biome or
 * structure data. The factories keep the union mutually exclusive at construction
 * time, and the kind is only used for display-name fallbacks and declared
 * payloads — the cloud runtime never reads a live world through it.
 */
public final class EnvironmentFallback {
    private final EnvironmentAxis.Kind kind;
    private final DimensionType dimension;
    private final Biome biome;

    private EnvironmentFallback(EnvironmentAxis.Kind kind, DimensionType dimension, Biome biome) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.dimension = dimension;
        this.biome = biome;
    }

    /** Dimension-identified environments; the stable identity is the DimensionType, never a runtime dim id. */
    public static EnvironmentFallback dimension(DimensionType dimension) {
        return new EnvironmentFallback(EnvironmentAxis.Kind.DIMENSION,
                Objects.requireNonNull(dimension, "dimension"), null);
    }

    /** Biome-identified environments; the representative biome also supplies spawn tables. */
    public static EnvironmentFallback biome(Biome biome) {
        return new EnvironmentFallback(EnvironmentAxis.Kind.BIOME, null,
                Objects.requireNonNull(biome, "biome"));
    }

    /** Structure environments carry declared payloads only; there is no 1.12 structure registry to scan. */
    public static EnvironmentFallback structure() {
        return new EnvironmentFallback(EnvironmentAxis.Kind.STRUCTURE, null, null);
    }

    public EnvironmentAxis.Kind kind() { return kind; }
    public DimensionType dimension() { return dimension; }
    public Biome biome() { return biome; }
}
