package mekanism.cloudworks.common.data;

import mekanism.cloudworks.common.upgrade.CloudworksUpgradeLimits;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeEffects;
import mekanism.common.Upgrade;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import net.minecraft.nbt.NBTTagString;

public final class ProductionLineData {
    public static final int SCHEMA_VERSION = 1;
    private final UUID lineId;
    private final List<MachineNodeData> machines = new ArrayList<>();
    private final List<CloudworksAuxiliaryNodeData> auxiliaries = new ArrayList<>();
    private final List<ProductionLineLink> links = new ArrayList<>();
    private final List<CloudworksChannelNode> channels = new ArrayList<>();
    private final List<String> dynamicResourceDisplayList = new ArrayList<>();
    private UUID owner;
    private String name = "Production Line";
    private boolean enabled;
    private boolean runWhileOwnerOffline;
    private boolean explicitRouting;
    private double energy;
    private double maxEnergy;
    private int speedUpgrades;
    private int energyUpgrades;
    // Persisted limits preserve old over-cap counts until the editor returns them to the pool.
    private int maxSpeedUpgrades;
    private int maxEnergyUpgrades;
    /**
     * 产线级自定义升级池: 命名空间类型 → 数量; 未知集成保留。
     */
    private CloudworksUpgradeSet customUpgradePool = new CloudworksUpgradeSet();
    private int nodeBufferMultiplier = 1;
    /**
     * 产线级能量门: 收货中继直喂本线（协议储存箱能量四门的产线侧）。
     */
    private boolean energyFlowReceivingToLine = true;
    /**
     * 产线级能量门: 本线池直入发货中继。
     */
    private boolean energyFlowLineToSending = false;
    /**
     * 命名空间扩展 payload: 可选容器, 未知与超界条目按既定策略保留或隔离。
     */
    private CloudworksExtensionSet extensions = new CloudworksExtensionSet();

    public ProductionLineData(UUID lineId, UUID owner) {
        this.lineId = lineId;
        this.owner = owner;
    }

    /**
     * Copies cold configuration only, without rebuilding machines, capabilities or ItemStacks.
     */
    public ProductionLineData copyForRuntime() {
        ProductionLineData copy = new ProductionLineData(lineId, owner);
        copy.name = name;
        copy.enabled = enabled;
        copy.runWhileOwnerOffline = runWhileOwnerOffline;
        copy.explicitRouting = explicitRouting;
        copy.energy = energy;
        copy.maxEnergy = maxEnergy;
        copy.speedUpgrades = speedUpgrades;
        copy.energyUpgrades = energyUpgrades;
        copy.maxSpeedUpgrades = maxSpeedUpgrades;
        copy.maxEnergyUpgrades = maxEnergyUpgrades;
        copy.nodeBufferMultiplier = nodeBufferMultiplier;
        copy.energyFlowReceivingToLine = energyFlowReceivingToLine;
        copy.energyFlowLineToSending = energyFlowLineToSending;
        copy.dynamicResourceDisplayList.addAll(dynamicResourceDisplayList);
        copy.extensions = extensions.copy();
        copy.customUpgradePool = customUpgradePool.copy();
        return copy;
    }

    public static ProductionLineData read(NBTTagCompound tag) {
        CloudworksDataChecks.fields(tag, 8, "lineId", "owner", "name");
        CloudworksDataChecks.fields(tag, 1, "enabled", "runWhileOwnerOffline", "explicitRouting", "energyFlowReceivingToLine", "energyFlowLineToSending");
        CloudworksDataChecks.fields(tag, 3, "speedUpgrades", "energyUpgrades", "maxSpeedUpgrades", "maxEnergyUpgrades");
        CloudworksDataChecks.fields(tag, 6, "energy", "maxEnergy");

        CloudworksDataChecks.schema(tag, SCHEMA_VERSION);
        ProductionLineData line = new ProductionLineData(UUID.fromString(tag.getString("lineId")),
                UUID.fromString(tag.getString("owner")));
        line.name = tag.getString("name");
        line.enabled = tag.getBoolean("enabled");
        line.runWhileOwnerOffline = tag.getBoolean("runWhileOwnerOffline");
        line.setEnergy(tag.getDouble("energy"), tag.getDouble("maxEnergy"));
        line.explicitRouting = tag.getBoolean("explicitRouting");
        int speed = tag.getInteger("speedUpgrades"), energy = tag.getInteger("energyUpgrades");
        int maxSpeed = tag.getInteger("maxSpeedUpgrades"), maxEnergy = tag.getInteger("maxEnergyUpgrades");
        if (speed < 0 || energy < 0 || maxSpeed < speed || maxEnergy < energy)
            throw new IllegalArgumentException("Invalid line upgrades");
        line.setMaxUpgradesRaw(maxSpeed, maxEnergy);
        line.setUpgradesRaw(speed, energy);
        line.nodeBufferMultiplier = tag.hasKey("nodeBufferMultiplier", NBT.TAG_INT)
                ? tag.getInteger("nodeBufferMultiplier") : 1;
        if (line.nodeBufferMultiplier < 1) throw new IllegalArgumentException("Invalid node buffer multiplier");
        line.setEnergyFlows(tag.getBoolean("energyFlowReceivingToLine"),
                tag.getBoolean("energyFlowLineToSending"));
        NBTTagList nodes = CloudworksDataChecks.list(tag, "machines", NBT.TAG_COMPOUND);
        Set<UUID> nodeIds = new HashSet<>();
        for (int i = 0; i < nodes.tagCount(); i++) {
            MachineNodeData node = MachineNodeData.read(nodes.getCompoundTagAt(i));
            // P1-12: a duplicate machine node id must REJECT the whole line — a
            // silently merged read would double-count machines, resources and
            // products in every Map-keyed consumer downstream.
            if (!nodeIds.add(node.nodeId()))
                throw new IllegalArgumentException("Duplicate machine node id: " + node.nodeId());
            line.machines.add(node);
        }
        // The custom pool must be loaded before budget validation runs.
        line.customUpgradePool = CloudworksUpgradeSet.read(tag, "customUpgradePool");
        CloudworksUpgradeLimits.validateBudget(line);
        NBTTagList edgeTags = CloudworksDataChecks.list(tag, "links", NBT.TAG_COMPOUND);
        Set<UUID> linkIds = new HashSet<>();
        for (int i = 0; i < edgeTags.tagCount(); i++) {
            ProductionLineLink link = ProductionLineLink.read(edgeTags.getCompoundTagAt(i));
            if (!linkIds.add(link.linkId())) throw new IllegalArgumentException("Duplicate link id");
            line.links.add(link);
        }
        NBTTagList channelTags = CloudworksDataChecks.list(tag, "channels", NBT.TAG_COMPOUND);
        for (int i = 0; i < channelTags.tagCount(); i++) {
            CloudworksChannelNode channel = CloudworksChannelNode.read(channelTags.getCompoundTagAt(i));
            if (!nodeIds.add(channel.id())) throw new IllegalArgumentException("Duplicate channel/node id");
            line.channels.add(channel);
        }
        if (tag.hasKey("auxiliaries", NBT.TAG_LIST)) {
            NBTTagList auxiliaryTags = CloudworksDataChecks.list(tag, "auxiliaries", NBT.TAG_COMPOUND);
            for (int i = 0; i < auxiliaryTags.tagCount(); i++) {
                CloudworksAuxiliaryNodeData auxiliary =
                        CloudworksAuxiliaryNodeData.read(auxiliaryTags.getCompoundTagAt(i));
                if (!nodeIds.add(auxiliary.nodeId()))
                    throw new IllegalArgumentException("Duplicate auxiliary node id");
                line.auxiliaries.add(auxiliary);
            }
        }
        NBTTagList display = CloudworksDataChecks.list(tag, "dynamicResourceDisplayList", NBT.TAG_STRING);
        for (int i = 0; i < display.tagCount(); i++) line.dynamicResourceDisplayList.add(display.getStringTagAt(i));
        line.extensions = CloudworksExtensionSet.read(tag);
        return line;
    }

    /**
     * Namespaced extension payloads; unknown or missing integrations stay preserved.
     */
    public CloudworksExtensionSet extensions() {
        return extensions;
    }

    /**
     * Node custom-upgrade ownership backing, not a line-wide effect or install source.
     */
    public CloudworksUpgradeSet customUpgradePool() {
        return customUpgradePool;
    }

    public UUID lineId() {
        return lineId;
    }

    public UUID owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null || name.isEmpty() ? "Production Line" : name;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean runWhileOwnerOffline() {
        return runWhileOwnerOffline;
    }

    public void setRunWhileOwnerOffline(boolean value) {
        runWhileOwnerOffline = value;
    }

    // 8.4: explicit routing is folded into the unified flow-gate mechanism;
    // the stored field stays the single truth (NBT key "explicitRouting" unchanged).
    public boolean explicitRouting() {
        return flowGates.gate(CloudworksFlowGate.EXPLICIT_ROUTING);
    }

    public void setExplicitRouting(boolean value) {
        flowGates.set(CloudworksFlowGate.EXPLICIT_ROUTING, value);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double energy() {
        return energy;
    }

    public double maxEnergy() {
        return maxEnergy;
    }

    public void setEnergy(double energy, double maxEnergy) {
        // Settlement boundary: NaN/Infinity must never enter the line pool —
        // Math.max(0, NaN) stays NaN and would poison every later settlement.
        if (!Double.isFinite(energy) || !Double.isFinite(maxEnergy) || energy < 0D || maxEnergy < 0D)
            throw new IllegalArgumentException("Non-finite line energy");
        this.maxEnergy = maxEnergy;
        this.energy = Math.max(0, Math.min(this.maxEnergy, energy));
    }

    public int speedUpgrades() {
        return speedUpgrades;
    }

    public int energyUpgrades() {
        return energyUpgrades;
    }

    public int nodeBufferMultiplier() {
        return nodeBufferMultiplier;
    }

    public void setNodeBufferMultiplier(int value) {
        if (value < 1) throw new IllegalArgumentException("Invalid node buffer multiplier");
        nodeBufferMultiplier = value;
    }

    public int maxSpeedUpgrades() {
        return CloudworksUpgradeLimits.lineCapacity(Upgrade.SPEED, machines);
    }

    public int maxEnergyUpgrades() {
        return CloudworksUpgradeLimits.lineCapacity(Upgrade.ENERGY, machines);
    }

    /**
     * Change-point upgrade setter (Mekanism {@code setUpgrades → onUpgradeChanged}
     * invariant): every count change recalculates the derived energy capacity
     * right here, so no call site can forget. Raw NBT loads bypass this via
     * {@link #setUpgradesRaw(int, int)} — the persisted capacity is authoritative.
     */
    public void setUpgrades(int speed, int energy) {
        int speedLimit = maxSpeedUpgrades(), energyLimit = maxEnergyUpgrades();
        setMaxUpgradesRaw(speedLimit, energyLimit);
        setUpgradesRaw(speed, energy);
        recomputeEnergyCapacity();
    }

    public void recomputeUpgradeLimits() {
        setMaxUpgrades(maxSpeedUpgrades(), maxEnergyUpgrades());
    }

    /**
     * Applies the stored limits without recalculating energy capacity.
     */
    void setUpgradesRaw(int speed, int energy) {
        speedUpgrades = Math.min(maxSpeedUpgrades, Math.max(0, speed));
        energyUpgrades = Math.min(maxEnergyUpgrades, Math.max(0, energy));
    }

    /**
     * Also used by NBT loads, which must preserve the persisted energy capacity.
     */
    void setMaxUpgradesRaw(int speed, int energy) {
        maxSpeedUpgrades = Math.max(0, speed);
        maxEnergyUpgrades = Math.max(0, energy);
        setUpgradesRaw(speedUpgrades, energyUpgrades);
    }

    public void setMaxUpgrades(int speed, int energy) {
        setMaxUpgradesRaw(speed, energy);
        recomputeEnergyCapacity();
    }

    // 8.4: gate accessors delegate to the unified CloudworksFlowGates view.

    /**
     * 产线级能量门: 收货中继直喂本线。
     */
    public boolean energyFlowReceivingToLine() {
        return flowGates.gate(CloudworksFlowGate.RECEIVING_TO_LINE);
    }

    /**
     * 产线级能量门: 本线池直入发货中继。
     */
    public boolean energyFlowLineToSending() {
        return flowGates.gate(CloudworksFlowGate.LINE_TO_SENDING);
    }

    public void setEnergyFlows(boolean receivingToLine, boolean lineToSending) {
        flowGates.set(CloudworksFlowGate.RECEIVING_TO_LINE, receivingToLine);
        flowGates.set(CloudworksFlowGate.LINE_TO_SENDING, lineToSending);
    }

    /**
     * Unified gate view over this line's three gates (two energy + routing model).
     */
    public CloudworksFlowGates flowGates() {
        return flowGates;
    }

    private final CloudworksFlowGates flowGates = new CloudworksFlowGates() {
        @Override
        public CloudworksFlowGate.Scope scope() {
            return CloudworksFlowGate.Scope.LINE;
        }

        @Override
        public boolean gate(CloudworksFlowGate gate) {
            switch (gate) {
                case EXPLICIT_ROUTING:
                    return explicitRouting;
                case RECEIVING_TO_LINE:
                    return energyFlowReceivingToLine;
                case LINE_TO_SENDING:
                    return energyFlowLineToSending;
                default:
                    throw new IllegalArgumentException("Not a line flow gate: " + gate);
            }
        }

        @Override
        public void set(CloudworksFlowGate gate, boolean value) {
            switch (gate) {
                case EXPLICIT_ROUTING:
                    explicitRouting = value;
                    break;
                case RECEIVING_TO_LINE:
                    energyFlowReceivingToLine = value;
                    break;
                case LINE_TO_SENDING:
                    energyFlowLineToSending = value;
                    break;
                default:
                    throw new IllegalArgumentException("Not a line flow gate: " + gate);
            }
        }
    };

    /**
     * Rebuilds the line energy pool from its registered virtual machines.
     */
    public void recomputeEnergyCapacity() {
        setEnergy(energy, upgradedEnergyCapacity());
    }

    public double upgradedEnergyCapacity() {
        // Saturate instead of overflowing: honest machine registrations cannot
        // reach Double.MAX_VALUE here, and setEnergy now refuses non-finite input.
        double capacity = 0D;
        CloudworksUpgradeEffects.Context upgrades = CloudworksUpgradeEffects.context(this);
        for (MachineNodeData node : machines) {
            MachineExecutionProfile profile = node.capabilities().profile();
            if (profile != null) capacity = Math.min(Double.MAX_VALUE,
                    capacity + (upgrades.forNode(node).capacity(profile.energyCapacity())
                            * mekanism.cloudworks.common.service.CloudworksModuleBinding.capacityMultiplier(node)
                            + mekanism.cloudworks.common.service.CloudworksModuleBinding.capacityAddition(node)) * node.machineCount());
        }
        return capacity;
    }

    public int effectiveTicks(int baseTicks) {
        return Math.max(1, CloudworksUpgradeEffects.of(this).ticks(baseTicks));
    }

    public List<MachineNodeData> machines() {
        return machines;
    }

    public List<CloudworksAuxiliaryNodeData> auxiliaries() {
        return auxiliaries;
    }

    public List<ProductionLineLink> links() {
        return links;
    }

    public List<CloudworksChannelNode> channels() {
        return channels;
    }

    public List<String> dynamicResourceDisplayList() {
        return dynamicResourceDisplayList;
    }

    public NBTTagCompound write() {
        NBTTagList nodes = new NBTTagList();
        for (MachineNodeData node : machines) nodes.appendTag(node.write());
        NBTTagList edgeTags = new NBTTagList();
        for (ProductionLineLink link : links) edgeTags.appendTag(link.write());
        NBTTagList channelTags = new NBTTagList();
        for (CloudworksChannelNode channel : channels) channelTags.appendTag(channel.write());
        NBTTagList auxiliaryTags = new NBTTagList();
        for (CloudworksAuxiliaryNodeData auxiliary : auxiliaries) auxiliaryTags.appendTag(auxiliary.write());
        return writeProjection(nodes, edgeTags, channelTags, auxiliaryTags);
    }

    /**
     * Frozen child tags may be reused by the Runtime's copy-on-write projection.
     */
    public NBTTagCompound writeProjection(NBTTagList nodes, NBTTagList edgeTags, NBTTagList channelTags) {
        NBTTagList auxiliaryTags = new NBTTagList();
        for (CloudworksAuxiliaryNodeData auxiliary : auxiliaries) auxiliaryTags.appendTag(auxiliary.write());
        return writeProjection(nodes, edgeTags, channelTags, auxiliaryTags);
    }

    public NBTTagCompound writeProjection(NBTTagList nodes, NBTTagList edgeTags,
                                          NBTTagList channelTags, NBTTagList auxiliaryTags) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", SCHEMA_VERSION);
        tag.setString("lineId", lineId.toString());
        tag.setString("owner", owner.toString());
        tag.setString("name", name);
        tag.setBoolean("enabled", enabled);
        tag.setBoolean("runWhileOwnerOffline", runWhileOwnerOffline);
        tag.setDouble("energy", energy);
        tag.setDouble("maxEnergy", maxEnergy);
        tag.setBoolean("explicitRouting", explicitRouting);
        tag.setInteger("speedUpgrades", speedUpgrades);
        tag.setInteger("energyUpgrades", energyUpgrades);
        tag.setInteger("maxSpeedUpgrades", maxSpeedUpgrades);
        tag.setInteger("maxEnergyUpgrades", maxEnergyUpgrades);
        tag.setInteger("nodeBufferMultiplier", nodeBufferMultiplier);
        tag.setBoolean("energyFlowReceivingToLine", energyFlowReceivingToLine);
        tag.setBoolean("energyFlowLineToSending", energyFlowLineToSending);
        tag.setTag("machines", nodes);
        tag.setTag("links", edgeTags);
        tag.setTag("channels", channelTags);
        tag.setTag("auxiliaries", auxiliaryTags);
        NBTTagList display = new NBTTagList();
        for (String value : dynamicResourceDisplayList) display.appendTag(new NBTTagString(value));
        tag.setTag("dynamicResourceDisplayList", display);
        extensions.write(tag);
        customUpgradePool.write(tag, "customUpgradePool");
        return tag;
    }

}
