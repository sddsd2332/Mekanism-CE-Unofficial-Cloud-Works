package mekanism.cloudworks.common.service;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.*;
import mekanism.cloudworks.common.registry.MachineExecutionProfile;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeEffects;
import mekanism.cloudworks.api.CloudworksGenerationContext;

import java.util.*;

/**
 * Owner-thread projection of one conditional snapshot. Only View is allowed on
 * a worker; Capture retains the resource dictionary for owner-thread materialization.
 * Resource ids come from the player session symbol table, so they are stable
 * across captures of the same session and Waiting records may carry them over.
 */
public final class CloudworksDetachedState {
    private CloudworksDetachedState() {
    }

    public static Capture capture(CloudworksPlayerDataManager.VirtualSnapshot snapshot,
                                  CloudworksResourceSymbols symbols) {
        return capture(snapshot, symbols, new CloudworksGenerationContext(0, 1, 0, false));
    }

    public static Capture capture(CloudworksPlayerDataManager.VirtualSnapshot snapshot,
                                  CloudworksResourceSymbols symbols, CloudworksGenerationContext generationContext) {
        return capture(snapshot, symbols, generationContext, false);
    }

    /** Runtime rebuilds validation and derived capacities on its worker, never during server admission. */
    public static Capture captureRuntime(CloudworksPlayerDataManager.VirtualSnapshot snapshot,
                                  CloudworksResourceSymbols symbols, CloudworksGenerationContext generationContext) {
        return capture(snapshot, symbols, generationContext, true);
    }

    public static Capture captureRuntime(CloudworksPlayerDataManager.VirtualSnapshot snapshot,
            CloudworksResourceSymbols symbols, CloudworksGenerationContext context, List<ProductionLineData> lines) {
        return capture(snapshot, symbols, context, true, lines);
    }

    private static Capture capture(CloudworksPlayerDataManager.VirtualSnapshot snapshot,
                                  CloudworksResourceSymbols symbols, CloudworksGenerationContext generationContext,
                                  boolean deriveCapacity) {
        return capture(snapshot, symbols, generationContext, deriveCapacity, snapshot.lines());
    }

    private static Capture capture(CloudworksPlayerDataManager.VirtualSnapshot snapshot,
            CloudworksResourceSymbols symbols, CloudworksGenerationContext generationContext,
            boolean deriveCapacity, List<ProductionLineData> lines) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(symbols);
        CloudworksData cloud = snapshot.cloud();
        if (deriveCapacity) {
            double excess = 0;
            double[] capacities = new double[lines.size()];
            for (int index = 0; index < lines.size(); index++) {
                ProductionLineData line = lines.get(index);
                CloudworksTopologyValidator.Result topology = CloudworksTopologyValidator.validate(line);
                if (!topology.valid()) throw new IllegalArgumentException("Invalid Runtime topology: " + topology.reason());
                capacities[index] = line.upgradedEnergyCapacity();
                excess += Math.max(0, line.energy() - capacities[index]);
            }
            if (!cloud.unlimitedEnergy() && (!Double.isFinite(excess) || excess > cloud.maxEnergy() - cloud.energy()))
                throw new IllegalStateException("cloudworks.error.energy_not_empty");
            for (int index = 0; index < lines.size(); index++)
                lines.get(index).setEnergy(lines.get(index).energy(), capacities[index]);
            if (!cloud.unlimitedEnergy()) cloud.setEnergy(cloud.energy() + excess, cloud.maxEnergy(), false);
        }
        Dictionary dictionary = new Dictionary(symbols);
        List<Line> projected = new ArrayList<>(lines.size());
        for (ProductionLineData line : lines) {
            if (!cloud.owner().equals(line.owner()) || !line.explicitRouting() || !line.enabled())
                throw new IllegalArgumentException("Invalid detached line");
            projected.add(project(line, dictionary));
        }
        Inventory receiving = inventory(cloud.receiving(), dictionary);
        Inventory transfer = inventory(cloud.transfer(), dictionary);
        Inventory sending = inventory(cloud.sending(), dictionary);
        ResourceRules resourceRules = CloudworksNodeResourceRules.prepare(lines, symbols);
        View view = new View(cloud.owner(), cloud.enabled(), cloud.offlineProcessingUnlocked(),
                cloud.energy(), cloud.maxEnergy(),
                cloud.unlimitedEnergy(), Math.min(cloud.workRate(),
                    mekanism.cloudworks.common.config.CloudworksConfig.settings().maxWorkRate),
                cloud.receivingCursor(), cloud.transferCursor(),
                cloud.receivingEnergy(), cloud.sendingEnergy(), cloud.receivingEnergyCapacity(),
                cloud.sendingEnergyCapacity(), cloud.energyFlowReceivingToCloud(),
                cloud.energyFlowReceivingToLines(), cloud.energyFlowCloudToSending(),
                cloud.energyFlowLinesToSending(),
                receiving, transfer, sending, cloud.lineIds(), projected,
                symbols.generation(), symbols.codecs(), symbols.families(), resourceRules, generationContext);
        return new Capture(view, symbols, snapshot);
    }

    private static Inventory inventory(CloudworksInventory source, Dictionary dictionary) {
        Map<Integer, Long> quantities = new LinkedHashMap<>();
        source.snapshot().forEach((resource, amount) ->
                quantities.merge(dictionary.id(resource), amount, Math::addExact));
        return new Inventory(source.capacity(), quantities);
    }

    private static List<Stack> stacks(List<CloudworksResourceStack> source, Dictionary dictionary) {
        List<Stack> result = new ArrayList<>(source.size());
        for (CloudworksResourceStack stack : source)
            result.add(new Stack(dictionary.id(stack.descriptor()), stack.amount(), stack.reserved()));
        return Collections.unmodifiableList(result);
    }

    private static Line project(ProductionLineData source, Dictionary dictionary) {
        List<Node> nodes = new ArrayList<>();
        CloudworksUpgradeEffects upgrades = CloudworksUpgradeEffects.of(source);
        for (MachineNodeData node : source.machines()) {
            MachineNodeData.MachineCapabilities capabilities = node.capabilities();
            MachineExecutionProfile profile = capabilities.profile();
            MachineSlotLayout layout = capabilities.slots();
            List<Slot> slots = new ArrayList<>();
            if (layout != null) for (MachineSlotLayout.Slot slot : layout.slots())
                slots.add(new Slot(slot.id(), slot.codecId().toString(), slot.role().name(),
                        slot.capacity(), slot.multipleConnections()));
            CloudworksOperation operation = node.operation();
            Operation running = operation == null ? null : new Operation(operation.id(),
                    operation.operations(), operation.duration(), stacks(operation.inputs(), dictionary),
                    stacks(operation.outputs(), dictionary), operation.extraEnergyPerTick());
            String machineKey = capabilities.keyString();
            nodes.add(new Node(node.nodeId(), machineKey, node.machineCount(), node.active(),
                    node.progress(), node.totalTicks(), node.state(), node.dimensionId(),
                    profile == null ? 0 : profile.threads(),
                    profile == null ? 0D : upgrades.energyPerTick(profile.energyPerTick()),
                    profile == null ? 0D : upgrades.capacity(profile.energyCapacity()),
                    profile == null ? 1 : profile.totalTicks(),
                    capabilities.executable(),
                    slots, stacks(node.inputs(), dictionary), stacks(node.outputs(), dictionary),
                    stacks(node.templates(), dictionary), running, capabilities.thermal(), node.thermalState()));
        }
        List<Channel> channels = new ArrayList<>();
        for (CloudworksChannelNode channel : source.channels()) {
            Map<Integer, Long> manifest = new LinkedHashMap<>();
            channel.resources().forEach((resource, amount) ->
                    manifest.merge(dictionary.id(resource), amount, Math::addExact));
            channels.add(new Channel(channel.id(), channel.mode().name(), channel.enabled(),
                    manifest, inventory(channel.buffer(), dictionary)));
        }
        List<Link> links = new ArrayList<>();
        for (ProductionLineLink link : source.links()) {
            List<Integer> filters = new ArrayList<>();
            for (QIOResourceDescriptor filter : link.resourceFilters()) filters.add(dictionary.id(filter));
            links.add(new Link(link.linkId(), link.sourceNode(), link.sourcePort(), link.targetNode(),
                    link.targetPort(), link.enabled(), link.priority(), link.ratio(),
                    link.ratioRemainder(), filters));
        }
        return new Line(source.lineId(), source.runWhileOwnerOffline(), source.energy(),
                source.maxEnergy(),
                source.speedUpgrades(), source.energyUpgrades(),
                source.maxSpeedUpgrades(), source.maxEnergyUpgrades(),
                upgrades.timeMultiplier(), upgrades.energyPerTick(1D), upgrades.configured(),
                source.energyFlowReceivingToLine(), source.energyFlowLineToSending(),
                nodes, channels, links);
    }

    private static <K, V> Map<K, V> immutable(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public static final class Capture {
        private final View view;
        private final CloudworksResourceSymbols symbols;
        private final CloudworksPlayerDataManager.VirtualSnapshot origin;

        private Capture(View view, CloudworksResourceSymbols symbols,
                        CloudworksPlayerDataManager.VirtualSnapshot origin) {
            this.view = view;
            this.symbols = symbols;
            this.origin = origin;
        }

        public View view() {
            return view;
        }

        /**
         * Resolve only on the owner thread after a successful conditional commit.
         * Delegates to the append-only session table so ids registered after the
         * capture (recipe products) resolve as well.
         */
        public QIOResourceDescriptor descriptor(int resourceId) {
            return symbols.descriptor(resourceId);
        }

        public int resourceCount() {
            return symbols.size();
        }

        /**
         * The snapshot this capture was built from; results must materialize against exactly it.
         */
        public CloudworksPlayerDataManager.VirtualSnapshot originSnapshot() {
            return origin;
        }
    }

    /**
     * Thin adapter so projection code keeps one dictionary spelling.
     */
    private static final class Dictionary {
        private final CloudworksResourceSymbols symbols;

        private Dictionary(CloudworksResourceSymbols symbols) {
            this.symbols = symbols;
        }

        private int id(QIOResourceDescriptor descriptor) {
            return symbols.id(descriptor);
        }
    }

    public static final class View {
        public final UUID owner;
        public final boolean enabled, offlineProcessingUnlocked, unlimitedEnergy;
        public final double energy, maxEnergy;
        public final double receivingEnergy, sendingEnergy, receivingCapacity, sendingCapacity;
        public final boolean energyFlowReceivingToCloud, energyFlowReceivingToLines;
        public final boolean energyFlowCloudToSending, energyFlowLinesToSending;
        public final int workRate, receivingCursor, transferCursor;
        public final Inventory receiving, transfer, sending;
        public final List<UUID> allLineIds;
        /**
         * Dictionary generation of the session symbol table at capture time.
         */
        public final long symbolGeneration;
        public final List<String> resourceCodecs;
        /**
         * Resource families indexed by the same ids; per-type warehouse caps are family-keyed.
         */
        public final List<String> resourceFamilies;
        public final List<Line> lines;
        public final ResourceRules resourceRules;
        public final CloudworksGenerationContext generationContext;

        private View(UUID owner, boolean enabled, boolean offlineProcessingUnlocked,
                     double energy, double maxEnergy,
                     boolean unlimitedEnergy, int workRate, int receivingCursor, int transferCursor,
                     double receivingEnergy, double sendingEnergy, double receivingCapacity, double sendingCapacity,
                     boolean energyFlowReceivingToCloud, boolean energyFlowReceivingToLines,
                     boolean energyFlowCloudToSending, boolean energyFlowLinesToSending,
                     Inventory receiving, Inventory transfer, Inventory sending,
                     List<UUID> allLineIds, List<Line> lines, long symbolGeneration,
                     List<String> resourceCodecs, List<String> resourceFamilies, ResourceRules resourceRules,
                     CloudworksGenerationContext generationContext) {
            this.owner = owner;
            this.enabled = enabled;
            this.offlineProcessingUnlocked = offlineProcessingUnlocked;
            this.energy = energy;
            this.maxEnergy = maxEnergy;
            this.unlimitedEnergy = unlimitedEnergy;
            this.workRate = workRate;
            this.receivingCursor = receivingCursor;
            this.transferCursor = transferCursor;
            this.receivingEnergy = receivingEnergy;
            this.sendingEnergy = sendingEnergy;
            this.receivingCapacity = receivingCapacity;
            this.sendingCapacity = sendingCapacity;
            this.energyFlowReceivingToCloud = energyFlowReceivingToCloud;
            this.energyFlowReceivingToLines = energyFlowReceivingToLines;
            this.energyFlowCloudToSending = energyFlowCloudToSending;
            this.energyFlowLinesToSending = energyFlowLinesToSending;
            this.receiving = receiving;
            this.transfer = transfer;
            this.sending = sending;
            this.allLineIds = Collections.unmodifiableList(new ArrayList<>(allLineIds));
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
            this.symbolGeneration = symbolGeneration;
            this.resourceCodecs = Collections.unmodifiableList(new ArrayList<>(resourceCodecs));
            this.resourceFamilies = Collections.unmodifiableList(new ArrayList<>(resourceFamilies));
            this.resourceRules = resourceRules;
            this.generationContext = generationContext;
        }
    }

    /** Immutable, worker-safe resource conversion and output routing rules. */
    public static final class ResourceRules {
        private static final String INFUSE_CODEC = "cloudworks:infuse";
        private final Map<Integer, Conversion> conversions;
        private final Set<Integer> infuseTypes;
        private final Map<String, Map<Integer, Set<String>>> outputPorts;
        private final Map<String, Map<String, Set<Integer>>> inputPorts;

        ResourceRules(Map<Integer, Conversion> conversions, Set<Integer> infuseTypes,
                Map<String, Map<Integer, Set<String>>> ports,
                Map<String, Map<String, Set<Integer>>> inputs) {
            this.conversions = immutable(conversions);
            this.infuseTypes = Collections.unmodifiableSet(new HashSet<>(infuseTypes));
            Map<String, Map<Integer, Set<String>>> copy = new LinkedHashMap<>();
            ports.forEach((machine, resources) -> {
                Map<Integer, Set<String>> resourceCopy = new LinkedHashMap<>();
                resources.forEach((id, names) -> resourceCopy.put(id, Collections.unmodifiableSet(new HashSet<>(names))));
                copy.put(machine, immutable(resourceCopy));
            });
            outputPorts = immutable(copy);
            Map<String, Map<String, Set<Integer>>> inputCopy = new LinkedHashMap<>();
            inputs.forEach((machine, rules) -> {
                Map<String, Set<Integer>> portCopy = new LinkedHashMap<>();
                rules.forEach((port, ids) -> portCopy.put(port, Collections.unmodifiableSet(new HashSet<>(ids))));
                inputCopy.put(machine, immutable(portCopy));
            });
            inputPorts = immutable(inputCopy);
        }

        /** Extra inputs have their own admission and capacity, even though recipes use a shared resource ledger. */
        public long inputRoom(Node node, String port, int resource, Map<Integer, Long> available) {
            Set<Integer> accepted = inputPorts.getOrDefault(node.machineKey, Collections.emptyMap()).get(port);
            if (port.equals("infuse_item_input")) accepted = conversions.keySet();
            if (accepted == null) return Long.MAX_VALUE;
            if (!accepted.contains(resource)) return 0;
            long capacity = 0;
            for (Slot slot : node.slots)
                if (slot.id.equals(port) && slot.role.equals("INPUT"))
                    capacity = CloudworksQuantities.multiplyLimit(slot.capacity, node.machineCount);
            long stored = 0;
            for (Map.Entry<Integer, Long> entry : available.entrySet())
                if (accepted.contains(entry.getKey())) stored = CloudworksQuantities.addLimit(stored, entry.getValue());
            for (Stack stack : node.inputs)
                if (accepted.contains(stack.resourceId)) stored = CloudworksQuantities.addLimit(stored, stack.reserved);
            return Math.max(0, capacity - stored);
        }

        /** Reserved inputs are unavailable, but reserved infusion still occupies capacity and fixes the tank type. */
        public void convertInfuse(Node node, Map<Integer, Long> available) {
            if (!node.active || conversions.isEmpty()) return;
            long capacity = 0;
            for (Slot slot : node.slots)
                if ("INPUT".equals(slot.role) && INFUSE_CODEC.equals(slot.codec))
                    capacity = CloudworksQuantities.addLimit(capacity,
                            CloudworksQuantities.multiplyLimit(slot.capacity, node.machineCount));
            if (capacity == 0) return;
            long stored = 0;
            Integer type = null;
            for (Map.Entry<Integer, Long> entry : available.entrySet()) {
                if (entry.getValue() <= 0 || !infuseTypes.contains(entry.getKey())) continue;
                if (type != null && !type.equals(entry.getKey())) return;
                type = entry.getKey();
                stored = Math.addExact(stored, entry.getValue());
            }
            for (Stack stack : node.inputs) {
                if (stack.reserved <= 0 || !infuseTypes.contains(stack.resourceId)) continue;
                if (type != null && type != stack.resourceId) return;
                type = stack.resourceId;
                stored = Math.addExact(stored, stack.reserved);
            }
            if (stored >= capacity) return;
            for (Map.Entry<Integer, Long> input : new ArrayList<>(available.entrySet())) {
                Conversion conversion = conversions.get(input.getKey());
                if (conversion == null || type != null && type != conversion.target) continue;
                long count = Math.min(input.getValue(), (capacity - stored) / conversion.units);
                if (count <= 0) continue;
                long produced = Math.multiplyExact(count, conversion.units);
                long remaining = input.getValue() - count;
                long targetAmount = Math.addExact(available.getOrDefault(conversion.target, 0L), produced);
                if (remaining == 0) available.remove(input.getKey()); else available.put(input.getKey(), remaining);
                available.put(conversion.target, targetAmount);
                stored += produced;
                type = conversion.target;
            }
        }

        /** Untagged and chance outputs retain their existing behavior; input ports are unaffected. */
        public boolean allowsOutput(Node node, int resource, String port, String codec) {
            int matchingPorts = 0;
            for (Slot slot : node.slots)
                if ("OUTPUT".equals(slot.role) && slot.codec.equals(codec)) matchingPorts++;
            if (matchingPorts <= 1) return true;
            Set<String> ports = outputPorts.getOrDefault(node.machineKey, Collections.emptyMap()).get(resource);
            return ports == null || ports.contains("") || ports.contains(port);
        }

        public boolean acceptsInput(String machineKey, String port, int resource) {
            Set<Integer> accepted = inputPorts.getOrDefault(machineKey, Collections.emptyMap()).get(port);
            if (port.equals("infuse_item_input")) accepted = conversions.keySet();
            return accepted == null || accepted.contains(resource);
        }

        public int conversionTarget(int resource) {
            Conversion conversion = conversions.get(resource);
            return conversion == null ? -1 : conversion.target;
        }

        public int conversionUnits(int resource) {
            Conversion conversion = conversions.get(resource);
            return conversion == null ? 0 : conversion.units;
        }
    }

    static final class Conversion {
        private final int target, units;

        Conversion(int target, int units) {
            this.target = target;
            this.units = units;
        }
    }

    public static final class Inventory {
        public final long capacity;
        public final Map<Integer, Long> contents;

        private Inventory(long capacity, Map<Integer, Long> contents) {
            this.capacity = capacity;
            this.contents = immutable(contents);
        }
    }

    public static final class Stack {
        public final int resourceId;
        public final long amount, reserved;

        private Stack(int resourceId, long amount, long reserved) {
            this.resourceId = resourceId;
            this.amount = amount;
            this.reserved = reserved;
        }
    }

    public static final class Line {
        public final UUID id;
        public final boolean runWhileOwnerOffline;
        public final double energy, maxEnergy;
        public final int speedUpgrades, energyUpgrades;
        public final int maxSpeedUpgrades, maxEnergyUpgrades;
        public final double timeMultiplier, energyMultiplier;
        public final boolean configuredUpgrades;
        public final boolean energyFlowReceivingToLine, energyFlowLineToSending;
        public final List<Node> nodes;
        public final List<Channel> channels;
        public final List<Link> links;

        private Line(UUID id, boolean runWhileOwnerOffline, double energy, double maxEnergy,
                     int speedUpgrades, int energyUpgrades,
                     int maxSpeedUpgrades, int maxEnergyUpgrades, double timeMultiplier, double energyMultiplier,
                     boolean configuredUpgrades,
                     boolean energyFlowReceivingToLine, boolean energyFlowLineToSending,
                     List<Node> nodes, List<Channel> channels,
                     List<Link> links) {
            this.id = id;
            this.runWhileOwnerOffline = runWhileOwnerOffline;
            this.energy = energy;
            this.maxEnergy = maxEnergy;
            this.speedUpgrades = speedUpgrades;
            this.energyUpgrades = energyUpgrades;
            this.maxSpeedUpgrades = maxSpeedUpgrades;
            this.maxEnergyUpgrades = maxEnergyUpgrades;
            this.timeMultiplier = timeMultiplier;
            this.energyMultiplier = energyMultiplier;
            this.configuredUpgrades = configuredUpgrades;
            this.energyFlowReceivingToLine = energyFlowReceivingToLine;
            this.energyFlowLineToSending = energyFlowLineToSending;
            this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
            this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
            this.links = Collections.unmodifiableList(new ArrayList<>(links));
        }
    }

    public static final class Node {
        public final UUID id;
        public final String machineKey;
        public final long machineCount;
        public final int progress, totalTicks, dimensionId, threads, baseTicks;
        public final boolean active, executable;
        public final String state;
        public final double energyPerTick, energyCapacity;
        public final List<Slot> slots;
        public final List<Stack> inputs, outputs, templates;
        public final Operation operation;
        public final mekanism.cloudworks.api.CloudworksThermalPlan thermal;
        public final CloudworksThermalState thermalState;

        private Node(UUID id, String machineKey, long machineCount, boolean active, int progress, int totalTicks,
                     String state, int dimensionId, int threads, double energyPerTick,
                     double energyCapacity, int baseTicks, boolean executable, List<Slot> slots,
                     List<Stack> inputs, List<Stack> outputs, List<Stack> templates, Operation operation,
                     mekanism.cloudworks.api.CloudworksThermalPlan thermal, CloudworksThermalState thermalState) {
            this.id = id;
            this.machineKey = machineKey;
            this.machineCount = machineCount;
            this.active = active;
            this.progress = progress;
            this.totalTicks = totalTicks;
            this.state = state;
            this.dimensionId = dimensionId;
            this.threads = threads;
            this.energyPerTick = energyPerTick;
            this.energyCapacity = energyCapacity;
            this.baseTicks = baseTicks;
            this.executable = executable;
            this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
            this.inputs = inputs;
            this.outputs = outputs;
            this.templates = templates;
            this.operation = operation;
            this.thermal = thermal;
            this.thermalState = thermalState;
        }
    }

    public static final class Slot {
        public final String id, codec, role;
        public final int capacity;
        public final boolean multipleConnections;

        private Slot(String id, String codec, String role, int capacity, boolean multipleConnections) {
            this.id = id;
            this.codec = codec;
            this.role = role;
            this.capacity = capacity;
            this.multipleConnections = multipleConnections;
        }
    }

    public static final class Operation {
        public final UUID id;
        public final int operations, duration;
        public final double extraEnergyPerTick;
        public final List<Stack> inputs, outputs;

        private Operation(UUID id, int operations, int duration, List<Stack> inputs, List<Stack> outputs,
                double extraEnergyPerTick) {
            this.id = id;
            this.operations = operations;
            this.duration = duration;
            this.inputs = inputs;
            this.outputs = outputs;
            this.extraEnergyPerTick = extraEnergyPerTick;
        }
    }

    public static final class Channel {
        public final UUID id;
        public final String mode;
        public final boolean enabled;
        public final Map<Integer, Long> manifest;
        public final Inventory buffer;

        private Channel(UUID id, String mode, boolean enabled, Map<Integer, Long> manifest, Inventory buffer) {
            this.id = id;
            this.mode = mode;
            this.enabled = enabled;
            this.manifest = immutable(manifest);
            this.buffer = buffer;
        }
    }

    public static final class Link {
        public final UUID id, source, target;
        public final String sourcePort, targetPort;
        public final boolean enabled;
        public final int priority;
        public final double ratio, ratioRemainder;
        public final List<Integer> filterResourceIds;

        private Link(UUID id, UUID source, String sourcePort, UUID target, String targetPort,
                     boolean enabled, int priority, double ratio, double ratioRemainder, List<Integer> filterResourceIds) {
            this.id = id;
            this.source = source;
            this.sourcePort = sourcePort;
            this.target = target;
            this.targetPort = targetPort;
            this.enabled = enabled;
            this.priority = priority;
            this.ratio = ratio;
            this.ratioRemainder = ratioRemainder;
            this.filterResourceIds = Collections.unmodifiableList(new ArrayList<>(filterResourceIds));
        }
    }
}
