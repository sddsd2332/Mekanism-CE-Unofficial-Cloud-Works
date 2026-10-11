package mekanism.cloudworks.common.network.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import mekanism.common.content.qio.QIOResourceEntry;
import net.minecraft.item.ItemStack;

/** Server-authoritative presentation data, independent of persistent cloud NBT. */
public final class CloudworksGuiData {
    public static final CloudworksGuiData EMPTY = new CloudworksGuiData(
            Overview.EMPTY, Graph.EMPTY, MachineCache.EMPTY, Choices.EMPTY,
            Warehouses.EMPTY, Collections.emptyList());

    public final Overview overview;
    public final Graph graph;
    public final MachineCache machineCache;
    public final Choices choices;
    public final Warehouses warehouses;
    public final List<QIOResourceEntry> recycle;

    public CloudworksGuiData(Overview overview, Graph graph, MachineCache machineCache,
            Choices choices, Warehouses warehouses, List<QIOResourceEntry> recycle) {
        this.overview = overview;
        this.graph = graph;
        this.machineCache = machineCache;
        this.choices = choices;
        this.warehouses = warehouses;
        this.recycle = recycle == null ? null : Lists.immutable(recycle);
    }

    public CloudworksGuiData apply(CloudworksGuiData update) {
        return new CloudworksGuiData(update.overview == null ? overview : update.overview,
                update.graph == null ? graph : update.graph,
                update.machineCache == null ? machineCache : update.machineCache,
                update.choices == null ? choices : update.choices,
                update.warehouses == null ? warehouses : update.warehouses,
                update.recycle == null ? recycle : update.recycle);
    }

    // Nested EMPTY snapshots must not initialize the outer class before their fields are assigned.
    private static final class Lists {
        private static <T> List<T> immutable(List<T> values) {
            return Collections.unmodifiableList(new ArrayList<>(values));
        }
    }

    public static final class Overview {
        public static final Overview EMPTY = new Overview(0, 0, 0, 0, 1, 0,
                0, 0, false, 0, 0, false, false, 0, 0, 1, Collections.emptyList());
        public final int offset, total, unlocked, maximum, workRate;
        public final long revision;
        public final double energy, maxEnergy, receivingEnergy, sendingEnergy, receivingCapacity, sendingCapacity;
        public final boolean unlimited, receivingToCloud, cloudToSending;
        public final int speedUpgradePool, energyUpgradePool, clockTick;
        public final int simulatedHeight, lavaHeatCards, maxMachineNodes;
        public final int machineNodeLimit;
        public final long maxMachinesPerNode;
        public final boolean offlineUnlocked, netherEnvironment, offlineProcessingEnabled;
        public final boolean recycleToTransfer;
        public final List<Line> lines;

        public Overview(int offset, int total, int unlocked, int maximum, int workRate, long revision,
                double energy, double maxEnergy, boolean unlimited, double receivingEnergy,
                double sendingEnergy, boolean receivingToCloud, boolean cloudToSending,
                int speedUpgradePool, int energyUpgradePool, int clockTick, List<Line> lines) {
            this(offset, total, unlocked, maximum, workRate, revision, energy, maxEnergy, unlimited,
                    receivingEnergy, sendingEnergy, receivingToCloud, cloudToSending, speedUpgradePool,
                    energyUpgradePool, clockTick, lines, 0, 0, 64, false, false);
        }

        public Overview(int offset, int total, int unlocked, int maximum, int workRate, long revision,
                double energy, double maxEnergy, boolean unlimited, double receivingEnergy,
                double sendingEnergy, boolean receivingToCloud, boolean cloudToSending,
                int speedUpgradePool, int energyUpgradePool, int clockTick, List<Line> lines,
                int simulatedHeight, int lavaHeatCards, int maxMachineNodes,
                boolean offlineUnlocked, boolean netherEnvironment) {
            this(offset, total, unlocked, maximum, workRate, revision, energy, maxEnergy, unlimited, receivingEnergy,
                    sendingEnergy, receivingToCloud, cloudToSending, speedUpgradePool, energyUpgradePool,
                    clockTick, lines, simulatedHeight, lavaHeatCards, maxMachineNodes, offlineUnlocked,
                    netherEnvironment, unlimited ? Double.MAX_VALUE : maxEnergy,
                    unlimited ? Double.MAX_VALUE : maxEnergy, true);
        }

        public Overview(int offset, int total, int unlocked, int maximum, int workRate, long revision,
                double energy, double maxEnergy, boolean unlimited, double receivingEnergy,
                double sendingEnergy, boolean receivingToCloud, boolean cloudToSending,
                int speedUpgradePool, int energyUpgradePool, int clockTick, List<Line> lines,
                int simulatedHeight, int lavaHeatCards, int maxMachineNodes,
                boolean offlineUnlocked, boolean netherEnvironment, double receivingCapacity,
                double sendingCapacity, boolean offlineProcessingEnabled) {
            this(offset, total, unlocked, maximum, workRate, revision, energy, maxEnergy, unlimited,
                    receivingEnergy, sendingEnergy, receivingToCloud, cloudToSending, speedUpgradePool,
                    energyUpgradePool, clockTick, lines, simulatedHeight, lavaHeatCards, maxMachineNodes,
                    offlineUnlocked, netherEnvironment, receivingCapacity, sendingCapacity,
                    offlineProcessingEnabled, 4096, 1024, true);
        }

        public Overview(int offset, int total, int unlocked, int maximum, int workRate, long revision,
                double energy, double maxEnergy, boolean unlimited, double receivingEnergy,
                double sendingEnergy, boolean receivingToCloud, boolean cloudToSending,
                int speedUpgradePool, int energyUpgradePool, int clockTick, List<Line> lines,
                int simulatedHeight, int lavaHeatCards, int maxMachineNodes,
                boolean offlineUnlocked, boolean netherEnvironment, double receivingCapacity,
                double sendingCapacity, boolean offlineProcessingEnabled, int machineNodeLimit,
                long maxMachinesPerNode, boolean recycleToTransfer) {
            this.offset = offset;
            this.total = total;
            this.unlocked = unlocked;
            this.maximum = maximum;
            this.workRate = workRate;
            this.revision = revision;
            this.energy = energy;
            this.maxEnergy = maxEnergy;
            this.unlimited = unlimited;
            this.receivingEnergy = receivingEnergy;
            this.sendingEnergy = sendingEnergy;
            this.receivingToCloud = receivingToCloud;
            this.cloudToSending = cloudToSending;
            this.speedUpgradePool = speedUpgradePool;
            this.energyUpgradePool = energyUpgradePool;
            this.clockTick = clockTick;
            this.simulatedHeight = simulatedHeight;
            this.lavaHeatCards = lavaHeatCards;
            this.maxMachineNodes = maxMachineNodes;
            this.offlineUnlocked = offlineUnlocked;
            this.netherEnvironment = netherEnvironment;
            this.receivingCapacity = receivingCapacity;
            this.sendingCapacity = sendingCapacity;
            this.offlineProcessingEnabled = offlineProcessingEnabled;
            this.machineNodeLimit = machineNodeLimit;
            this.maxMachinesPerNode = maxMachinesPerNode;
            this.recycleToTransfer = recycleToTransfer;
            this.lines = Lists.immutable(lines);
        }

    }

    public static final class Line {
        public final UUID id;
        public final String name;
        public final boolean available, enabled, receivingFlow, sendingFlow, runWhileOwnerOffline;
        public final int nodeCount;
        public final double energy, maxEnergy;
        public final ItemStack icon;
        public final List<mekanism.api.qio.resource.QIOResourceDescriptor> markers = new ArrayList<>();

        public Line(UUID id, String name, boolean available, boolean enabled, int nodeCount,
                double energy, double maxEnergy, boolean receivingFlow, boolean sendingFlow, ItemStack icon) {
            this(id, name, available, enabled, nodeCount, energy, maxEnergy, receivingFlow, sendingFlow, icon, false);
        }

        public Line(UUID id, String name, boolean available, boolean enabled, int nodeCount,
                double energy, double maxEnergy, boolean receivingFlow, boolean sendingFlow,
                ItemStack icon, boolean runWhileOwnerOffline) {
            this.id = id;
            this.name = name;
            this.available = available;
            this.enabled = enabled;
            this.nodeCount = nodeCount;
            this.energy = energy;
            this.maxEnergy = maxEnergy;
            this.receivingFlow = receivingFlow;
            this.sendingFlow = sendingFlow;
            this.runWhileOwnerOffline = runWhileOwnerOffline;
            this.icon = icon.copy();
        }
    }

    public static final class Graph {
        public static final Graph EMPTY = new Graph(null, "", false, false, 0, 0, 0, 0,
                Collections.emptyList(), Collections.emptyList());
        public final UUID id;
        public final String name;
        public final boolean enabled, explicitRouting;
        public final double energy, maxEnergy;
        public final int speed, energyUpgrades;
        public final List<Node> nodes;
        public final List<Link> links;
        public CloudworksLineDraft configuration;

        public Graph(UUID id, String name, boolean enabled, boolean explicitRouting,
                double energy, double maxEnergy, int speed, int energyUpgrades,
                List<Node> nodes, List<Link> links) {
            this.id = id;
            this.name = name;
            this.enabled = enabled;
            this.explicitRouting = explicitRouting;
            this.energy = energy;
            this.maxEnergy = maxEnergy;
            this.speed = speed;
            this.energyUpgrades = energyUpgrades;
            this.nodes = Lists.immutable(nodes);
            this.links = Lists.immutable(links);
        }
    }

    public static final class Node {
        public final UUID id;
        public final String name, state;
        public final ItemStack icon;
        public final int x, z, count, progress, duration;
        public final boolean active;
        public final List<ResourceSummary> input, output, templates;
        public final List<Port> ports;
        public final mekanism.cloudworks.common.data.CloudworksThermalState thermal;

        public Node(UUID id, String name, String state, ItemStack icon, int x, int z, int count,
                int progress, int duration, boolean active, List<ResourceSummary> input,
                List<ResourceSummary> output, List<Port> ports) {
            this(id, name, state, icon, x, z, count, progress, duration, active, input, output,
                    Collections.emptyList(), ports);
        }

        public Node(UUID id, String name, String state, ItemStack icon, int x, int z, int count,
                int progress, int duration, boolean active, List<ResourceSummary> input,
                List<ResourceSummary> output, List<ResourceSummary> templates, List<Port> ports) {
            this(id, name, state, icon, x, z, count, progress, duration, active, input, output, templates, ports, null);
        }

        public Node(UUID id, String name, String state, ItemStack icon, int x, int z, int count,
                int progress, int duration, boolean active, List<ResourceSummary> input,
                List<ResourceSummary> output, List<ResourceSummary> templates, List<Port> ports,
                mekanism.cloudworks.common.data.CloudworksThermalState thermal) {
            this.id = id;
            this.name = name;
            this.state = state;
            this.icon = icon.copy();
            this.x = x;
            this.z = z;
            this.count = count;
            this.progress = progress;
            this.duration = duration;
            this.active = active;
            this.input = Lists.immutable(input);
            this.output = Lists.immutable(output);
            this.templates = Lists.immutable(templates);
            this.ports = Lists.immutable(ports);
            this.thermal = thermal;
        }

        public Node at(int x, int z) {
            return new Node(id, name, state, icon, x, z, count, progress, duration, active, input, output, templates, ports, thermal);
        }
    }

    public static final class Port {
        public final String id, codec;
        public final boolean output;

        public Port(String id, String codec, boolean output) {
            this.id = id;
            this.codec = codec;
            this.output = output;
        }
    }

    public static final class ResourceSummary {
        public final String name;
        public final long amount;
        public final mekanism.api.qio.resource.QIOResourceDescriptor descriptor;

        public ResourceSummary(String name, long amount, mekanism.api.qio.resource.QIOResourceDescriptor descriptor) {
            this.name = name;
            this.amount = amount;
            this.descriptor = descriptor;
        }
    }

    public static final class Link {
        public final UUID id, sourceNode, targetNode;
        public final String sourcePort, targetPort;
        public final List<String> filterNames;
        public final boolean enabled;
        public final int priority;
        public final double ratio;

        public Link(UUID id, UUID sourceNode, String sourcePort, UUID targetNode, String targetPort,
                boolean enabled, int priority, double ratio, List<String> filterNames) {
            this.id = id;
            this.sourceNode = sourceNode;
            this.sourcePort = sourcePort;
            this.targetNode = targetNode;
            this.targetPort = targetPort;
            this.enabled = enabled;
            this.priority = priority;
            this.ratio = ratio;
            this.filterNames = Lists.immutable(filterNames);
        }
    }

    public static final class MachineCache {
        public static final MachineCache EMPTY = new MachineCache(-2, Collections.emptyList(), Collections.emptyList());
        public final int selectedType;
        public final List<Machine> types, variants;

        public MachineCache(int selectedType, List<Machine> types, List<Machine> variants) {
            this.selectedType = selectedType;
            this.types = Lists.immutable(types);
            this.variants = Lists.immutable(variants);
        }
    }

    public static final class Machine {
        public final ItemStack stack;
        public final long count, claimed, available;
        public final List<Claim> claims;

        public Machine(ItemStack stack, long count, long claimed, long available, List<Claim> claims) {
            this.stack = stack.copy();
            this.count = count;
            this.claimed = claimed;
            this.available = available;
            this.claims = Lists.immutable(claims);
        }
    }

    public static final class Claim {
        public final UUID line;
        public final String name;
        public final long count;

        public Claim(UUID line, String name, long count) {
            this.line = line;
            this.name = name;
            this.count = count;
        }
    }

    public static final class Choices {
        public static final Choices EMPTY = new Choices(0, 0, Collections.emptyList(), Collections.emptyList(), null);
        public final int offset, total;
        public final List<String> names;
        public final List<mekanism.api.qio.resource.QIOResourceDescriptor> resources;
        public final UUID context;

        public Choices(int offset, int total, List<String> names,
                List<mekanism.api.qio.resource.QIOResourceDescriptor> resources, UUID context) {
            this.offset = offset;
            this.total = total;
            this.names = Lists.immutable(names);
            this.resources = Lists.immutable(resources);
            this.context = context;
        }
    }

    public static final class Warehouses {
        public static final Warehouses EMPTY = new Warehouses(Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        public final List<QIOResourceEntry> receiving, transfer, sending;

        public Warehouses(List<QIOResourceEntry> receiving, List<QIOResourceEntry> transfer, List<QIOResourceEntry> sending) {
            this.receiving = Lists.immutable(receiving);
            this.transfer = Lists.immutable(transfer);
            this.sending = Lists.immutable(sending);
        }
    }
}
