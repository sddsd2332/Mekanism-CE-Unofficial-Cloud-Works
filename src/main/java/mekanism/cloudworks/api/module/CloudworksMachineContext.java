package mekanism.cloudworks.api.module;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.item.ItemStack;
import java.util.Objects;

/** Immutable node snapshot. It exposes no mutable CloudWorks data or Runtime stores. */
public final class CloudworksMachineContext {
    private final UUID nodeId;
    private final ItemStack machine;
    private final long machineCount;
    private final boolean active;
    private final String recipeType;
    private final Map<QIOResourceDescriptor, Long> inputs, outputs, configuration;

    public CloudworksMachineContext(UUID nodeId, ItemStack machine, long machineCount, boolean active,
            String recipeType, Map<QIOResourceDescriptor, Long> inputs,
            Map<QIOResourceDescriptor, Long> outputs, Map<QIOResourceDescriptor, Long> configuration) {
        this.nodeId = Objects.requireNonNull(nodeId);
        this.machine = machine.copy();
        this.machine.setCount(1);
        if (machineCount < 0) throw new IllegalArgumentException("Negative machine count");
        this.machineCount = machineCount;
        this.active = active;
        this.recipeType = recipeType == null ? "" : recipeType;
        this.inputs = immutable(inputs);
        this.outputs = immutable(outputs);
        this.configuration = immutable(configuration);
    }

    private static Map<QIOResourceDescriptor, Long> immutable(Map<QIOResourceDescriptor, Long> source) {
        Map<QIOResourceDescriptor, Long> copy = new LinkedHashMap<>();
        source.forEach((descriptor, amount) -> {
            if (descriptor == null || !descriptor.isResolved() || amount == null || amount < 0)
                throw new IllegalArgumentException("Invalid machine context resource");
            if (amount > 0) copy.merge(descriptor, amount, Math::addExact);
        });
        return Collections.unmodifiableMap(copy);
    }

    public UUID nodeId() { return nodeId; }
    public ItemStack machine() { return machine.copy(); }
    public long machineCount() { return machineCount; }
    public boolean active() { return active; }
    public String recipeType() { return recipeType; }
    public Map<QIOResourceDescriptor, Long> inputs() { return inputs; }
    public Map<QIOResourceDescriptor, Long> outputs() { return outputs; }
    public Map<QIOResourceDescriptor, Long> configuration() { return configuration; }
}
