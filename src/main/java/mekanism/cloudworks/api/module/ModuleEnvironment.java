package mekanism.cloudworks.api.module;

import java.util.*;
import net.minecraft.util.ResourceLocation;

/** Immutable source declaration, projected before generator and thermal snapshots freeze. */
public final class ModuleEnvironment {
    private final Set<ResourceLocation> flags;
    private final int heightPerModule;
    private final int lavaPerModule;
    public ModuleEnvironment(Collection<ResourceLocation> flags, int heightPerModule, int lavaPerModule) {
        if (heightPerModule < 0 || lavaPerModule < 0) throw new IllegalArgumentException("Negative environment units");
        this.flags = Collections.unmodifiableSet(new LinkedHashSet<>(flags));
        this.heightPerModule = heightPerModule; this.lavaPerModule = lavaPerModule;
    }
    public Set<ResourceLocation> flags() { return flags; }
    public int heightPerModule() { return heightPerModule; }
    public int lavaPerModule() { return lavaPerModule; }
}
