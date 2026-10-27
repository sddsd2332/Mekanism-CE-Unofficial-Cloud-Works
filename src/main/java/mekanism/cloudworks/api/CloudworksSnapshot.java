package mekanism.cloudworks.api;

import java.util.*;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.item.ItemStack;

/** Immutable authorized query result. Revision is owner-scoped; timestamp is the sampling time. */
public final class CloudworksSnapshot {
    public final UUID owner;
    public final long revision, sampledAtMillis, total;
    public final boolean enabled, offlineUnlocked;
    public final double energy, capacity, receivingEnergy, receivingCapacity, sendingEnergy, sendingCapacity;
    public final int workRate, clockTick;
    public final CloudworksQuery query;
    public final List<Line> lines;
    public final List<Node> nodes;
    public final List<Resource> resources;
    public final Map<String, Long> effects;

    public CloudworksSnapshot(UUID owner, long revision, long sampledAtMillis, boolean enabled, boolean offlineUnlocked,
            double energy, double capacity, double receivingEnergy, double receivingCapacity,
            double sendingEnergy, double sendingCapacity, int workRate, int clockTick,
            CloudworksQuery query, long total, List<Line> lines, List<Node> nodes, List<Resource> resources,
            Map<String, Long> effects) {
        this.owner = Objects.requireNonNull(owner);
        this.revision = revision;
        this.sampledAtMillis = sampledAtMillis;
        this.enabled = enabled;
        this.offlineUnlocked = offlineUnlocked;
        this.energy = energy;
        this.capacity = capacity;
        this.receivingEnergy = receivingEnergy;
        this.receivingCapacity = receivingCapacity;
        this.sendingEnergy = sendingEnergy;
        this.sendingCapacity = sendingCapacity;
        this.workRate = workRate;
        this.clockTick = clockTick;
        this.query = Objects.requireNonNull(query);
        this.total = total;
        this.lines = immutable(lines);
        this.nodes = immutable(nodes);
        this.resources = immutable(resources);
        this.effects = Collections.unmodifiableMap(new LinkedHashMap<>(effects));
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static final class Line {
        public final UUID id;
        public final String name;
        public final boolean enabled, offline;
        public final double energy, capacity;
        public final int machines, channels;
        public Line(UUID id, String name, boolean enabled, boolean offline, double energy,
                double capacity, int machines, int channels) {
            this.id = id; this.name = name; this.enabled = enabled; this.offline = offline;
            this.energy = energy; this.capacity = capacity; this.machines = machines; this.channels = channels;
        }
    }

    public static final class Node {
        public final UUID id;
        public final String kind, state;
        public final boolean enabled;
        public final long machineCount;
        public final int x, y;
        private final ItemStack machine;
        public Node(UUID id, String kind, String state, boolean enabled, long machineCount,
                int x, int y, ItemStack machine) {
            this.id = id; this.kind = kind; this.state = state; this.enabled = enabled;
            this.machineCount = machineCount; this.x = x; this.y = y; this.machine = machine.copy();
        }
        public ItemStack machine() { return machine.copy(); }
    }

    public static final class Resource {
        public final QIOResourceDescriptor descriptor;
        public final long amount, reserved;
        public Resource(QIOResourceDescriptor descriptor, long amount, long reserved) {
            this.descriptor = Objects.requireNonNull(descriptor);
            this.amount = amount; this.reserved = reserved;
        }
    }
}
