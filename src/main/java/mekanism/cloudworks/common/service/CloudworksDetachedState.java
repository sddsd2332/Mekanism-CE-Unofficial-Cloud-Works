package mekanism.cloudworks.common.service;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.common.data.*;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeEffects;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.api.CloudworksAreaGeometry;
import mekanism.cloudworks.api.module.MachineModuleHandle;
import mekanism.cloudworks.api.module.MachineModuleSource;
import mekanism.cloudworks.api.node.CloudworksAuxiliaryNodeType;
import mekanism.cloudworks.api.module.MachineContextSnapshot;
import mekanism.cloudworks.common.registry.CloudworksAuxiliaryNodeRegistry;

import java.util.*;
import net.minecraft.item.ItemStack;
import mekanism.cloudworks.api.environment.CloudworksGenerationPlan;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.cloudworks.api.module.ModuleArea;
import mekanism.cloudworks.common.config.CloudworksConfig;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeStore;
import net.minecraft.util.ResourceLocation;

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
    public static Capture captureRuntime(CloudworksPlayerDataManager.VirtualSnapshot snapshot,
            CloudworksResourceSymbols symbols, CloudworksGenerationContext context, List<ProductionLineData> lines,
            Map<UUID, CloudworksGenerationPlan> generation) {
        return capture(snapshot, symbols, context, true, lines, generation);
    }

    private static Capture capture(CloudworksPlayerDataManager.VirtualSnapshot snapshot,
                                  CloudworksResourceSymbols symbols, CloudworksGenerationContext generationContext,
                                  boolean deriveCapacity) {
        return capture(snapshot, symbols, generationContext, deriveCapacity, snapshot.lines());
    }

    private static Capture capture(CloudworksPlayerDataManager.VirtualSnapshot snapshot,
            CloudworksResourceSymbols symbols, CloudworksGenerationContext generationContext,
            boolean deriveCapacity, List<ProductionLineData> lines) {
        return capture(snapshot, symbols, generationContext, deriveCapacity, lines, Collections.emptyMap());
    }

    private static Capture capture(CloudworksPlayerDataManager.VirtualSnapshot snapshot,
            CloudworksResourceSymbols symbols, CloudworksGenerationContext generationContext,
            boolean deriveCapacity, List<ProductionLineData> lines,
            Map<UUID, CloudworksGenerationPlan> generation) {
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
            projected.add(project(line, dictionary, snapshot.specialNodes(), generationContext));
        }
        Inventory receiving = inventory(cloud.receiving(), dictionary);
        Inventory transfer = inventory(cloud.transfer(), dictionary);
        Inventory sending = inventory(cloud.sending(), dictionary);
        ResourceRules resourceRules = CloudworksNodeResourceRules.prepare(lines, symbols, generation);
        View view = new View(cloud.owner(), cloud.enabled(), cloud.offlineProcessingUnlocked(),
                cloud.energy(), cloud.maxEnergy(),
                cloud.unlimitedEnergy(), Math.min(cloud.workRate(),
                    CloudworksConfig.settings().maxWorkRate),
                Math.min(cloud.outputMultiplierUnits(),
                    CloudworksConfig.settings().maxOutputMultiplierUnits),
                cloud.receivingCursor(), cloud.transferCursor(),
                cloud.receivingEnergy(), cloud.sendingEnergy(), cloud.receivingEnergyCapacity(),
                 cloud.sendingEnergyCapacity(), cloud.effectiveWarehouseItemCap(),
                 cloud.energyFlowReceivingToCloud(),
                cloud.energyFlowReceivingToLines(), cloud.energyFlowCloudToSending(),
                cloud.energyFlowLinesToSending(),
                receiving, transfer, sending, cloud.lineIds(), projected,
                symbols.generation(), symbols.descriptors(), symbols.codecs(), symbols.families(),
                resourceRules, generationContext);
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

    private static Line project(ProductionLineData source, Dictionary dictionary,
            CloudworksSpecialNodeStore.Snapshot specialNodes,
            CloudworksGenerationContext generationContext) {
        List<Node> nodes = new ArrayList<>();
        CloudworksUpgradeEffects.Context upgradeContext = CloudworksUpgradeEffects.context(source);
        List<CloudworksAreaGeometry.Rect> heatSources = CloudworksNodeEnvironment.sources(source);
        // B5: nodes frozen by a missing mod never enter the runtime — no capacity,
        // no ticks, no links; their persisted data stays untouched for recovery.
        Set<UUID> frozenNodes = new HashSet<>();
        for (MachineNodeData node : source.machines())
            if (node.frozenByMissingMod()) frozenNodes.add(node.nodeId());
        CloudworksModuleContexts moduleContexts = CloudworksModuleContexts.capture(source);
        for (MachineNodeData node : source.machines()) {
            if (node.frozenByMissingMod()) continue;
            MachineNodeData.MachineCapabilities capabilities = node.capabilities();
            MachineExecutionProfile profile = capabilities.profile();
            CloudworksUpgradeEffects upgrades = upgradeContext.forNode(node);
            CloudworksGenerationContext nodeEnvironment =
                    CloudworksNodeEnvironment.generation(source, node, generationContext);
            MachineContextSnapshot machineContext =
                    moduleContexts.forMachine(node, nodeEnvironment);
            List<CloudworksModuleBinding> machineEnvironmentModules =
                    new ArrayList<>(CloudworksModuleBinding.environmentSources(
                            source, node, nodeEnvironment, machineContext));
            machineEnvironmentModules.addAll(CloudworksModuleBinding.machineEnvironmentBehavior(
                    node, nodeEnvironment, machineContext));
            CloudworksOperation operation = node.operation();
            MachineSlotLayout layout = CloudworksNodeMechanisms.executionSlots(node);
            List<Slot> slots = new ArrayList<>();
            if (layout != null) for (MachineSlotLayout.Slot slot : layout.slots())
                slots.add(new Slot(slot.id(), slot.codecId().toString(), slot.role().name(),
                        slot.capacity(), slot.multipleConnections(), slot.recipeFilteredInput()));
            Operation running = projectOperation(operation, dictionary);
            String machineKey = capabilities.keyString();
            Map<ResourceLocation, Long> customUpgrades = new LinkedHashMap<>();
            for (ResourceLocation id : node.customUpgrades().ids())
                customUpgrades.put(id, node.customUpgrades().count(id));
            int canvasHeight = CloudworksAreaGeometry.nodeHeight(
                    capabilities.slots() == null ? 0 : capabilities.slots().slots().size());
            Node projected = new Node(node.nodeId(), machineKey, node.machine(), node.x(), node.z(),
                    node.machineCount(), node.active(),
                    node.progress(), node.totalTicks(), node.state(),
                    profile == null ? 0 : profile.threads(),
                    profile == null ? 0D : upgrades.energyPerTick(profile.energyPerTick()),
                    profile == null ? 0D : upgrades.capacity(profile.energyCapacity()),
                    profile == null ? 1 : profile.totalTicks(),
                    capabilities.executable(), upgrades.timeMultiplier(), upgrades.energyPerTick(1D),
                    upgrades.secondaryChanceMultiplier(), upgrades.configured(),
                    slots, stacks(node.inputs(), dictionary), stacks(node.outputs(), dictionary),
                    stacks(node.templates(), dictionary), running, capabilities.thermal(), node.thermalState(),
                    node.outputSequence(), node.nodeId(), "", machineKey, customUpgrades,
                    remainders(node.generationRemainders(), dictionary),
                    CloudworksNodeEnvironment.heat(node, capabilities.thermal(), heatSources, canvasHeight),
                      nodeEnvironment, machineContext,
                      machineEnvironmentModules,
                      node.moduleCostRemainders(), processingBatches(node, "", dictionary), node.processCredits(),
                      mekanism.cloudworks.common.integration.CloudworksGasUpgrade.multiplier(node, upgrades.timeMultiplier()),
                      !mekanism.cloudworks.common.registry.CloudworksMachineAdapters.getAll(node.machine()).stream()
                              .anyMatch(mekanism.cloudworks.api.machine.CloudworksMachineAdapter::requiresEnvironmentNode)
                              || nodeEnvironment.hasEnvironment(CloudworksGenerationContext.ENVIRONMENT_NODE_COVERAGE));
            nodes.add(projected);
            for (CloudworksNodeMechanisms.Branch branch : capabilities.branches()) {
                CloudworksExecutionState state = node.executionBranches().get(branch.id);
                CloudworksOperation promise = state == null ? null : state.operation;
                Operation branchOperation = projectOperation(promise, dictionary);
                MachineExecutionProfile branchProfile = branch.profile;
                nodes.add(new Node(CloudworksNodeMechanisms.branchId(node.nodeId(), branch.id),
                        machineKey + "|branch=" + branch.id, node.machine(), node.x(), node.z(),
                        node.machineCount(), node.active(),
                        state == null ? 0 : state.progress, state == null ? 1 : state.duration,
                        state == null ? "OFF" : state.state, branchProfile.threads(),
                        upgrades.energyPerTick(branchProfile.energyPerTick()), upgrades.capacity(branchProfile.energyCapacity()),
                        branchProfile.totalTicks(), true, upgrades.timeMultiplier(), upgrades.energyPerTick(1D),
                        upgrades.secondaryChanceMultiplier(), upgrades.configured(),
                        slots, projected.inputs, projected.outputs, projected.templates, branchOperation, branch.thermal,
                        state == null ? null : state.thermal, state == null ? 0 : state.outputSequence,
                        node.nodeId(), branch.id, machineKey, customUpgrades,
                        state == null ? Collections.emptyMap() : remainders(state.generationRemainders, dictionary),
                        CloudworksNodeEnvironment.heat(node, branch.thermal, heatSources, canvasHeight),
                        nodeEnvironment, machineContext, projected.environmentModules, projected.moduleCostRemainders,
                        processingBatches(node, branch.id, dictionary), node.processCredits(), projected.secondaryInputMultiplier, projected.environmentReady));
            }
        }
        List<Channel> channels = new ArrayList<>();
        for (CloudworksChannelNode channel : source.channels()) {
            Map<Integer, Long> manifest = new LinkedHashMap<>();
            boolean enabled = channel.enabled();
            if (CloudworksNodeEnvironment.source(channel.archetypeId())) {
                enabled = false; // Area effects are already frozen into thermal owners; no resource channel tick.
            } else if (channel.special()) {
                enabled &= channel.resources().size() == 1;
                for (QIOResourceDescriptor resource : channel.resources().keySet()) {
                    CloudworksSpecialNodeStore.Entry entry =
                            specialNodes.resources.get(resource);
                    if (entry == null) enabled = false;
                    else manifest.put(dictionary.id(resource), entry.rules.outputAmount);
                }
            } else channel.resources().forEach((resource, amount) ->
                    manifest.merge(dictionary.id(resource), amount, Math::addExact));
            // A virtual source's cache is replenishable, not external inventory.
            Inventory buffer = channel.special()
                    ? new Inventory(channel.buffer().capacity(), Collections.emptyMap())
                    : inventory(channel.buffer(), dictionary);
            channels.add(new Channel(channel.id(), channel.mode().name(), channel.archetypeId(), enabled,
                    manifest, buffer));
        }
        List<Auxiliary> auxiliaries = new ArrayList<>();
        for (CloudworksAuxiliaryNodeData auxiliary : source.auxiliaries()) {
            CloudworksAuxiliaryNodeType type = CloudworksAuxiliaryNodeRegistry.byItem(auxiliary.type());
            if (type == null) continue;
            List<Node> candidates = new ArrayList<>();
            for (Node node : nodes) {
                MachineNodeData targetData = null;
                for (MachineNodeData candidate : source.machines())
                    if (candidate.nodeId().equals(node.physicalNode)) { targetData = candidate; break; }
                if (!node.branchId.isEmpty() || targetData == null || !type.accepts(targetData.machine()))
                    continue;
                if (type.targeting() == CloudworksAuxiliaryNodeType.Targeting.LINKED
                        && !auxiliary.linkedTargets().contains(node.physicalNode)) continue;
                if (type.targeting() == CloudworksAuxiliaryNodeType.Targeting.AREA) {
                    int radius = auxiliary.range() > 0 ? auxiliary.range() : type.range();
                    ModuleArea area = type.module().area();
                    CloudworksAreaGeometry.Rect bounds = area == null
                            ? CloudworksAreaGeometry.square(auxiliary.x() + CloudworksAreaGeometry.NODE_WIDTH / 2D,
                                    auxiliary.z() + CloudworksAreaGeometry.nodeHeight(type.ports().size()) / 2D, radius * 2D)
                            : area.bounds(auxiliary.x(), auxiliary.z(), CloudworksAreaGeometry.nodeHeight(type.ports().size()), radius);
                    if (!CloudworksAreaGeometry.exceeds(bounds, new CloudworksAreaGeometry.Rect(node.x, node.z,
                                    node.x + CloudworksAreaGeometry.NODE_WIDTH, node.z + CloudworksAreaGeometry.nodeHeight(node.slots.size())),
                                    area == null ? .5D : area.threshold())) continue;
                }
                candidates.add(node);
            }
            candidates.sort(Comparator.comparing(node -> node.id));
            if (type.maxTargets() > 0 && candidates.size() > type.maxTargets())
                candidates = new ArrayList<>(candidates.subList(0, type.maxTargets()));
            Map<UUID, CloudworksModuleBinding> effects = new LinkedHashMap<>();
            if (!auxiliary.active()) candidates.clear();
            for (Node node : candidates) {
                double targetEnergy = node.energyPerTick;
                QIOResourceDescriptor costResource = auxiliary.input().snapshot().keySet().stream()
                        .filter(resource -> QIOResourceCodecs.FLUID_STACK_ID.equals(resource.getCodecId()))
                        .findFirst().orElse(null);
                    effects.put(node.id, new CloudworksModuleBinding(new MachineModuleHandle(type.module(),
                        1, 1, MachineModuleSource.AUXILIARY_NODE, auxiliary.nodeId(), node.physicalNode,
                        node.machineStack, node.machineCount, "", node.customUpgrades, node.environment)
                        .withMachineContext(node.machineContext),
                        costResource, targetEnergy));
            }
            auxiliaries.add(new Auxiliary(auxiliary.nodeId(), type.id().toString(), auxiliary.x(), auxiliary.z(),
                    auxiliary.active(), inventory(auxiliary.input(), dictionary),
                    effects, auxiliary.costRemainders()));
        }
        List<Link> links = new ArrayList<>();
        for (ProductionLineLink link : source.links()) {
            if (frozenNodes.contains(link.sourceNode()) || frozenNodes.contains(link.targetNode()))
                continue; // B5: persisted but never compiled; restored with the node
            List<Integer> filters = new ArrayList<>();
            for (QIOResourceDescriptor filter : link.resourceFilters()) filters.add(dictionary.id(filter));
            links.add(new Link(link.linkId(), link.sourceNode(), link.sourcePort(), link.targetNode(),
                    link.targetPort(), link.enabled(), link.priority(), link.ratio(),
                    link.ratioRemainder(), filters));
        }
        return new Line(source.lineId(), source.runWhileOwnerOffline(), source.energy(),
                source.maxEnergy(),
                source.speedUpgrades(), source.energyUpgrades(),
                source.maxSpeedUpgrades(), source.maxEnergyUpgrades(), source.nodeBufferMultiplier(),
                source.energyFlowReceivingToLine(), source.energyFlowLineToSending(),
                nodes, channels, auxiliaries, links);
    }

    private static Operation projectOperation(CloudworksOperation operation, Dictionary dictionary) {
        return operation == null ? null : new Operation(operation.id(), operation.operations(), operation.duration(),
                stacks(operation.inputs(), dictionary), stacks(operation.outputs(), dictionary), stacks(operation.chanceOutputs(), dictionary),
                operation.extraEnergyPerTick(), operation.completionPrepared(), operation.recipeId(), operation.modulePower(),
                operation.lifecycleId(), operation.parallelUnits(), operation.outputSequence(), operation.requiredEnvironmentAxis());
    }

    private static List<ProcessingBatch> processingBatches(MachineNodeData node, String branch, Dictionary dictionary) {
        List<ProcessingBatch> result = new ArrayList<>();
        String prefix = CloudworksProcessingBatches.prefix(branch);
        node.processingBatches().forEach((key, value) -> {
            if (key.startsWith(prefix)) result.add(new ProcessingBatch(value.progress, value.state,
                    value.outputSequence, projectOperation(value.operation, dictionary)));
        });
        result.sort(Comparator.comparingLong(value -> value.sequence));
        return Collections.unmodifiableList(result);
    }

    private static Map<Integer, Double> remainders(Map<QIOResourceDescriptor, Double> values, Dictionary dictionary) {
        Map<Integer, Double> result = new LinkedHashMap<>();
        values.forEach((resource, amount) -> result.put(dictionary.id(resource), amount));
        return immutable(result);
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
        public final long warehouseItemTypeCap;
        public final boolean energyFlowReceivingToCloud, energyFlowReceivingToLines;
        public final boolean energyFlowCloudToSending, energyFlowLinesToSending;
        public final int workRate, outputMultiplierUnits, receivingCursor, transferCursor;
        public final Inventory receiving, transfer, sending;
        public final List<UUID> allLineIds;
        /**
         * Dictionary generation of the session symbol table at capture time.
         */
        public final long symbolGeneration;
        public final List<QIOResourceDescriptor> resourceDescriptors;
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
                     boolean unlimitedEnergy, int workRate, int outputMultiplierUnits,
                     int receivingCursor, int transferCursor,
                     double receivingEnergy, double sendingEnergy, double receivingCapacity, double sendingCapacity,
                     long warehouseItemTypeCap,
                     boolean energyFlowReceivingToCloud, boolean energyFlowReceivingToLines,
                     boolean energyFlowCloudToSending, boolean energyFlowLinesToSending,
                     Inventory receiving, Inventory transfer, Inventory sending,
                     List<UUID> allLineIds, List<Line> lines, long symbolGeneration,
                     List<QIOResourceDescriptor> resourceDescriptors, List<String> resourceCodecs,
                     List<String> resourceFamilies, ResourceRules resourceRules,
                     CloudworksGenerationContext generationContext) {
            this.owner = owner;
            this.enabled = enabled;
            this.offlineProcessingUnlocked = offlineProcessingUnlocked;
            this.energy = energy;
            this.maxEnergy = maxEnergy;
            this.unlimitedEnergy = unlimitedEnergy;
            this.workRate = workRate;
            this.outputMultiplierUnits = outputMultiplierUnits;
            this.receivingCursor = receivingCursor;
            this.transferCursor = transferCursor;
            this.receivingEnergy = receivingEnergy;
            this.sendingEnergy = sendingEnergy;
            this.receivingCapacity = receivingCapacity;
            this.sendingCapacity = sendingCapacity;
            this.warehouseItemTypeCap = warehouseItemTypeCap;
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
            this.resourceDescriptors = Collections.unmodifiableList(new ArrayList<>(resourceDescriptors));
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
            Set<String> ports = outputPorts.getOrDefault(node.rulesKey, Collections.emptyMap()).get(resource);
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
        public final int nodeBufferMultiplier;
        public final boolean energyFlowReceivingToLine, energyFlowLineToSending;
        public final List<Node> nodes;
        public final List<Channel> channels;
        public final List<Auxiliary> auxiliaries;
        public final List<Link> links;

        private Line(UUID id, boolean runWhileOwnerOffline, double energy, double maxEnergy,
                     int speedUpgrades, int energyUpgrades,
                     int maxSpeedUpgrades, int maxEnergyUpgrades, int nodeBufferMultiplier,
                     boolean energyFlowReceivingToLine, boolean energyFlowLineToSending,
                     List<Node> nodes, List<Channel> channels, List<Auxiliary> auxiliaries,
                     List<Link> links) {
            this.id = id;
            this.runWhileOwnerOffline = runWhileOwnerOffline;
            this.energy = energy;
            this.maxEnergy = maxEnergy;
            this.speedUpgrades = speedUpgrades;
            this.energyUpgrades = energyUpgrades;
            this.maxSpeedUpgrades = maxSpeedUpgrades;
            this.maxEnergyUpgrades = maxEnergyUpgrades;
            this.nodeBufferMultiplier = nodeBufferMultiplier;
            this.energyFlowReceivingToLine = energyFlowReceivingToLine;
            this.energyFlowLineToSending = energyFlowLineToSending;
            this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
            this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
            this.auxiliaries = Collections.unmodifiableList(new ArrayList<>(auxiliaries));
            this.links = Collections.unmodifiableList(new ArrayList<>(links));
        }
    }

    public static final class Node {
        public final UUID id;
        public final UUID physicalNode;
        public final String branchId, rulesKey;
        public final double secondaryInputMultiplier;
        public final boolean environmentReady;
        public final String machineKey;
        public final long machineCount;
        public final int progress, totalTicks, threads, baseTicks;
        public final boolean active, executable;
        public final String state;
        public final double energyPerTick, energyCapacity;
        public final double timeMultiplier, energyMultiplier;
        public final double secondaryChanceMultiplier;
        public final boolean configuredUpgrades;
        public final List<Slot> slots;
        public final List<Stack> inputs, outputs, templates;
        public final Operation operation;
        public final CloudworksThermalPlan thermal;
        public final CloudworksThermalState thermalState;
        public final double environmentHeat;
        public final long outputSequence;
        public final Map<ResourceLocation, Long> customUpgrades;
        public final Map<Integer, Double> generationRemainders;
        public final CloudworksGenerationContext environment;
        public final MachineContextSnapshot machineContext;
        public final int x, z;
        public final ItemStack machineStack;
        public final List<CloudworksModuleBinding> environmentModules;
        public final Map<String, Double> moduleCostRemainders;
        public final List<ProcessingBatch> processingBatches;
        public final Map<String, mekanism.cloudworks.common.data.CloudworksResourceCredit> processCredits;

        private Node(UUID id, String machineKey, ItemStack machineStack, int x, int z,
                     long machineCount, boolean active, int progress, int totalTicks,
                     String state, int threads, double energyPerTick,
                     double energyCapacity, int baseTicks, boolean executable, double timeMultiplier,
                     double energyMultiplier, double secondaryChanceMultiplier, boolean configuredUpgrades, List<Slot> slots,
                     List<Stack> inputs, List<Stack> outputs, List<Stack> templates, Operation operation,
                     CloudworksThermalPlan thermal, CloudworksThermalState thermalState,
                      long outputSequence, UUID physicalNode, String branchId, String rulesKey,
                      Map<ResourceLocation, Long> customUpgrades,
                     Map<Integer, Double> generationRemainders, double environmentHeat,
                      CloudworksGenerationContext environment, MachineContextSnapshot machineContext,
                      List<CloudworksModuleBinding> environmentModules,
                      Map<String, Double> moduleCostRemainders, List<ProcessingBatch> processingBatches,
                      Map<String, mekanism.cloudworks.common.data.CloudworksResourceCredit> processCredits, double secondaryInputMultiplier,
                      boolean environmentReady) {
            this.id = id;
            this.machineKey = machineKey;
            this.machineStack = machineStack.copy();
            this.machineStack.setCount(1);
            this.x = x;
            this.z = z;
            this.machineCount = machineCount;
            this.active = active;
            this.progress = progress;
            this.totalTicks = totalTicks;
            this.state = state;
            this.threads = threads;
            this.energyPerTick = energyPerTick;
            this.energyCapacity = energyCapacity;
            this.baseTicks = baseTicks;
            this.executable = executable;
            this.timeMultiplier = timeMultiplier;
            this.energyMultiplier = energyMultiplier;
            this.secondaryChanceMultiplier = secondaryChanceMultiplier;
            this.configuredUpgrades = configuredUpgrades;
            this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
            this.inputs = inputs;
            this.outputs = outputs;
            this.templates = templates;
            this.operation = operation;
            this.thermal = thermal;
            this.thermalState = thermalState;
            this.environmentHeat = environmentHeat;
            this.outputSequence = outputSequence;
            this.physicalNode = physicalNode; this.branchId = branchId; this.rulesKey = rulesKey;
            this.customUpgrades = Collections.unmodifiableMap(new LinkedHashMap<>(customUpgrades));
            this.generationRemainders = generationRemainders;
            this.environment = environment;
            this.machineContext = Objects.requireNonNull(machineContext);
            this.environmentModules = Collections.unmodifiableList(new ArrayList<>(environmentModules));
            this.moduleCostRemainders = Collections.unmodifiableMap(new LinkedHashMap<>(moduleCostRemainders));
            this.processingBatches = processingBatches;
            this.processCredits = Collections.unmodifiableMap(new LinkedHashMap<>(processCredits));
            this.secondaryInputMultiplier = secondaryInputMultiplier;
            this.environmentReady = environmentReady;
        }
    }

    public static final class Auxiliary {
        public final UUID id;
        public final String typeId;
        public final int x, z;
        public final boolean active;
        public final Inventory input;
        public final Map<UUID, CloudworksModuleBinding> effects;
        public final Map<String, Double> costRemainders;

        private Auxiliary(UUID id, String typeId, int x, int z, boolean active,
                Inventory input, Map<UUID, CloudworksModuleBinding> effects, Map<String, Double> costRemainders) {
            this.id = id;
            this.typeId = typeId;
            this.x = x;
            this.z = z;
            this.active = active;
            this.input = input;
            this.effects = Collections.unmodifiableMap(new LinkedHashMap<>(effects));
            this.costRemainders = Collections.unmodifiableMap(new LinkedHashMap<>(costRemainders));
        }
    }

    public static final class Slot {
        public final String id, codec, role;
        public final int capacity;
        public final boolean multipleConnections, recipeFilteredInput;

        private Slot(String id, String codec, String role, int capacity, boolean multipleConnections,
                boolean recipeFilteredInput) {
            this.id = id;
            this.codec = codec;
            this.role = role;
            this.capacity = capacity;
            this.multipleConnections = multipleConnections;
            this.recipeFilteredInput = recipeFilteredInput;
        }
    }

    public static final class ProcessingBatch {
        public final int progress;
        public final String state;
        public final long sequence;
        public final Operation operation;
        private ProcessingBatch(int progress, String state, long sequence, Operation operation) {
            this.progress = progress; this.state = state; this.sequence = sequence; this.operation = operation;
        }
    }

    public static final class Operation {
        public final UUID id;
        public final int operations, duration;
        public final double extraEnergyPerTick;
        public final boolean completionPrepared;
        public final String recipeId;
        public final double modulePower;
        public final long lifecycleId;
        public final int parallelUnits;
        public final long outputSequence;
        public final ResourceLocation requiredEnvironmentAxis;
        public final List<Stack> inputs, outputs, chanceOutputs;

        private Operation(UUID id, int operations, int duration, List<Stack> inputs, List<Stack> outputs,
                List<Stack> chanceOutputs,
                double extraEnergyPerTick, boolean completionPrepared, String recipeId, double modulePower, long lifecycleId,
                int parallelUnits, long outputSequence, ResourceLocation requiredEnvironmentAxis) {
            this.id = id;
            this.operations = operations;
            this.duration = duration;
            this.inputs = inputs;
            this.outputs = outputs;
            this.chanceOutputs = chanceOutputs;
            this.extraEnergyPerTick = extraEnergyPerTick;
            this.completionPrepared = completionPrepared;
            this.recipeId = recipeId == null ? "" : recipeId;
            this.modulePower = modulePower;
            this.lifecycleId = lifecycleId;
            this.parallelUnits = parallelUnits;
            this.outputSequence = outputSequence;
            this.requiredEnvironmentAxis = requiredEnvironmentAxis;
        }
    }

    public static final class Channel {
        public final UUID id;
        public final String mode;
        public final String archetypeId;
        public final boolean enabled;
        public final Map<Integer, Long> manifest;
        public final Inventory buffer;

        private Channel(UUID id, String mode, String archetypeId, boolean enabled,
                Map<Integer, Long> manifest, Inventory buffer) {
            this.id = id;
            this.mode = mode;
            this.archetypeId = archetypeId == null ? "" : archetypeId;
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
