package mekanism.cloudworks.common.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

import mekanism.cloudworks.api.recipe.RecipeContribution;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.*;
import mekanism.cloudworks.common.network.data.node.AuxiliaryNode;
import mekanism.cloudworks.common.network.data.node.ChannelNode;
import mekanism.cloudworks.common.network.data.node.MachineNode;
import mekanism.cloudworks.common.network.data.node.Node;
import mekanism.common.PacketHandler;
import mekanism.common.content.qio.QIOResourceEntry;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.common.data.CloudworksThermalState;
import mekanism.cloudworks.common.data.CloudworksUpgradeSet;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.service.CloudworksOutputMultiplier;
import net.minecraft.util.ResourceLocation;

/** Business fields use an explicit wire format; only resource templates retain their native encoding. */
public final class CloudworksGuiCodec {
    public static final int SECTION_COUNT = 6;
    public static final int MAX_BYTES = 2_097_152;
    private static final int MAX_ENTRIES = 65_536;

    private CloudworksGuiCodec() {
    }

    public static byte[][] encode(CloudworksGuiData data) {
        byte[][] result = new byte[SECTION_COUNT][];
        for (int section = 0; section < SECTION_COUNT; section++) {
            PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
            try {
                switch (section) {
                    case 0 -> writeOverview(buffer, data.overview);
                    case 1 -> writeGraph(buffer, data.graph);
                    case 2 -> {
                        buffer.writeInt(data.machineCache.selectedType);
                        writeList(buffer, data.machineCache.types, CloudworksGuiCodec::writeMachine);
                        writeList(buffer, data.machineCache.variants, CloudworksGuiCodec::writeMachine);
                    }
                    case 3 -> {
                        buffer.writeInt(data.choices.offset);
                        buffer.writeInt(data.choices.total);
                        writeList(buffer, data.choices.names, PacketBuffer::writeString);
                        writeList(buffer, data.choices.resources, CloudworksLineDraftCodec::resource);
                        buffer.writeBoolean(data.choices.context != null);
                        if (data.choices.context != null) uuid(buffer, data.choices.context);
                    }
                    case 4 -> {
                        writeList(buffer, data.warehouses.receiving, (b, entry) -> entry.write(b));
                        writeList(buffer, data.warehouses.transfer, (b, entry) -> entry.write(b));
                        writeList(buffer, data.warehouses.sending, (b, entry) -> entry.write(b));
                    }
                    case 5 -> writeList(buffer, data.recycle, (b, entry) -> entry.write(b));
                }
                if (buffer.readableBytes() > MAX_BYTES - 1024) throw new IllegalArgumentException("Cloud GUI section too large");
                result[section] = new byte[buffer.readableBytes()];
                buffer.readBytes(result[section]);
            } finally {
                buffer.release();
            }
        }
        return result;
    }

    public static CloudworksGuiData decode(byte[][] sections) {
        Overview overview = null;
        Graph graph = null;
        MachineCache machines = null;
        Choices choices = null;
        Warehouses warehouses = null;
        List<QIOResourceEntry> recycle = null;
        for (int section = 0; section < SECTION_COUNT; section++) {
            if (sections[section] == null) continue;
            PacketBuffer buffer = new PacketBuffer(Unpooled.wrappedBuffer(sections[section]));
            try {
                switch (section) {
                    case 0 -> overview = readOverview(buffer);
                    case 1 -> graph = readGraph(buffer);
                    case 2 -> machines = new MachineCache(buffer.readInt(),
                            readList(buffer, MAX_ENTRIES, CloudworksGuiCodec::readMachine),
                            readList(buffer, MAX_ENTRIES, CloudworksGuiCodec::readMachine));
                    case 3 -> choices = new Choices(nonnegative(buffer.readInt()), nonnegative(buffer.readInt()),
                            readList(buffer, 64, b -> b.readString(512)),
                            readList(buffer, 64, CloudworksLineDraftCodec::resource),
                            buffer.readBoolean() ? uuid(buffer) : null);
                    case 4 -> warehouses = new Warehouses(readResources(buffer), readResources(buffer), readResources(buffer));
                    case 5 -> recycle = readResources(buffer);
                }
                if (buffer.isReadable()) throw new IllegalArgumentException("Trailing cloud GUI data");
            } finally {
                buffer.release();
            }
        }
        return new CloudworksGuiData(overview, graph, machines, choices, warehouses, recycle);
    }

    private static void writeOverview(PacketBuffer b, Overview value) {
        b.writeInt(value.offset);
        b.writeInt(value.total);
        b.writeInt(value.unlocked);
        b.writeInt(value.maximum);
        b.writeInt(value.workRate);
        b.writeInt(value.outputMultiplierUnits);
        b.writeLong(value.revision);
        b.writeDouble(value.energy);
        b.writeDouble(value.maxEnergy);
        b.writeBoolean(value.unlimited);
        b.writeDouble(value.receivingEnergy);
        b.writeDouble(value.sendingEnergy);
        b.writeDouble(value.receivingCapacity);
        b.writeDouble(value.sendingCapacity);
        b.writeBoolean(value.offlineProcessingEnabled);
        b.writeBoolean(value.receivingToCloud);
        b.writeBoolean(value.cloudToSending);
        b.writeInt(value.speedUpgradePool);
        b.writeInt(value.energyUpgradePool);
        b.writeInt(value.clockTick);
        b.writeInt(value.maxMachineNodes);
        b.writeInt(value.machineNodeLimit);
        b.writeLong(value.maxMachinesPerNode);
        b.writeBoolean(value.recycleToTransfer);
        b.writeBoolean(value.offlineUnlocked);
        b.writeInt(value.netherHeatSourcesUsed);
        writeList(b, value.infiniteResources, (buffer, resource) -> {
            CloudworksLineDraftCodec.resource(buffer, resource.descriptor);
            buffer.writeBoolean(resource.unlimitedPlacement);
            buffer.writeLong(resource.nodeLimit);
            buffer.writeLong(resource.usedOutsideLine);
            buffer.writeLong(resource.outputAmount);
        });
        writeList(b, value.lines, (buffer, line) -> {
            uuid(buffer, line.id);
            buffer.writeString(line.name);
            buffer.writeBoolean(line.available);
            buffer.writeBoolean(line.enabled);
            buffer.writeInt(line.nodeCount);
            buffer.writeDouble(line.energy);
            buffer.writeDouble(line.maxEnergy);
            buffer.writeBoolean(line.receivingFlow);
            buffer.writeBoolean(line.sendingFlow);
            writeStack(buffer, line.icon);
            buffer.writeBoolean(line.runWhileOwnerOffline);
            writeList(buffer, line.markers, CloudworksLineDraftCodec::resource);
        });
        b.writeBoolean(value.team.id != null);
        if (value.team.id != null) {
            uuid(b, value.team.id);
            b.writeString(value.team.name);
            b.writeBoolean(value.team.owner != null);
            if (value.team.owner != null) uuid(b, value.team.owner);
            writeList(b, value.team.members, (buffer, member) -> {
                uuid(buffer, member.id);
                buffer.writeString(member.name);
                buffer.writeBoolean(member.online);
                buffer.writeBoolean(member.allowed);
            });
        }
        b.writeString(value.team.ftbBinding);
        b.writeBoolean(value.team.ftbAvailable);
        b.writeString(value.team.joinMode);
        b.writeInt(value.team.color);
        writeList(b, value.team.requests, (buffer, request) -> {
            uuid(buffer, request.id);
            buffer.writeString(request.name);
        });
        b.writeBoolean(value.customUpgrades != null);
        if (value.customUpgrades != null)
            writeList(b, value.customUpgrades, CloudworksGuiCodec::writeCustomUpgrade);
        writeList(b, value.effectLevels, (buffer, entry) -> {
            buffer.writeString(entry.id);
            buffer.writeLong(entry.level);
        });
    }

    private static Overview readOverview(PacketBuffer b) {
        int offset = nonnegative(b.readInt()), total = nonnegative(b.readInt());
        int unlocked = nonnegative(b.readInt()), maximum = nonnegative(b.readInt()), rate = nonnegative(b.readInt());
        int outputMultiplier = CloudworksOutputMultiplier.validate(b.readInt());
        long revision = nonnegative(b.readLong());
        double energy = energy(b.readDouble()), capacity = b.readDouble();
        boolean unlimited = b.readBoolean();
        if (Double.isNaN(capacity) || capacity < 0 || !unlimited && !Double.isFinite(capacity))
            throw new IllegalArgumentException("Invalid cloud capacity");
        double receiving = energy(b.readDouble()), sending = energy(b.readDouble());
        double receivingCap = energy(b.readDouble()), sendingCap = energy(b.readDouble());
        boolean offlineEnabled = b.readBoolean();
        boolean r2c = b.readBoolean(), c2s = b.readBoolean();
        int speed = nonnegative(b.readInt()), upgrades = nonnegative(b.readInt());
        int clockTick = b.readInt();
        if (clockTick < 1 || clockTick > 24000) throw new IllegalArgumentException("Invalid cloud clock");
        int maxNodes = nonnegative(b.readInt());
        if (maxNodes < 1) throw new IllegalArgumentException("Invalid cloud status");
        int nodeLimit = nonnegative(b.readInt());
        long machinesPerNode = nonnegative(b.readLong());
        if (nodeLimit < 1 || machinesPerNode < 1) throw new IllegalArgumentException("Invalid machine limits");
        boolean recycleToTransfer = b.readBoolean();
        boolean offline = b.readBoolean();
        int netherUsed = nonnegative(b.readInt());
        List<SpecialResource> infiniteResources = readList(b, MAX_ENTRIES,
                buffer -> new SpecialResource(CloudworksLineDraftCodec.resource(buffer), buffer.readBoolean(),
                        buffer.readLong(), buffer.readLong(), buffer.readLong()));
        List<Line> lines = readList(b, 32, buffer -> {
            Line line = new Line(uuid(buffer), buffer.readString(64),
                buffer.readBoolean(), buffer.readBoolean(), nonnegative(buffer.readInt()),
                energy(buffer.readDouble()), energy(buffer.readDouble()), buffer.readBoolean(),
                buffer.readBoolean(), readStack(buffer), buffer.readBoolean());
            line.markers.addAll(readList(buffer, 25, CloudworksLineDraftCodec::resource));
            return line;
        });
        Team team = Team.EMPTY;
        if (b.readBoolean()) {
            UUID id = uuid(b);
            String name = b.readString(128);
            UUID owner = b.readBoolean() ? uuid(b) : null;
            List<Member> members = readList(b, MAX_ENTRIES,
                    buffer -> new Member(uuid(buffer), buffer.readString(128), buffer.readBoolean(), buffer.readBoolean()));
            team = new Team(id, name, owner, members);
        }
        String binding = b.readString(128);
        boolean ftbAvailable = b.readBoolean();
        String joinMode = b.readString(32);
        if (!"AUTO_JOIN".equals(joinMode) && !"APPROVAL".equals(joinMode) && !"DISABLED".equals(joinMode))
            throw new IllegalArgumentException("Invalid team join mode");
        int color = b.readInt();
        List<TeamRequest> requests = readList(b, MAX_ENTRIES,
                buffer -> new TeamRequest(uuid(buffer), buffer.readString(64)));
        team = new Team(team.id, team.name, team.owner, team.members, binding, ftbAvailable,
                joinMode, color, requests);
        // The upload catalogue includes zero-count declarations, not only the bounded asset pool.
        // Byte-derived ceiling: a claimed count may never exceed what the section's bytes can hold.
        // Absent marker = on-demand catalogue not requested (upgrade window closed).
        List<CustomUpgrade> customUpgrades = b.readBoolean()
                ? readList(b, b.readableBytes() / 12, CloudworksGuiCodec::readCustomUpgrade) : null;
        // Byte-derived ceiling (id string + long level ≥ 12 bytes each): the
        // write side has no count limit, so the read side must not either.
        List<EffectLevel> effectLevels = readList(b, b.readableBytes() / 12,
                buffer -> {
                    String id = buffer.readString(256);
                    try {
                        new ResourceLocation(id);
                    } catch (Exception invalid) {
                        throw new IllegalArgumentException("Invalid cloud effect id");
                    }
                    return new EffectLevel(id, nonnegative(buffer.readLong()));
                });
        if (offset > total || lines.size() > total - offset || unlocked > maximum || rate < 1)
            throw new IllegalArgumentException("Invalid cloud directory");
        return new Overview(offset, total, unlocked, maximum, rate, revision, energy, capacity,
                unlimited, receiving, sending, r2c, c2s, speed, upgrades, clockTick, lines,
                maxNodes, offline, receivingCap, sendingCap, offlineEnabled,
                nodeLimit, machinesPerNode, recycleToTransfer, infiniteResources, outputMultiplier, team,
                customUpgrades, effectLevels).withNetherSourceUsage(netherUsed);
    }

    private static void writeGraph(PacketBuffer b, Graph graph) {
        b.writeBoolean(graph.id != null);
        if (graph.id == null) return;
        uuid(b, graph.id);
        b.writeString(graph.name);
        b.writeBoolean(graph.enabled);
        b.writeBoolean(graph.explicitRouting);
        b.writeDouble(graph.energy);
        b.writeDouble(graph.maxEnergy);
        b.writeInt(graph.speed);
        b.writeInt(graph.energyUpgrades);
        b.writeBoolean(graph.configuration != null);
        if (graph.configuration != null) CloudworksLineDraftCodec.write(b, graph.configuration);
        writeList(b, graph.nodes, CloudworksGuiCodec::writeNode);
        writeList(b, graph.links, (buffer, link) -> {
            uuid(buffer, link.id);
            uuid(buffer, link.sourceNode);
            buffer.writeString(link.sourcePort);
            uuid(buffer, link.targetNode);
            buffer.writeString(link.targetPort);
            buffer.writeBoolean(link.enabled);
            buffer.writeInt(link.priority);
            buffer.writeDouble(link.ratio);
            writeList(buffer, link.filterNames, PacketBuffer::writeString);
        });
        writeList(b, graph.customUpgrades, CloudworksGuiCodec::writeCustomUpgrade);
    }

    private static Graph readGraph(PacketBuffer b) {
        if (!b.readBoolean()) return Graph.EMPTY;
        UUID id = uuid(b);
        String name = b.readString(64);
        boolean enabled = b.readBoolean(), routing = b.readBoolean();
        double energy = energy(b.readDouble()), maximum = energy(b.readDouble());
        int speed = nonnegative(b.readInt()), upgrades = nonnegative(b.readInt());
        CloudworksLineDraft configuration =
                b.readBoolean() ? CloudworksLineDraftCodec.read(b) : null;
        List<Node> nodes = readList(b, b.readableBytes() / 32, CloudworksGuiCodec::readNode);
        List<Link> links = readList(b, b.readableBytes() / 64, buffer -> new Link(uuid(buffer), uuid(buffer),
                buffer.readString(128), uuid(buffer), buffer.readString(128), buffer.readBoolean(),
                buffer.readInt(), ratio(buffer.readDouble()), readList(buffer, MAX_ENTRIES, in -> in.readString(512))));
        Graph graph = new Graph(id, name, enabled, routing, energy, maximum, speed, upgrades, nodes, links);
        graph.configuration = configuration;
        graph.customUpgrades = readList(b, b.readableBytes() / 12,
                CloudworksGuiCodec::readCustomUpgrade);
        return graph;
    }

    /** Common node projection first, then the Kind-specific identity and tail fields. */
    private static void writeNode(PacketBuffer buffer, Node node) {
        buffer.writeByte(node.kind().ordinal());
        uuid(buffer, node.id);
        buffer.writeString(node.state);
        buffer.writeInt(node.x);
        buffer.writeInt(node.z);
        buffer.writeBoolean(node.active);
        writeSummaries(buffer, node.input);
        writeSummaries(buffer, node.output);
        writeSummaries(buffer, node.templates);
        writeList(buffer, node.ports, (out, port) -> {
            out.writeString(port.id);
            out.writeString(port.codec);
            out.writeBoolean(port.output);
        });
        buffer.writeBoolean(node.thermal != null);
        if (node.thermal != null) {
            buffer.writeDouble(node.thermal.heat); buffer.writeDouble(node.thermal.capacity);
            buffer.writeDouble(node.thermal.plasma); buffer.writeDouble(node.thermal.fuelTicks);
            buffer.writeDouble(node.thermal.credit); buffer.writeDouble(node.thermal.produced);
            buffer.writeDouble(node.thermal.consumed); buffer.writeDouble(node.thermal.loss);
            buffer.writeBoolean(node.thermal.burning);
        }
        writeList(buffer, node.branches, (out, branch) -> {
            out.writeString(branch.id); out.writeString(branch.state);
            out.writeInt(branch.progress); out.writeInt(branch.duration);
        });
        if (node instanceof MachineNode) {
            MachineNode machine = (MachineNode) node;
            buffer.writeString(machine.machineIdentity);
            buffer.writeInt(machine.count);
            buffer.writeInt(machine.progress);
            buffer.writeInt(machine.duration);
        } else if (node instanceof AuxiliaryNode) {
            buffer.writeString(((AuxiliaryNode) node).auxiliaryItem);
        } else {
            ChannelNode channel = (ChannelNode) node;
            buffer.writeString(channel.archetypeId);
            buffer.writeInt(channel.areaHits);
        }
    }

    private static Node readNode(PacketBuffer buffer) {
        Node.Kind kind = Node.Kind.values()[buffer.readByte()];
        UUID id = uuid(buffer);
        String state = buffer.readString(128);
        int x = buffer.readInt(), z = buffer.readInt();
        boolean active = buffer.readBoolean();
        List<ResourceSummary> input = readSummaries(buffer), output = readSummaries(buffer),
                templates = readSummaries(buffer);
        List<Port> ports = readList(buffer, buffer.readableBytes() / 3,
                in -> new Port(in.readString(128), in.readString(256), in.readBoolean()));
        CloudworksThermalState thermal = buffer.readBoolean() ? new CloudworksThermalState(
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readBoolean()) : null;
        List<ExecutionBranch> branches = readList(buffer, buffer.readableBytes() / 8, in ->
                new ExecutionBranch(in.readString(128), in.readString(128), in.readInt(), in.readInt()));
        String identity = buffer.readString(128);
        switch (kind) {
            case MACHINE: return new MachineNode(id, state, identity, x, z,
                    nonnegative(buffer.readInt()), nonnegative(buffer.readInt()),
                    nonnegative(buffer.readInt()), active,
                    input, output, templates, ports, thermal, branches);
            case AUXILIARY: return new AuxiliaryNode(id, state, identity, x, z, active, input, ports);
            default: return new ChannelNode(id, state, identity, x, z, active,
                    input, nonnegative(buffer.readInt()));
        }
    }

    private static void writeCustomUpgrade(PacketBuffer buffer, CustomUpgrade entry) {
        buffer.writeString(entry.id);
        buffer.writeLong(entry.count);
    }

    private static CustomUpgrade readCustomUpgrade(PacketBuffer buffer) {
        String id = buffer.readString(128);
        new ResourceLocation(id);
        return new CustomUpgrade(id, nonnegative(buffer.readLong()));
    }

    public static void writeMachine(PacketBuffer b, Machine machine) {
        writeStack(b, machine.stack);
        b.writeLong(machine.count);
        b.writeLong(machine.claimed);
        b.writeLong(machine.available);
        b.writeString(machine.categoryId);
        writeList(b, machine.claims, (buffer, claim) -> {
            uuid(buffer, claim.line);
            buffer.writeString(claim.name);
            buffer.writeLong(claim.count);
        });
    }

    public static Machine readMachine(PacketBuffer b) {
        ItemStack stack = readStack(b);
        long count = nonnegative(b.readLong()), claimed = nonnegative(b.readLong()),
                available = nonnegative(b.readLong());
        String categoryId = b.readString(128);
        return new Machine(stack, count, claimed, available, readList(b, MAX_ENTRIES,
                buffer -> new Claim(uuid(buffer), buffer.readString(64), nonnegative(buffer.readLong()))),
                categoryId);
    }

    private static void writeSummaries(PacketBuffer b, List<ResourceSummary> summaries) {
        writeList(b, summaries, (buffer, value) -> {
            buffer.writeString(value.name);
            buffer.writeLong(value.amount);
            CloudworksLineDraftCodec.resource(buffer, value.descriptor);
        });
    }

    private static List<ResourceSummary> readSummaries(PacketBuffer b) {
        return readList(b, MAX_ENTRIES, buffer -> new ResourceSummary(buffer.readString(512),
                nonnegative(buffer.readLong()), CloudworksLineDraftCodec.resource(buffer)));
    }

    private static List<QIOResourceEntry> readResources(PacketBuffer b) {
        return readList(b, MAX_ENTRIES, buffer -> {
            QIOResourceEntry entry = QIOResourceEntry.read(buffer);
            if (entry == null) throw new IllegalArgumentException("Invalid QIO resource template");
            return entry;
        });
    }

    private static <T> void writeList(PacketBuffer b, List<T> values, BiConsumer<PacketBuffer, T> writer) {
        if (values.size() > MAX_ENTRIES) throw new IllegalArgumentException("Cloud GUI list too large");
        b.writeVarInt(values.size());
        for (T value : values) writer.accept(b, value);
    }

    private static <T> List<T> readList(PacketBuffer b, int maximum, Function<PacketBuffer, T> reader) {
        int size = b.readVarInt();
        if (size < 0 || size > maximum) throw new IllegalArgumentException("Invalid cloud GUI list size");
        List<T> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) result.add(reader.apply(b));
        return result;
    }


    public static void writeStack(ByteBuf b, ItemStack stack) {
        // Full item templates preserve NBT variants and ForgeCaps, not cloud business state.
        PacketHandler.writeNBT(b, stack.writeToNBT(new NBTTagCompound()));
    }

    public static ItemStack readStack(ByteBuf b) {
        NBTTagCompound tag = PacketHandler.readNBT(b);
        if (tag == null) throw new IllegalArgumentException("Missing item template");
        return new ItemStack(tag);
    }

    public static void uuid(ByteBuf b, UUID id) {
        b.writeLong(id.getMostSignificantBits());
        b.writeLong(id.getLeastSignificantBits());
    }

    public static UUID uuid(ByteBuf b) {
        return new UUID(b.readLong(), b.readLong());
    }

    private static int nonnegative(int value) {
        if (value < 0) throw new IllegalArgumentException("Negative GUI count");
        return value;
    }

    private static long nonnegative(long value) {
        if (value < 0) throw new IllegalArgumentException("Negative GUI count");
        return value;
    }

    private static double energy(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid GUI energy");
        return value;
    }

    private static double ratio(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Invalid GUI ratio");
        return value;
    }
}
