package mekanism.cloudworks.api.work;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.util.ResourceLocation;

/**
 * Immutable output capacity lookup. Derived snapshots share a frozen resource
 * dictionary and store only one capacity per codec.
 */
public final class PostWorkCapacitySnapshot {
    private final Set<QIOResourceDescriptor> resources;
    private final Map<ResourceLocation, Long> capacities;

    public PostWorkCapacitySnapshot(Collection<QIOResourceDescriptor> resources,
            Map<ResourceLocation, Long> capacities) {
        this(Collections.unmodifiableSet(new HashSet<>(resources)), capacities);
    }

    private PostWorkCapacitySnapshot(Set<QIOResourceDescriptor> resources,
            Map<ResourceLocation, Long> capacities) {
        this.resources = resources;
        this.capacities = Collections.unmodifiableMap(new LinkedHashMap<>(capacities));
    }

    public PostWorkCapacitySnapshot withCodecCapacities(Map<ResourceLocation, Long> capacities) {
        return new PostWorkCapacitySnapshot(resources, capacities);
    }

    public long capacity(QIOResourceDescriptor resource) {
        return resource != null && resources.contains(resource)
                ? capacities.getOrDefault(resource.getCodecId(), 0L) : 0L;
    }
}
