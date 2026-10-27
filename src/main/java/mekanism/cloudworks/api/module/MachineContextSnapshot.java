package mekanism.cloudworks.api.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.Map;
import java.util.RandomAccess;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;

/**
 * Immutable, worker-safe view of the machine's related CloudWorks nodes.
 *
 * <p>This is a projection of topology and declared source state. It never
 * exposes World, TileEntity, mutable inventories, or live Runtime objects.</p>
 */
public final class MachineContextSnapshot {
    private final CloudworksGenerationContext environment;
    private final List<AuxiliaryNode> auxiliaryNodes;
    private final List<InputOutputNode> inputOutputNodes;
    private final List<MachineNode> machineNodes;
    private final List<Link> links;

    public MachineContextSnapshot(CloudworksGenerationContext environment,
            List<AuxiliaryNode> auxiliaryNodes, List<InputOutputNode> inputOutputNodes,
            List<MachineNode> machineNodes, List<Link> links) {
        this(environment, auxiliaryNodes, inputOutputNodes, machineNodes, links, false);
    }

    private MachineContextSnapshot(CloudworksGenerationContext environment,
            List<AuxiliaryNode> auxiliaryNodes, List<InputOutputNode> inputOutputNodes,
            List<MachineNode> machineNodes, List<Link> links, boolean sharedMachines) {
        this.environment = Objects.requireNonNull(environment);
        this.auxiliaryNodes = immutable(auxiliaryNodes);
        this.inputOutputNodes = immutable(inputOutputNodes);
        this.machineNodes = sharedMachines ? machineNodes : immutable(machineNodes);
        this.links = immutable(links);
    }

    /** Shares a frozen line inventory while retaining the normal self-excluding view. */
    public static MachineContextSnapshot withSharedMachines(CloudworksGenerationContext environment,
            List<AuxiliaryNode> auxiliaries, List<InputOutputNode> channels,
            MachineNodes machines, UUID self, List<Link> links) {
        return new MachineContextSnapshot(environment, auxiliaries, channels,
                Objects.requireNonNull(machines).excluding(self), links, true);
    }

    /** One defensive line snapshot, reused by all module contexts of that capture. */
    public static final class MachineNodes {
        private final List<MachineNode> nodes;
        private final Map<UUID, Integer> positions = new HashMap<>();
        public MachineNodes(List<MachineNode> source) {
            nodes = immutable(Objects.requireNonNull(source));
            for (int i = 0; i < nodes.size(); i++)
                if (positions.put(Objects.requireNonNull(nodes.get(i)).nodeId(), i) != null)
                    throw new IllegalArgumentException("Duplicate machine in context snapshot");
        }
        public List<MachineNode> excluding(UUID self) {
            Integer skipped = positions.get(self);
            return skipped == null ? nodes : Collections.unmodifiableList(new OtherMachines(nodes, skipped));
        }
    }

    private static final class OtherMachines extends AbstractList<MachineNode> implements RandomAccess {
        private final List<MachineNode> nodes;
        private final int skipped;
        OtherMachines(List<MachineNode> nodes, int skipped) { this.nodes = nodes; this.skipped = skipped; }
        @Override public int size() { return nodes.size() - 1; }
        @Override public MachineNode get(int index) {
            if (index < 0 || index >= size()) throw new IndexOutOfBoundsException("Index: " + index);
            return nodes.get(index < skipped ? index : index + 1);
        }
    }

    public static MachineContextSnapshot empty(CloudworksGenerationContext environment) {
        return new MachineContextSnapshot(environment, Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    public CloudworksGenerationContext environment() { return environment; }
    public List<AuxiliaryNode> auxiliaryNodes() { return auxiliaryNodes; }
    public List<InputOutputNode> inputOutputNodes() { return inputOutputNodes; }
    public List<MachineNode> machineNodes() { return machineNodes; }
    public List<Link> links() { return links; }

    public boolean hasAuxiliary(ResourceLocation moduleId) {
        return auxiliaryNodes.stream().anyMatch(node -> node.moduleId().equals(moduleId));
    }

    public int auxiliaryCount(ResourceLocation moduleId) {
        int count = 0;
        for (AuxiliaryNode node : auxiliaryNodes)
            if (node.moduleId().equals(moduleId)) count += node.effectiveCount();
        return count;
    }

    public boolean hasInputOutputArchetype(String archetypeId) {
        return inputOutputNodes.stream().anyMatch(node -> node.archetypeId().equals(archetypeId));
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static final class AuxiliaryNode {
        private final UUID sourceNodeId;
        private final ResourceLocation moduleId;
        private final boolean active, hasInput;
        private final int effectiveCount;

        public AuxiliaryNode(UUID sourceNodeId, ResourceLocation moduleId, boolean active,
                boolean hasInput, int effectiveCount) {
            this.sourceNodeId = Objects.requireNonNull(sourceNodeId);
            this.moduleId = Objects.requireNonNull(moduleId);
            this.active = active;
            this.hasInput = hasInput;
            if (effectiveCount < 0) throw new IllegalArgumentException("Negative auxiliary count");
            this.effectiveCount = effectiveCount;
        }
        public UUID sourceNodeId() { return sourceNodeId; }
        public ResourceLocation moduleId() { return moduleId; }
        public boolean active() { return active; }
        public boolean hasInput() { return hasInput; }
        public int effectiveCount() { return effectiveCount; }
    }

    public static final class InputOutputNode {
        private final UUID nodeId;
        private final String mode, archetypeId;
        private final boolean enabled;
        private final Set<ResourceLocation> codecs;

        public InputOutputNode(UUID nodeId, String mode, String archetypeId, boolean enabled,
                Set<ResourceLocation> codecs) {
            this.nodeId = Objects.requireNonNull(nodeId);
            this.mode = mode == null ? "" : mode;
            this.archetypeId = archetypeId == null ? "" : archetypeId;
            this.enabled = enabled;
            this.codecs = Collections.unmodifiableSet(new LinkedHashSet<>(codecs));
        }
        public UUID nodeId() { return nodeId; }
        public String mode() { return mode; }
        public String archetypeId() { return archetypeId; }
        public boolean enabled() { return enabled; }
        public Set<ResourceLocation> codecs() { return codecs; }
    }

    public static final class MachineNode {
        private final UUID nodeId;
        private final ItemStack identity;
        private final boolean active;
        private final long machineCount;

        public MachineNode(UUID nodeId, ItemStack identity, boolean active, long machineCount) {
            this.nodeId = Objects.requireNonNull(nodeId);
            this.identity = identity == null || identity.isEmpty() ? ItemStack.EMPTY : identity.copy();
            this.identity.setCount(1);
            this.active = active;
            this.machineCount = machineCount;
        }
        public UUID nodeId() { return nodeId; }
        public ItemStack identity() { return identity.isEmpty() ? ItemStack.EMPTY : identity.copy(); }
        public boolean active() { return active; }
        public long machineCount() { return machineCount; }
    }

    public static final class Link {
        private final UUID linkId, sourceNode, targetNode;
        private final String sourcePort, targetPort;
        private final boolean enabled;
        private final double ratio;

        public Link(UUID linkId, UUID sourceNode, String sourcePort, UUID targetNode,
                String targetPort, boolean enabled, double ratio) {
            this.linkId = Objects.requireNonNull(linkId);
            this.sourceNode = Objects.requireNonNull(sourceNode);
            this.targetNode = Objects.requireNonNull(targetNode);
            this.sourcePort = sourcePort == null ? "" : sourcePort;
            this.targetPort = targetPort == null ? "" : targetPort;
            this.enabled = enabled;
            if (!Double.isFinite(ratio) || ratio < 0D || ratio > 1D)
                throw new IllegalArgumentException("Invalid context link ratio");
            this.ratio = ratio;
        }
        public UUID linkId() { return linkId; }
        public UUID sourceNode() { return sourceNode; }
        public String sourcePort() { return sourcePort; }
        public UUID targetNode() { return targetNode; }
        public String targetPort() { return targetPort; }
        public boolean enabled() { return enabled; }
        public double ratio() { return ratio; }
    }
}
