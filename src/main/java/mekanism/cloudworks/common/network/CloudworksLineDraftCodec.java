package mekanism.cloudworks.common.network;

import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksChannelNode;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft.*;
import mekanism.common.PacketHandler;
import net.minecraft.network.PacketBuffer;

public final class CloudworksLineDraftCodec {
    private CloudworksLineDraftCodec() {
    }

    public static final class UnsupportedBlueprintVersionException extends IllegalArgumentException {
        public UnsupportedBlueprintVersionException() {
            super("cloudworks.error.blueprint_invalid");
        }
    }

    public static void write(PacketBuffer b, CloudworksLineDraft draft) {
        CloudworksGuiCodec.uuid(b, draft.line);
        b.writeString(draft.name);
        b.writeBoolean(draft.enabled);
        b.writeBoolean(draft.runWhileOwnerOffline);
        b.writeBoolean(draft.receivingFlow);
        b.writeBoolean(draft.sendingFlow);
        b.writeBoolean(draft.explicitRouting);
        b.writeInt(draft.speed);
        b.writeInt(draft.energy);
        b.writeInt(draft.maxSpeed);
        b.writeInt(draft.maxEnergy);
        b.writeInt(draft.maxNodes);
        b.writeLong(draft.maxMachinesPerNode);
        b.writeVarInt(draft.markers.size());
        for (QIOResourceDescriptor marker : draft.markers) resource(b, marker);
        b.writeVarInt(draft.machines.size());
        for (Machine machine : draft.machines) {
            CloudworksGuiCodec.uuid(b, machine.id);
            CloudworksGuiCodec.writeStack(b, machine.type);
            b.writeInt(machine.x);
            b.writeInt(machine.y);
            b.writeLong(machine.count);
            b.writeBoolean(machine.active);
            b.writeInt(machine.dimension);
            optionalUuid(b, machine.waiting);
            b.writeLong(machine.required);
            b.writeLong(machine.missing);
        }
        b.writeVarInt(draft.channels.size());
        for (Channel channel : draft.channels) {
            CloudworksGuiCodec.uuid(b, channel.id);
            b.writeEnumValue(channel.mode);
            b.writeInt(channel.x);
            b.writeInt(channel.y);
            b.writeBoolean(channel.active);
            optionalUuid(b, channel.waiting);
            b.writeVarInt(channel.resources.size());
            for (Resource resource : channel.resources) {
                resource(b, resource.descriptor);
                b.writeLong(resource.amount);
            }
        }
        b.writeVarInt(draft.links.size());
        for (Wire wire : draft.links) {
            CloudworksGuiCodec.uuid(b, wire.id);
            CloudworksGuiCodec.uuid(b, wire.source);
            b.writeString(wire.sourcePort);
            CloudworksGuiCodec.uuid(b, wire.target);
            b.writeString(wire.targetPort);
            b.writeBoolean(wire.enabled);
            b.writeInt(wire.priority);
            b.writeDouble(wire.ratio);
            b.writeVarInt(wire.filters.size());
            for (QIOResourceDescriptor filter : wire.filters) resource(b, filter);
        }
    }

    public static CloudworksLineDraft read(PacketBuffer b) {
        return readDraft(b);
    }

    private static CloudworksLineDraft readDraft(PacketBuffer b) {
        CloudworksLineDraft draft = new CloudworksLineDraft();
        draft.line = CloudworksGuiCodec.uuid(b);
        draft.name = b.readString(64);
        draft.enabled = b.readBoolean();
        draft.runWhileOwnerOffline = b.readBoolean();
        draft.receivingFlow = b.readBoolean();
        draft.sendingFlow = b.readBoolean();
        draft.explicitRouting = b.readBoolean();
        draft.speed = b.readInt();
        draft.energy = b.readInt();
        draft.maxSpeed = b.readInt();
        draft.maxEnergy = b.readInt();
        draft.maxNodes = b.readInt();
        draft.maxMachinesPerNode = b.readLong();
        if (draft.maxNodes < 1 || draft.maxMachinesPerNode < 1) throw new IllegalArgumentException("Invalid machine limits");
        for (int i = count(b, CloudworksLineDraft.MAX_MARKERS); i > 0; i--) draft.markers.add(resource(b));
        for (int i = count(b, b.readableBytes() / 33); i > 0; i--) {
            Machine machine = new Machine(CloudworksGuiCodec.uuid(b), CloudworksGuiCodec.readStack(b),
                    coordinate(b), coordinate(b), b.readLong());
            machine.active = b.readBoolean();
            machine.dimension = b.readInt();
            machine.waiting = optionalUuid(b);
            machine.required = b.readLong();
            machine.missing = b.readLong();
            if (machine.count < 0 || machine.count == 0 && machine.waiting == null
                    || machine.count > 0 && machine.waiting != null || machine.type.isEmpty())
                throw new IllegalArgumentException("Invalid machine");
            if (machine.required < 0 || machine.missing < 0 || machine.missing > machine.required
                    || (machine.waiting != null) != (machine.required > 0))
                throw new IllegalArgumentException("Invalid waiting machine");
            draft.machines.add(machine);
        }
        for (int i = count(b, b.readableBytes() / 27); i > 0; i--) {
            UUID id = CloudworksGuiCodec.uuid(b);
            int ordinal = b.readVarInt();
            if (ordinal < 0 || ordinal >= CloudworksChannelNode.Mode.values().length)
                throw new IllegalArgumentException("Invalid channel");
            Channel channel = new Channel(id, CloudworksChannelNode.Mode.values()[ordinal], coordinate(b), coordinate(b));
            channel.active = b.readBoolean();
            channel.waiting = optionalUuid(b);
            // Bound decoding by bytes actually present, not a gameplay limit on resource types.
            for (int j = count(b, b.readableBytes() / Long.BYTES); j > 0; j--) {
                Resource resource = new Resource(resource(b), b.readLong());
                if (resource.amount <= 0) throw new IllegalArgumentException("Invalid resource amount");
                channel.resources.add(resource);
            }
            draft.channels.add(channel);
        }
        for (int i = count(b, b.readableBytes() / 64); i > 0; i--) {
            Wire wire = new Wire(CloudworksGuiCodec.uuid(b), CloudworksGuiCodec.uuid(b), b.readString(128),
                    CloudworksGuiCodec.uuid(b), b.readString(128));
            wire.enabled = b.readBoolean();
            wire.priority = b.readInt();
            wire.ratio = b.readDouble();
            if (!Double.isFinite(wire.ratio) || wire.ratio < 0 || wire.ratio > 1)
                throw new IllegalArgumentException("Invalid ratio");
            java.util.Set<QIOResourceDescriptor> unique = new java.util.HashSet<>();
            for (int j = count(b, b.readableBytes()); j > 0; j--) {
                QIOResourceDescriptor filter = resource(b);
                if (!unique.add(filter)) throw new IllegalArgumentException("Duplicate link filter");
                wire.filters.add(filter);
            }
            draft.links.add(wire);
        }
        return draft;
    }

    public static void resource(PacketBuffer b, QIOResourceDescriptor descriptor) {
        PacketHandler.writeNBT(b, descriptor.write());
    }

    public static QIOResourceDescriptor resource(PacketBuffer b) {
        QIOResourceDescriptor descriptor = QIOResourceDescriptor.read(PacketHandler.readNBT(b));
        if (descriptor == null || !descriptor.isResolved()) throw new IllegalArgumentException("Invalid resource");
        return descriptor;
    }

    public static int count(PacketBuffer b, int maximum) {
        int count = b.readVarInt();
        if (count < 0 || count > maximum) throw new IllegalArgumentException("Invalid list size");
        return count;
    }

    private static int coordinate(PacketBuffer b) {
        int value = b.readInt();
        if (Math.abs((long) value) > 1_000_000) throw new IllegalArgumentException("Invalid coordinate");
        return value;
    }

    private static void optionalUuid(PacketBuffer b, UUID id) {
        b.writeBoolean(id != null);
        if (id != null) CloudworksGuiCodec.uuid(b, id);
    }

    private static UUID optionalUuid(PacketBuffer b) {
        return b.readBoolean() ? CloudworksGuiCodec.uuid(b) : null;
    }

    public static byte[] encode(CloudworksLineDraft draft) {
        PacketBuffer buffer = new PacketBuffer(io.netty.buffer.Unpooled.buffer());
        try {
            write(buffer, draft);
            if (buffer.readableBytes() > CloudworksGuiCodec.MAX_BYTES - 1024)
                throw new IllegalArgumentException("cloudworks.error.editor_size");
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally { buffer.release(); }
    }

    public static CloudworksLineDraft decode(byte[] bytes) {
        PacketBuffer buffer = new PacketBuffer(io.netty.buffer.Unpooled.wrappedBuffer(bytes));
        try {
            if (bytes.length > CloudworksGuiCodec.MAX_BYTES) throw new IllegalArgumentException("Invalid draft size");
            CloudworksLineDraft draft = read(buffer);
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing draft data");
            return draft;
        } finally { buffer.release(); }
    }

    public static CloudworksLineDraft decodeBlueprint(byte[] bytes, int schemaVersion) {
        if (bytes.length > CloudworksGuiCodec.MAX_BYTES) throw new IllegalArgumentException("Invalid draft size");
        if (schemaVersion == 1) return decode(bytes);
        throw new UnsupportedBlueprintVersionException();
    }

    public static CloudworksLineDraft copy(CloudworksLineDraft source) {
        // Item/resource objects are immutable identities; copy mutable collections without native API reads.
        CloudworksLineDraft result = new CloudworksLineDraft();
        result.line = source.line;
        result.name = source.name;
        result.enabled = source.enabled;
        result.receivingFlow = source.receivingFlow;
        result.sendingFlow = source.sendingFlow;
        result.explicitRouting = source.explicitRouting;
        result.runWhileOwnerOffline = source.runWhileOwnerOffline;
        result.speed = source.speed;
        result.energy = source.energy;
        result.maxSpeed = source.maxSpeed;
        result.maxEnergy = source.maxEnergy;
        result.maxNodes = source.maxNodes;
        result.maxMachinesPerNode = source.maxMachinesPerNode;
        result.markers.addAll(source.markers);
        for (Machine m : source.machines) {
            Machine next = new Machine(m.id, m.type, m.x, m.y, m.count);
            next.dimension = m.dimension;
            next.active = m.active;
            next.waiting = m.waiting;
            next.required = m.required;
            next.missing = m.missing;
            result.machines.add(next);
        }
        for (Channel c : source.channels) {
            Channel next = new Channel(c.id, c.mode, c.x, c.y);
            next.active = c.active;
            next.waiting = c.waiting;
            for (Resource r : c.resources) next.resources.add(new Resource(r.descriptor, r.amount));
            result.channels.add(next);
        }
        for (Wire w : source.links) {
            Wire next = new Wire(w.id, w.source, w.sourcePort, w.target, w.targetPort);
            next.enabled = w.enabled;
            next.priority = w.priority;
            next.ratio = w.ratio;
            next.filters.addAll(w.filters);
            result.links.add(next);
        }
        return result;
    }
}
