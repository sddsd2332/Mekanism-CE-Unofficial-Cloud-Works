package mekanism.cloudworks.common.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.*;
import mekanism.common.PacketHandler;
import mekanism.common.content.qio.QIOResourceEntry;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;

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
        b.writeInt(value.simulatedHeight);
        b.writeInt(value.lavaHeatCards);
        b.writeInt(value.maxMachineNodes);
        b.writeInt(value.machineNodeLimit);
        b.writeLong(value.maxMachinesPerNode);
        b.writeBoolean(value.recycleToTransfer);
        b.writeBoolean(value.offlineUnlocked);
        b.writeBoolean(value.netherEnvironment);
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
    }

    private static Overview readOverview(PacketBuffer b) {
        int offset = nonnegative(b.readInt()), total = nonnegative(b.readInt());
        int unlocked = nonnegative(b.readInt()), maximum = nonnegative(b.readInt()), rate = nonnegative(b.readInt());
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
        int height = nonnegative(b.readInt()), lava = nonnegative(b.readInt()), maxNodes = nonnegative(b.readInt());
        if (height > 256 || lava > 7 || maxNodes < 1) throw new IllegalArgumentException("Invalid cloud status");
        int nodeLimit = nonnegative(b.readInt());
        long machinesPerNode = nonnegative(b.readLong());
        if (nodeLimit < 1 || machinesPerNode < 1) throw new IllegalArgumentException("Invalid machine limits");
        boolean recycleToTransfer = b.readBoolean();
        boolean offline = b.readBoolean(), nether = b.readBoolean();
        List<Line> lines = readList(b, 32, buffer -> {
            Line line = new Line(uuid(buffer), buffer.readString(64),
                buffer.readBoolean(), buffer.readBoolean(), nonnegative(buffer.readInt()),
                energy(buffer.readDouble()), energy(buffer.readDouble()), buffer.readBoolean(),
                buffer.readBoolean(), readStack(buffer), buffer.readBoolean());
            line.markers.addAll(readList(buffer, 25, CloudworksLineDraftCodec::resource));
            return line;
        });
        if (offset > total || lines.size() > total - offset || unlocked > maximum || rate < 1)
            throw new IllegalArgumentException("Invalid cloud directory");
        return new Overview(offset, total, unlocked, maximum, rate, revision, energy, capacity,
                unlimited, receiving, sending, r2c, c2s, speed, upgrades, clockTick, lines,
                height, lava, maxNodes, offline, nether, receivingCap, sendingCap, offlineEnabled,
                nodeLimit, machinesPerNode, recycleToTransfer);
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
        writeList(b, graph.nodes, (buffer, node) -> {
            uuid(buffer, node.id);
            buffer.writeString(node.name);
            buffer.writeString(node.state);
            writeStack(buffer, node.icon);
            buffer.writeInt(node.x);
            buffer.writeInt(node.z);
            buffer.writeInt(node.count);
            buffer.writeInt(node.progress);
            buffer.writeInt(node.duration);
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
        });
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
    }

    private static Graph readGraph(PacketBuffer b) {
        if (!b.readBoolean()) return Graph.EMPTY;
        UUID id = uuid(b);
        String name = b.readString(64);
        boolean enabled = b.readBoolean(), routing = b.readBoolean();
        double energy = energy(b.readDouble()), maximum = energy(b.readDouble());
        int speed = nonnegative(b.readInt()), upgrades = nonnegative(b.readInt());
        mekanism.cloudworks.common.network.data.CloudworksLineDraft configuration =
                b.readBoolean() ? CloudworksLineDraftCodec.read(b) : null;
        List<Node> nodes = readList(b, b.readableBytes() / 32, buffer -> new Node(uuid(buffer), buffer.readString(512),
                buffer.readString(128), readStack(buffer), buffer.readInt(), buffer.readInt(),
                nonnegative(buffer.readInt()), nonnegative(buffer.readInt()), nonnegative(buffer.readInt()),
                buffer.readBoolean(), readSummaries(buffer), readSummaries(buffer), readSummaries(buffer),
                readList(buffer, 1024, in -> new Port(in.readString(128), in.readString(256), in.readBoolean())),
                buffer.readBoolean() ? new mekanism.cloudworks.common.data.CloudworksThermalState(
                        buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                        buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readBoolean()) : null));
        List<Link> links = readList(b, b.readableBytes() / 64, buffer -> new Link(uuid(buffer), uuid(buffer),
                buffer.readString(128), uuid(buffer), buffer.readString(128), buffer.readBoolean(),
                buffer.readInt(), ratio(buffer.readDouble()), readList(buffer, MAX_ENTRIES, in -> in.readString(512))));
        Graph graph = new Graph(id, name, enabled, routing, energy, maximum, speed, upgrades, nodes, links);
        graph.configuration = configuration;
        return graph;
    }

    public static void writeMachine(PacketBuffer b, Machine machine) {
        writeStack(b, machine.stack);
        b.writeLong(machine.count);
        b.writeLong(machine.claimed);
        b.writeLong(machine.available);
        writeList(b, machine.claims, (buffer, claim) -> {
            uuid(buffer, claim.line);
            buffer.writeString(claim.name);
            buffer.writeLong(claim.count);
        });
    }

    public static Machine readMachine(PacketBuffer b) {
        return new Machine(readStack(b), nonnegative(b.readLong()), nonnegative(b.readLong()),
                nonnegative(b.readLong()), readList(b, MAX_ENTRIES,
                        buffer -> new Claim(uuid(buffer), buffer.readString(64), nonnegative(buffer.readLong()))));
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
