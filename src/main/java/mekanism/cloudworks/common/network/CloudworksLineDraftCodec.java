package mekanism.cloudworks.common.network;

import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksChannelNode;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft.*;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeLimits;
import mekanism.common.PacketHandler;
import net.minecraft.network.PacketBuffer;
import io.netty.buffer.Unpooled;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import mekanism.cloudworks.common.data.CloudworksUpgradeSet;
import mekanism.cloudworks.common.recipe.MachineRecipeTypeEntry;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetypes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

public final class CloudworksLineDraftCodec {
    public static final int SCHEMA_VERSION = 13;
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
        b.writeInt(draft.nodeBufferMultiplier);
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
            optionalUuid(b, machine.waiting);
            b.writeLong(machine.required);
            b.writeLong(machine.missing);
            b.writeString(machine.recipeType);
            PacketHandler.writeNBT(b, oreRuleTag(machine));
            b.writeVarInt(machine.assignedCustom.size());
            for (Map.Entry<String, Long> entry : machine.assignedCustom.entrySet()) {
                if (entry.getKey() == null
                        || entry.getKey().length() > CloudworksLineDraft.MAX_CUSTOM_UPGRADE_ID_CHARS)
                    throw new IllegalArgumentException("Invalid custom upgrade assignment id");
                b.writeString(entry.getKey());
                b.writeLong(entry.getValue());
            }
            b.writeString(machine.waitingMechanism);
        }
        b.writeVarInt(draft.auxiliaries.size());
        for (Auxiliary auxiliary : draft.auxiliaries) {
            CloudworksGuiCodec.uuid(b, auxiliary.id);
            CloudworksGuiCodec.writeStack(b, auxiliary.type);
            b.writeInt(auxiliary.x);
            b.writeInt(auxiliary.y);
            b.writeInt(auxiliary.range);
            b.writeBoolean(auxiliary.active);
            b.writeVarInt(auxiliary.targets.size());
            for (UUID target : auxiliary.targets) CloudworksGuiCodec.uuid(b, target);
            b.writeVarInt(auxiliary.resources.size());
            for (Resource resource : auxiliary.resources) {
                resource(b, resource.descriptor);
                b.writeLong(resource.amount);
            }
        }
        b.writeVarInt(draft.channels.size());
        for (Channel channel : draft.channels) {
            CloudworksGuiCodec.uuid(b, channel.id);
            b.writeEnumValue(channel.mode);
            b.writeString(channel.archetypeId == null ? "" : channel.archetypeId);
            b.writeInt(channel.x);
            b.writeInt(channel.y);
            b.writeBoolean(channel.active);
            optionalUuid(b, channel.waiting);
            b.writeVarInt(channel.resources.size());
            for (Resource resource : channel.resources) {
                resource(b, resource.descriptor);
                b.writeLong(resource.amount);
            }
            boolean hasConfig = !channel.config.getKeySet().isEmpty();
            b.writeBoolean(hasConfig);
            if (hasConfig) {
                if (channel.config.toString().length() > CloudworksChannelNode.MAX_CONFIG_CHARS)
                    throw new IllegalArgumentException("Special node configuration too large");
                PacketHandler.writeNBT(b, channel.config);
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
        // Schema 11 dropped the native assignment pair; only legacy blueprint
        // versions (<=10) still carry it through decodeBlueprint's flags.
        return readDraft(b, true, true, false, false, true, true, true, true, true, false);
    }

    private static CloudworksLineDraft readDraft(PacketBuffer b, boolean recipeTypes, boolean specialNodes,
            boolean nodeUpgrades, boolean legacyTeAugments, boolean customUpgrades, boolean channelConfig,
            boolean mechanismHints, boolean auxiliaryNodes, boolean oreRules, boolean legacyDimensions) {
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
        if (recipeTypes) draft.nodeBufferMultiplier = b.readInt();
        if (draft.maxNodes < 1 || draft.maxMachinesPerNode < 1) throw new IllegalArgumentException("Invalid machine limits");
        if (draft.nodeBufferMultiplier < 1) throw new IllegalArgumentException("Invalid node buffer multiplier");
        for (int i = count(b, CloudworksLineDraft.MAX_MARKERS); i > 0; i--) draft.markers.add(resource(b));
        for (int i = count(b, b.readableBytes() / (legacyDimensions ? 33 : 29)); i > 0; i--) {
            Machine machine = new Machine(CloudworksGuiCodec.uuid(b), CloudworksGuiCodec.readStack(b),
                    coordinate(b), coordinate(b), b.readLong());
            machine.active = b.readBoolean();
            if (legacyDimensions) b.readInt();
            machine.waiting = optionalUuid(b);
            machine.required = b.readLong();
            machine.missing = b.readLong();
            if (recipeTypes) {
                machine.recipeType = b.readString(MachineRecipeTypeEntry.MAX_ID_LENGTH);
                if (oreRules) {
                    NBTTagCompound configuration = PacketHandler.readNBT(b);
                    machine.oreRules.addAll(mekanism.cloudworks.common.data.CloudworksOreRule.readList(configuration.getTagList("rules", 10)));
                    if (configuration.hasKey("fusionInjectionRate")) {
                        if (!configuration.hasKey("fusionInjectionRate", 3)) throw new IllegalArgumentException("Invalid fusion injection type");
                        machine.fusionInjectionRate = configuration.getInteger("fusionInjectionRate");
                        if (machine.fusionInjectionRate < 0 || (machine.fusionInjectionRate & 1) != 0)
                            throw new IllegalArgumentException("cloudworks.error.fusion_injection_invalid");
                    }
                }
                if (!machine.recipeType.isEmpty()
                        && !MachineRecipeTypeEntry.validId(machine.recipeType))
                    throw new IllegalArgumentException("Invalid machine recipe type");
            }
            if (nodeUpgrades) {
                // Legacy blueprints (schema <=10) still carry the retired native
                // assignment pair; consume and discard it to keep the version
                // matrix decoding (axis-priority batch 3c).
                b.readInt();
                b.readInt();
            }
            if (customUpgrades) {
                for (int entry = count(b, b.readableBytes() / 12);
                        entry > 0; entry--) {
                    String id = b.readString(CloudworksLineDraft.MAX_CUSTOM_UPGRADE_ID_CHARS);
                    if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                        throw new IllegalArgumentException("Invalid custom upgrade assignment id");
                    try {
                        new ResourceLocation(id);
                    } catch (Exception invalid) {
                        throw new IllegalArgumentException("Invalid custom upgrade assignment id");
                    }
                    long value = b.readLong();
                    if (value <= 0 || machine.assignedCustom.containsKey(id))
                        throw new IllegalArgumentException("Invalid custom upgrade assignment");
                    machine.assignedCustom.put(id, value);
                }
            }
            if (legacyTeAugments) {
                // Schema 5 carried retired Thermal Expansion augment identifiers;
                // they are consumed and discarded so legacy blueprints still load.
                int augmentCount = count(b, 64);
                for (int augment = 0; augment < augmentCount; augment++) b.readString(128);
            }
            if (mechanismHints) {
                machine.waitingMechanism = b.readString(
                        CloudworksNodeMechanisms.MAX_SHAPE_CHARS);
                CloudworksNodeMechanisms.split(machine.waitingMechanism);
                if (!machine.waitingMechanism.isEmpty() && machine.waiting == null)
                    throw new IllegalArgumentException("Invalid waiting mechanism preview");
            }
            if (machine.count < 0 || machine.count == 0 && machine.waiting == null
                    || machine.count > 0 && machine.waiting != null || machine.type.isEmpty())
                throw new IllegalArgumentException("Invalid machine");
            if (machine.required < 0 || machine.missing < 0 || machine.missing > machine.required
                    || (machine.waiting != null) != (machine.required > 0))
                throw new IllegalArgumentException("Invalid waiting machine");
            draft.machines.add(machine);
        }
        if (auxiliaryNodes) {
            for (int i = count(b, b.readableBytes() / (legacyDimensions ? 48 : 44)); i > 0; i--) {
                Auxiliary auxiliary = new Auxiliary(CloudworksGuiCodec.uuid(b), CloudworksGuiCodec.readStack(b),
                        coordinate(b), coordinate(b));
                if (legacyDimensions) b.readInt();
                auxiliary.range = b.readInt();
                auxiliary.active = b.readBoolean();
                if (auxiliary.range < 0 || auxiliary.range > 1_000_000)
                    throw new IllegalArgumentException("Invalid auxiliary range");
                for (int target = count(b, 4096); target > 0; target--)
                    auxiliary.targets.add(CloudworksGuiCodec.uuid(b));
                for (int resource = count(b, 4096); resource > 0; resource--) {
                    Resource value = new Resource(resource(b), b.readLong());
                    if (value.amount <= 0) throw new IllegalArgumentException("Invalid auxiliary resource");
                    auxiliary.resources.add(value);
                }
                draft.auxiliaries.add(auxiliary);
            }
        }
        for (int i = count(b, b.readableBytes() / 27); i > 0; i--) {
            UUID id = CloudworksGuiCodec.uuid(b);
            int ordinal = b.readVarInt();
            if (ordinal < 0 || ordinal >= CloudworksChannelNode.Mode.values().length)
                throw new IllegalArgumentException("Invalid channel");
            String archetype = specialNodes ? b.readString(128) : "";
            if (!archetype.isEmpty() && !CloudworksSpecialNodeArchetypes.known(archetype))
                throw new IllegalArgumentException("Unknown special node");
            Channel channel = new Channel(id, CloudworksChannelNode.Mode.values()[ordinal], archetype,
                    coordinate(b), coordinate(b));
            channel.active = b.readBoolean();
            channel.waiting = optionalUuid(b);
            // Bound decoding by bytes actually present, not a gameplay limit on resource types.
            for (int j = count(b, b.readableBytes() / Long.BYTES); j > 0; j--) {
                Resource resource = new Resource(resource(b), b.readLong());
                if (resource.amount <= 0) throw new IllegalArgumentException("Invalid resource amount");
                channel.resources.add(resource);
            }
            if (channelConfig && b.readBoolean()) {
                NBTTagCompound config = PacketHandler.readNBT(b);
                if (config == null || config.toString().length() > CloudworksChannelNode.MAX_CONFIG_CHARS)
                    throw new IllegalArgumentException("Invalid special node configuration");
                channel.config = config;
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
            Set<QIOResourceDescriptor> unique = new HashSet<>();
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
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
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
        return decode(bytes, true);
    }

    private static CloudworksLineDraft decode(byte[] bytes, boolean recipeTypes) {
        return decodeDraft(bytes, recipeTypes, true, false, false, true, true, true, true, true, false);
    }

    public static CloudworksLineDraft decodeBlueprint(byte[] bytes, int schemaVersion) {
        if (bytes.length > CloudworksGuiCodec.MAX_BYTES) throw new IllegalArgumentException("Invalid draft size");
        if (schemaVersion == 1) return decodeDraft(bytes, false, false, false, false, false, false, false, false, false, true);
        if (schemaVersion == 3) return decodeDraft(bytes, true, true, false, false, false, false, false, false, false, true);
        if (schemaVersion == 4) return decodeDraft(bytes, true, true, true, false, false, false, false, false, false, true);
        if (schemaVersion == 5) return decodeDraft(bytes, true, true, true, true, false, false, false, false, false, true);
        if (schemaVersion == 6) return decodeDraft(bytes, true, true, true, false, false, false, false, false, false, true);
        if (schemaVersion == 7) return decodeDraft(bytes, true, true, true, false, true, false, false, false, false, true);
        if (schemaVersion == 8) return decodeDraft(bytes, true, true, true, false, true, true, false, false, false, true);
        if (schemaVersion == 9 || schemaVersion == 10) return decodeDraft(bytes, true, true, true, false, true, true, true, false, false, true);
        if (schemaVersion == 11) return decodeDraft(bytes, true, true, false, false, true, true, true, true, false, true);
        if (schemaVersion == 12) return decodeDraft(bytes, true, true, false, false, true, true, true, true, true, true);
        if (schemaVersion == SCHEMA_VERSION) return decode(bytes);
        throw new UnsupportedBlueprintVersionException();
    }

    private static CloudworksLineDraft decodeDraft(byte[] bytes, boolean recipeTypes, boolean specialNodes,
            boolean nodeUpgrades, boolean legacyTeAugments, boolean customUpgrades, boolean channelConfig,
            boolean mechanismHints, boolean auxiliaryNodes, boolean oreRules, boolean legacyDimensions) {
        PacketBuffer buffer = new PacketBuffer(Unpooled.wrappedBuffer(bytes));
        try {
            CloudworksLineDraft draft = readDraft(buffer, recipeTypes, specialNodes, nodeUpgrades,
                    legacyTeAugments, customUpgrades, channelConfig, mechanismHints,
                    auxiliaryNodes, oreRules, legacyDimensions);
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing draft data");
            return draft;
        } finally {
            buffer.release();
        }
    }

    public static CloudworksLineDraft copy(CloudworksLineDraft source) {
        // ItemStack machine identities are shared read-only; copy mutable collections without native API reads.
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
        result.nodeBufferMultiplier = source.nodeBufferMultiplier;
        result.markers.addAll(source.markers);
        for (Machine m : source.machines) {
            Machine next = new Machine(m.id, m.type, m.x, m.y, m.count);
            next.active = m.active;
            next.recipeType = m.recipeType;
            next.oreRules.addAll(m.oreRules);
            next.fusionInjectionRate = m.fusionInjectionRate;
            next.assignedCustom.putAll(m.assignedCustom);
            next.waiting = m.waiting;
            next.waitingMechanism = m.waitingMechanism;
            next.required = m.required;
            next.missing = m.missing;
            result.machines.add(next);
        }
        for (Auxiliary auxiliary : source.auxiliaries) {
            Auxiliary next = new Auxiliary(auxiliary.id, auxiliary.type, auxiliary.x, auxiliary.y);
            next.range = auxiliary.range;
            next.active = auxiliary.active;
            next.targets.addAll(auxiliary.targets);
            for (Resource resource : auxiliary.resources)
                next.resources.add(new Resource(resource.descriptor, resource.amount));
            result.auxiliaries.add(next);
        }
        for (Channel c : source.channels) {
            Channel next = new Channel(c.id, c.mode, c.archetypeId, c.x, c.y);
            next.active = c.active;
            next.waiting = c.waiting;
            next.config = c.config.copy();
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

    private static NBTTagCompound oreRuleTag(Machine machine) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("rules", mekanism.cloudworks.common.data.CloudworksOreRule.writeList(machine.oreRules));
        tag.setInteger("fusionInjectionRate", machine.fusionInjectionRate);
        return tag;
    }
}
