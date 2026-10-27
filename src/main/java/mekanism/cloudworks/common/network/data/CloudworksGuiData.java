package mekanism.cloudworks.common.network.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import java.util.UUID;

import mekanism.cloudworks.api.recipe.RecipeContribution;
import mekanism.common.content.qio.QIOResourceEntry;
import net.minecraft.item.ItemStack;
import java.util.Objects;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.common.card.CloudworksCardEffects;
import mekanism.cloudworks.common.data.CloudworksThermalState;
import mekanism.cloudworks.common.network.data.node.Node;
import mekanism.cloudworks.common.service.CloudworksNodeEnvironment;
import mekanism.cloudworks.common.service.CloudworksOutputMultiplier;

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

    /**
     * Wire view of one custom upgrade pool entry: only identity and the pool
     * count cross the network (the count is the sole server-side state).
     * Display key, item, description keys and mechanism metadata are client
     * facts resolved from the frozen local registries; unknown ids keep their
     * counts and render as a bare shell.
     */
    public static final class CustomUpgrade {
        public final String id;
        public final long count;

        public CustomUpgrade(String id, long count) {
            this.id = id;
            this.count = count;
        }
    }

    /** Server-derived view of one generic cloud-effect level (8.6). */
    public static final class EffectLevel {
        public final String id;
        public final long level;

        public EffectLevel(String id, long level) {
            this.id = id;
            this.level = level;
        }
    }

    public static final class Overview {
        public static final Overview EMPTY = new Overview(0, 0, 0, 0, 1, 0,
                0, 0, false, 0, 0, false, false, 0, 0, 1, Collections.emptyList());
        public final int offset, total, unlocked, maximum, workRate;
        public final int outputMultiplierUnits;
        public final long revision;
        public final double energy, maxEnergy, receivingEnergy, sendingEnergy, receivingCapacity, sendingCapacity;
        public final boolean unlimited, receivingToCloud, cloudToSending;
        public final int speedUpgradePool, energyUpgradePool, clockTick;
        public final int maxMachineNodes;
        public int netherHeatSourcesUsed;
        public final int machineNodeLimit;
        public final long maxMachinesPerNode;
        public final boolean offlineUnlocked, offlineProcessingEnabled;
        public final boolean recycleToTransfer;
        public final List<SpecialResource> infiniteResources;
        public final List<Line> lines;
        public final Team team;
        public final List<CustomUpgrade> customUpgrades;
        public final List<EffectLevel> effectLevels;

        public Overview(int offset, int total, int unlocked, int maximum, int workRate, long revision,
                double energy, double maxEnergy, boolean unlimited, double receivingEnergy,
                double sendingEnergy, boolean receivingToCloud, boolean cloudToSending,
                int speedUpgradePool, int energyUpgradePool, int clockTick, List<Line> lines) {
            this(offset, total, unlocked, maximum, workRate, revision, energy, maxEnergy, unlimited,
                    receivingEnergy, sendingEnergy, receivingToCloud, cloudToSending, speedUpgradePool,
                    energyUpgradePool, clockTick, lines, 64, false, unlimited ? Double.MAX_VALUE : maxEnergy,
                    unlimited ? Double.MAX_VALUE : maxEnergy, true, 4096, 1024, true, Collections.emptyList(),
                    CloudworksOutputMultiplier.SCALE,
                    Team.EMPTY, Collections.emptyList(), Collections.emptyList());
        }

        public Overview(int offset, int total, int unlocked, int maximum, int workRate, long revision,
                double energy, double maxEnergy, boolean unlimited, double receivingEnergy,
                double sendingEnergy, boolean receivingToCloud, boolean cloudToSending,
                int speedUpgradePool, int energyUpgradePool, int clockTick, List<Line> lines,
                int maxMachineNodes, boolean offlineUnlocked, double receivingCapacity,
                double sendingCapacity, boolean offlineProcessingEnabled, int machineNodeLimit,
                long maxMachinesPerNode, boolean recycleToTransfer,
                List<SpecialResource> infiniteResources, int outputMultiplierUnits, Team team,
                List<CustomUpgrade> customUpgrades, List<EffectLevel> effectLevels) {
            this.offset = offset;
            this.total = total;
            this.unlocked = unlocked;
            this.maximum = maximum;
            this.workRate = workRate;
            this.outputMultiplierUnits = CloudworksOutputMultiplier.validate(outputMultiplierUnits);
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
            this.maxMachineNodes = maxMachineNodes;
            this.offlineUnlocked = offlineUnlocked;
            this.receivingCapacity = receivingCapacity;
            this.sendingCapacity = sendingCapacity;
            this.offlineProcessingEnabled = offlineProcessingEnabled;
            this.machineNodeLimit = machineNodeLimit;
            this.maxMachinesPerNode = maxMachinesPerNode;
            this.recycleToTransfer = recycleToTransfer;
            this.lines = Lists.immutable(lines);
            this.infiniteResources = Lists.immutable(infiniteResources);
            this.team = team == null ? Team.EMPTY : team;
            // null = on-demand catalogue not requested (upgrade window closed); distinct from empty.
            this.customUpgrades = customUpgrades == null ? null : Lists.immutable(customUpgrades);
            this.effectLevels = effectLevels == null ? Collections.emptyList() : Lists.immutable(effectLevels);
        }

        public Overview withTeam(Team value) {
            return new Overview(offset, total, unlocked, maximum, workRate, revision, energy, maxEnergy, unlimited,
                    receivingEnergy, sendingEnergy, receivingToCloud, cloudToSending, speedUpgradePool,
                    energyUpgradePool, clockTick, lines, maxMachineNodes,
                    offlineUnlocked, receivingCapacity, sendingCapacity, offlineProcessingEnabled,
                    machineNodeLimit, maxMachinesPerNode, recycleToTransfer, infiniteResources,
                    outputMultiplierUnits, value, customUpgrades, effectLevels).withNetherSourceUsage(netherHeatSourcesUsed);
        }

        /** Server-side projection of the cloud custom pool joined with the frozen registry (8.5-B2). */
        public Overview withCustomUpgrades(List<CustomUpgrade> entries) {
            return new Overview(offset, total, unlocked, maximum, workRate, revision, energy, maxEnergy, unlimited,
                    receivingEnergy, sendingEnergy, receivingToCloud, cloudToSending, speedUpgradePool,
                    energyUpgradePool, clockTick, lines, maxMachineNodes,
                    offlineUnlocked, receivingCapacity, sendingCapacity, offlineProcessingEnabled,
                    machineNodeLimit, maxMachinesPerNode, recycleToTransfer, infiniteResources,
                    outputMultiplierUnits, team, entries, effectLevels).withNetherSourceUsage(netherHeatSourcesUsed);
        }

        /** Server-side projection of the generic cloud-effect levels (8.6). */
        public Overview withEffectLevels(List<EffectLevel> entries) {
            return new Overview(offset, total, unlocked, maximum, workRate, revision, energy, maxEnergy, unlimited,
                    receivingEnergy, sendingEnergy, receivingToCloud, cloudToSending, speedUpgradePool,
                    energyUpgradePool, clockTick, lines, maxMachineNodes,
                    offlineUnlocked, receivingCapacity, sendingCapacity, offlineProcessingEnabled,
                    machineNodeLimit, maxMachinesPerNode, recycleToTransfer, infiniteResources,
                    outputMultiplierUnits, team, customUpgrades, entries).withNetherSourceUsage(netherHeatSourcesUsed);
        }

        public Overview withNetherSourceUsage(int usedOutsideLine) {
            if (usedOutsideLine < 0) throw new IllegalArgumentException("Invalid nether source usage");
            netherHeatSourcesUsed = usedOutsideLine;
            return this;
        }

        public long netherHeatSourceLimit() {
            for (EffectLevel effect : effectLevels)
                if (CloudworksCardEffects.NETHER.id().toString().equals(effect.id))
                    return effect.level;
            return 0;
        }
    }

    public static final class Team {
        public static final Team EMPTY = new Team(null, "", null, Collections.emptyList());
        public final UUID id;
        public final String name;
        public final UUID owner;
        public final List<Member> members;
        public final String ftbBinding;
        public final boolean ftbAvailable;
        public final String joinMode;
        public final int color;
        public final List<TeamRequest> requests;

        public Team(UUID id, String name, UUID owner, List<Member> members) {
            this(id, name, owner, members, "", false, "APPROVAL", 0xFF4F7FFF,
                    Collections.emptyList());
        }

        public Team(UUID id, String name, UUID owner, List<Member> members, String ftbBinding,
                boolean ftbAvailable) {
            this(id, name, owner, members, ftbBinding, ftbAvailable, "APPROVAL", 0xFF4F7FFF,
                    Collections.emptyList());
        }

        public Team(UUID id, String name, UUID owner, List<Member> members, String ftbBinding,
                boolean ftbAvailable, String joinMode, int color, List<TeamRequest> requests) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.owner = owner;
            this.members = Lists.immutable(members);
            this.ftbBinding = ftbBinding;
            this.ftbAvailable = ftbAvailable;
            this.joinMode = joinMode == null ? "APPROVAL" : joinMode;
            this.color = color;
            this.requests = Lists.immutable(requests);
        }
    }

    public static final class TeamRequest {
        public final UUID id;
        public final String name;
        public TeamRequest(UUID id, String name) {
            this.id = Objects.requireNonNull(id);
            this.name = name;
        }
    }

    public static final class Member {
        public final UUID id;
        public final String name;
        public final boolean online, allowed;

        public Member(UUID id, String name, boolean online) {
            this(id, name, online, true);
        }

        public Member(UUID id, String name, boolean online, boolean allowed) {
            this.id = Objects.requireNonNull(id);
            this.name = name == null || name.isEmpty() ? id.toString() : name;
            this.online = online;
            this.allowed = allowed;
        }
    }

    public static final class SpecialResource {
        public final QIOResourceDescriptor descriptor;
        public final boolean unlimitedPlacement;
        public final long nodeLimit, usedOutsideLine, outputAmount;

        public SpecialResource(QIOResourceDescriptor descriptor, boolean unlimitedPlacement,
                long nodeLimit, long usedOutsideLine, long outputAmount) {
            this.descriptor = Objects.requireNonNull(descriptor);
            if (!descriptor.isResolved() || nodeLimit <= 0 || usedOutsideLine < 0 || outputAmount <= 0)
                throw new IllegalArgumentException("Invalid special resource permission");
            this.unlimitedPlacement = unlimitedPlacement;
            this.nodeLimit = nodeLimit;
            this.usedOutsideLine = usedOutsideLine;
            this.outputAmount = outputAmount;
        }
    }

    public static final class Line {
        public final UUID id;
        public final String name;
        public final boolean available, enabled, receivingFlow, sendingFlow, runWhileOwnerOffline;
        public final int nodeCount;
        public final double energy, maxEnergy;
        public final ItemStack icon;
        public final List<QIOResourceDescriptor> markers = new ArrayList<>();

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
        /** Line-level custom upgrade pool joined with the frozen registry (8.5-B5). */
        public List<CustomUpgrade> customUpgrades = Collections.emptyList();

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



    public static final class ExecutionBranch {
        public final String id, state;
        public final int progress, duration;
        public ExecutionBranch(String id, String state, int progress, int duration) {
            if (id == null || id.length() > 128 || state == null || state.length() > 128
                    || progress < 0 || duration < 1 || progress >= duration)
                throw new IllegalArgumentException("Invalid execution branch projection");
            this.id = id; this.state = state; this.progress = progress; this.duration = duration;
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
        public final QIOResourceDescriptor descriptor;

        public ResourceSummary(String name, long amount, QIOResourceDescriptor descriptor) {
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
        public final String categoryId;

        public Machine(ItemStack stack, long count, long claimed, long available, List<Claim> claims) {
            this(stack, count, claimed, available, claims, "cloudworks:other");
        }

        /** Category translation keys are client facts resolved via CloudworksRegistries.category(stack). */
        public Machine(ItemStack stack, long count, long claimed, long available, List<Claim> claims,
                String categoryId) {
            this.stack = stack.copy();
            this.count = count;
            this.claimed = claimed;
            this.available = available;
            this.claims = Lists.immutable(claims);
            this.categoryId = categoryId == null || categoryId.isEmpty()
                    ? "cloudworks:other" : categoryId;
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
        public final List<QIOResourceDescriptor> resources;
        public final UUID context;

        public Choices(int offset, int total, List<String> names,
                List<QIOResourceDescriptor> resources, UUID context) {
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
