package mekanism.cloudworks.api.work;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.util.ResourceLocation;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;

/** Immutable output-commit snapshot passed to module post-work callbacks. */
public final class PostWorkContext {
    private final String recipeId;
    private final long operationId;
    private final int operationCount;
    private final long machineCount;
    private final Map<ResourceLocation, Long> installed;
    private final Map<QIOResourceDescriptor, Long> stored;
    private final Map<QIOResourceDescriptor, Long> capacities;
    private final PostWorkCapacitySnapshot capacitySnapshot;
    private final CloudworksGenerationContext environment;

    public PostWorkContext(String recipeId, long operationId, int operationCount, long machineCount,
            Map<ResourceLocation, Long> installed, Map<QIOResourceDescriptor, Long> stored,
            Map<QIOResourceDescriptor, Long> capacities, CloudworksGenerationContext environment) {
        this.recipeId = recipeId == null ? "" : recipeId;
        this.operationId = operationId;
        this.operationCount = operationCount;
        this.machineCount = machineCount;
        this.installed = Collections.unmodifiableMap(new LinkedHashMap<>(installed));
        this.stored = Collections.unmodifiableMap(new LinkedHashMap<>(stored));
        this.capacities = Collections.unmodifiableMap(new LinkedHashMap<>(capacities));
        this.capacitySnapshot = null;
        this.environment = Objects.requireNonNull(environment);
    }

    /** Uses a reusable, immutable lookup without copying the resource dictionary. */
    public PostWorkContext(String recipeId, long operationId, int operationCount, long machineCount,
            Map<ResourceLocation, Long> installed, Map<QIOResourceDescriptor, Long> stored,
            PostWorkCapacitySnapshot capacities, CloudworksGenerationContext environment) {
        this.recipeId = recipeId == null ? "" : recipeId;
        this.operationId = operationId;
        this.operationCount = operationCount;
        this.machineCount = machineCount;
        this.installed = Collections.unmodifiableMap(new LinkedHashMap<>(installed));
        this.stored = Collections.unmodifiableMap(new LinkedHashMap<>(stored));
        this.capacities = Collections.emptyMap();
        this.capacitySnapshot = Objects.requireNonNull(capacities);
        this.environment = Objects.requireNonNull(environment);
    }

    public String recipeId() { return recipeId; }
    public long operationId() { return operationId; }
    public int operationCount() { return operationCount; }
    public long machineCount() { return machineCount; }
    public long upgradeCount(ResourceLocation id) { return installed.getOrDefault(id, 0L); }
    public Map<ResourceLocation, Long> installedModules() { return installed; }
    public long stored(QIOResourceDescriptor resource) { return stored.getOrDefault(resource, 0L); }
    public long capacity(QIOResourceDescriptor resource) {
        return capacitySnapshot == null ? capacities.getOrDefault(resource, 0L) : capacitySnapshot.capacity(resource);
    }
    public long room(QIOResourceDescriptor resource) {
        long used = 0;
        for (Map.Entry<QIOResourceDescriptor, Long> entry : stored.entrySet())
            if (entry.getKey().getCodecId().equals(resource.getCodecId())) used = Math.addExact(used, entry.getValue());
        return Math.max(0L, capacity(resource) - used);
    }
    public CloudworksGenerationContext environment() { return environment; }
}
