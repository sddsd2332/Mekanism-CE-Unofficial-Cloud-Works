package mekanism.cloudworks.common.blueprint;

import io.netty.buffer.Unpooled;
import java.util.*;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.*;
import mekanism.cloudworks.common.network.CloudworksGuiCodec;
import mekanism.cloudworks.common.network.CloudworksLineDraftCodec;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.service.CloudworksTopologyValidator;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import mekanism.cloudworks.common.config.CloudworksConfig;
import mekanism.cloudworks.common.registry.CloudworksAuxiliaryNodeRegistry;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetype;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetypes;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeLimits;
import mekanism.common.Upgrade;
import net.minecraft.util.ResourceLocation;

/** Immutable share payload. Node upgrades are demands, never inventory or an ownership grant. */
public final class CloudworksBlueprint {
    private static final int DRAFT_SCHEMA_VERSION = CloudworksLineDraftCodec.SCHEMA_VERSION;
    private static final UUID NO_LINE = new UUID(0, 0);
    public final String exporterName;
    public final UUID exporterUUID;
    public final long exportTime;
    private final CloudworksLineDraft group;
    /** Server-side extension carrier (verbatim container); never enters the network share bytes. */
    private NBTTagCompound extensions = new NBTTagCompound();

    public CloudworksBlueprint(String name, UUID exporter, long time, CloudworksLineDraft structure) {
        exporterName = name;
        exporterUUID = exporter;
        exportTime = time;
        group = CloudworksLineDraftCodec.copy(structure);
        group.line = NO_LINE;
        group.speed = group.energy = group.maxSpeed = group.maxEnergy = 0;
        group.enabled = group.runWhileOwnerOffline = false;
        group.maxNodes = 1;
        group.maxMachinesPerNode = 1;
        group.nodeBufferMultiplier = 1;
        for (CloudworksLineDraft.Machine machine : group.machines) {
            if (machine.waiting != null) machine.count = machine.required;
            machine.waiting = null;
            machine.waitingMechanism = "";
            machine.required = machine.missing = 0;
        }
        for (CloudworksLineDraft.Channel channel : group.channels) {
            channel.waiting = null;
            if (channel.archetypeId == null) channel.archetypeId = "";
            if (!channel.archetypeId.isEmpty())
                for (CloudworksLineDraft.Resource resource : channel.resources) resource.amount = 1;
        }
    }

    public CloudworksLineDraft draft() { return CloudworksLineDraftCodec.copy(group); }

    /** Returns a copy carrying the source line's extensions (server-side only; library NBT, not wire bytes). */
    public CloudworksBlueprint withExtensions(CloudworksExtensionSet source) {
        CloudworksBlueprint copy = new CloudworksBlueprint(exporterName, exporterUUID, exportTime, group);
        NBTTagCompound carrier = source.container();
        if (carrier.toString().length() <= 8 * CloudworksExtensionSet.MAX_TOTAL_BYTES)
            copy.extensions = carrier;
        return copy;
    }

    /** Verbatim extension carrier copy; empty when the blueprint carries none. */
    public NBTTagCompound extensionsCarrier() {
        return extensions.copy();
    }

    public void validate() {
        if (exporterUUID == null || exporterName == null || exporterName.trim().isEmpty()
                || exporterName.length() > 64 || exporterName.chars().anyMatch(c -> Character.isISOControl(c)) || exportTime < 0
                || exportTime > System.currentTimeMillis() + 86_400_000L
                || group.name.trim().isEmpty() || group.name.length() > 64 || group.name.chars().anyMatch(c -> Character.isISOControl(c))
                || group.machines.isEmpty() && group.channels.isEmpty() && group.auxiliaries.isEmpty())
            throw invalid();
        Set<UUID> ids = new HashSet<>();
        ProductionLineData topology = new ProductionLineData(NO_LINE, exporterUUID);
        long maximum = CloudworksConfig.settings().maxMachinesPerNode;
        for (CloudworksLineDraft.Machine value : group.machines) {
            if (value.id == null || !ids.add(value.id) || value.count <= 0 || value.count > maximum
                    || value.waiting != null || !CloudworksRegistries.isRegistered(value.type)
                    || !CloudworksRegistries.acceptsRecipeType(value.type, value.recipeType)) throw invalid();
            coordinate(value.x); coordinate(value.y);
            MachineNodeData node = new MachineNodeData(value.id, value.type, value.x, value.y);
            node.setMachineCount(value.count);
            node.setRecipeType(value.recipeType);
            node.setOreRules(value.oreRules);
            node.setFusionInjectionRate(value.fusionInjectionRate);
            CloudworksNodeMechanisms.applyAssignments(value, node);
            for (Map.Entry<String, Long> entry : value.assignedCustom.entrySet())
                if (entry.getValue() <= 0 || entry.getValue() > CloudworksUpgradeLimits
                        .draftCustomCapacity(value, new ResourceLocation(entry.getKey())))
                    throw invalid();
            topology.machines().add(node);
        }
        for (CloudworksLineDraft.Channel value : group.channels) {
            if (value.id == null || !ids.add(value.id) || value.waiting != null || value.mode == null) throw invalid();
            if (value.archetypeId == null) value.archetypeId = "";
            if (!value.archetypeId.isEmpty()) {
                CloudworksSpecialNodeArchetype archetype =
                        CloudworksSpecialNodeArchetypes.get(value.archetypeId);
                if (archetype == null || archetype.source() != value.mode.input()) throw invalid();
                // 9.1 deepening: blueprint validation runs the declared config validator.
                try {
                    archetype.validateConfig(value.config);
                } catch (RuntimeException refused) {
                    throw invalid();
                }
                if (!archetype.requiresResourceSelection()) {
                    if (!value.resources.isEmpty()) throw invalid();
                } else if (value.resources.size() != 1
                        || value.resources.get(0).descriptor == null
                        || !value.resources.get(0).descriptor.isResolved()
                        || value.resources.get(0).amount <= 0) throw invalid();
            }
            coordinate(value.x); coordinate(value.y);
            CloudworksChannelNode node = new CloudworksChannelNode(
                    value.id, value.mode, Long.MAX_VALUE, value.archetypeId);
            Set<QIOResourceDescriptor> resources = new HashSet<>();
            for (CloudworksLineDraft.Resource resource : value.resources) {
                if (resource.descriptor == null || !resource.descriptor.isResolved()
                        || !resources.add(resource.descriptor) || resource.amount <= 0) throw invalid();
                node.setResource(resource.descriptor, resource.amount);
            }
            topology.channels().add(node);
        }
        for (CloudworksLineDraft.Auxiliary value : group.auxiliaries) {
            if (value.id == null || !ids.add(value.id)
                    || CloudworksAuxiliaryNodeRegistry.byItem(value.type) == null)
                throw invalid();
            coordinate(value.x); coordinate(value.y);
            if (value.range < 0 || value.range > 1_000_000) throw invalid();
            CloudworksAuxiliaryNodeData node =
                    new CloudworksAuxiliaryNodeData(
                            value.id, value.type, value.x, value.y);
            node.setRange(value.range);
            node.setActive(value.active);
            node.setLinkedTargets(value.targets);
            Set<QIOResourceDescriptor> resources = new HashSet<>();
            for (CloudworksLineDraft.Resource resource : value.resources) {
                if (resource.descriptor == null || !resource.descriptor.isResolved()
                        || resource.amount <= 0 || !resources.add(resource.descriptor)) throw invalid();
                node.input().insertIgnoringTypeCap(resource.descriptor, resource.amount);
            }
            topology.auxiliaries().add(node);
        }
        Set<UUID> wires = new HashSet<>();
        Set<String> endpoints = new HashSet<>();
        for (CloudworksLineDraft.Wire value : group.links) {
            if (value.id == null || !wires.add(value.id) || !ids.contains(value.source) || !ids.contains(value.target)
                    || value.sourcePort == null || value.targetPort == null
                    || !endpoints.add(value.source + "|" + value.sourcePort + "|" + value.target + "|" + value.targetPort)
                    || !Double.isFinite(value.ratio) || value.ratio < 0 || value.ratio > 1
                    || new HashSet<>(value.filters).size() != value.filters.size()) throw invalid();
            ProductionLineLink link = new ProductionLineLink(value.id, value.source, value.sourcePort,
                    value.target, value.targetPort);
            link.setEnabled(true); // Disabled wires must still reference valid ports.
            link.setRatio(value.ratio);
            link.setResourceFilters(value.filters);
            topology.links().add(link);
        }
        if (group.markers.size() > 25 || new HashSet<>(group.markers).size() != group.markers.size()
                || group.markers.stream().anyMatch(resource -> resource == null || !resource.isResolved())
                || !CloudworksTopologyValidator.validate(topology).valid()) throw invalid();
    }

    public static void coordinate(int value) {
        if (Math.abs((long) value) > 1_000_000) throw invalid();
    }

    public static IllegalArgumentException invalid() {
        return new IllegalArgumentException("cloudworks.error.blueprint_invalid");
    }

    public byte[] bytes() {
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        try {
            buffer.writeString(exporterName);
            CloudworksGuiCodec.uuid(buffer, exporterUUID);
            buffer.writeLong(exportTime);
            CloudworksLineDraftCodec.write(buffer, group);
            if (buffer.readableBytes() > CloudworksGuiCodec.MAX_BYTES - 1024) throw invalid();
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally { buffer.release(); }
    }

    public static CloudworksBlueprint fromBytes(byte[] bytes) {
        if (bytes.length > CloudworksGuiCodec.MAX_BYTES - 1024) throw invalid();
        PacketBuffer buffer = new PacketBuffer(Unpooled.wrappedBuffer(bytes));
        try {
            String name = buffer.readString(64);
            UUID id = CloudworksGuiCodec.uuid(buffer);
            long time = buffer.readLong();
            CloudworksLineDraft group = CloudworksLineDraftCodec.read(buffer);
            if (buffer.isReadable() || !NO_LINE.equals(group.line) || group.speed != 0 || group.energy != 0
                    || group.maxSpeed != 0 || group.maxEnergy != 0 || group.enabled || group.runWhileOwnerOffline
                || group.maxNodes != 1 || group.maxMachinesPerNode != 1 || group.nodeBufferMultiplier != 1
                    || group.machines.stream().anyMatch(machine -> machine.waiting != null)
                    || group.channels.stream().anyMatch(channel -> channel.waiting != null)) throw invalid();
            CloudworksBlueprint result = new CloudworksBlueprint(name, id, time, group);
            result.validate();
            return result;
        } finally { buffer.release(); }
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("exporterName", exporterName);
        tag.setString("exporterUUID", exporterUUID.toString());
        tag.setLong("exportTime", exportTime);
        tag.setInteger("structureVersion", DRAFT_SCHEMA_VERSION);
        tag.setByteArray("structure", CloudworksLineDraftCodec.encode(group));
        if (!extensions.getKeySet().isEmpty()) tag.setTag("extensions", extensions.copy());
        return tag;
    }

    public static CloudworksBlueprint read(NBTTagCompound tag) {
        if (!tag.hasKey("exporterName", 8) || !tag.hasKey("exporterUUID", 8)
                || !tag.hasKey("exportTime", 4) || !tag.hasKey("structure", 7)
                || tag.hasKey("structureVersion") && !tag.hasKey("structureVersion", 3)) throw invalid();
        int structureVersion = tag.hasKey("structureVersion", 3) ? tag.getInteger("structureVersion") : 0;
        CloudworksLineDraft group = CloudworksLineDraftCodec.decodeBlueprint(
                tag.getByteArray("structure"), structureVersion);
        if (!NO_LINE.equals(group.line) || group.speed != 0 || group.energy != 0
                || group.maxSpeed != 0 || group.maxEnergy != 0 || group.enabled || group.runWhileOwnerOffline
                || group.maxNodes != 1 || group.maxMachinesPerNode != 1 || group.nodeBufferMultiplier != 1
                || group.machines.stream().anyMatch(machine -> machine.waiting != null)
                || group.channels.stream().anyMatch(channel -> channel.waiting != null)
                || !UUID.fromString(tag.getString("exporterUUID")).toString()
                        .equalsIgnoreCase(tag.getString("exporterUUID"))) throw invalid();
        CloudworksBlueprint result = new CloudworksBlueprint(tag.getString("exporterName"),
                UUID.fromString(tag.getString("exporterUUID")), tag.getLong("exportTime"), group);
        if (tag.hasKey("extensions", 10)) {
            NBTTagCompound stored = tag.getCompoundTag("extensions");
            // Loose carrier cap; per-entry bounds are enforced at the placement merge.
            if (stored.toString().length() <= 8 * CloudworksExtensionSet.MAX_TOTAL_BYTES)
                result.extensions = stored.copy();
        }
        result.validate();
        return result;
    }

    public CloudworksLineDraft remap(UUID line, int anchorX, int anchorY) {
        CloudworksLineDraft result = draft();
        int minX = minimumX(result), minY = minimumY(result);
        Map<UUID, UUID> ids = new HashMap<>();
        for (CloudworksLineDraft.Machine node : result.machines) {
            UUID id = UUID.randomUUID(); ids.put(node.id, id); node.id = id;
            node.x = Math.addExact(anchorX, Math.subtractExact(node.x, minX));
            node.y = Math.addExact(anchorY, Math.subtractExact(node.y, minY));
            coordinate(node.x); coordinate(node.y);
        }
        for (CloudworksLineDraft.Channel node : result.channels) {
            UUID id = UUID.randomUUID(); ids.put(node.id, id); node.id = id;
            node.x = Math.addExact(anchorX, Math.subtractExact(node.x, minX));
            node.y = Math.addExact(anchorY, Math.subtractExact(node.y, minY));
            coordinate(node.x); coordinate(node.y);
        }
        for (CloudworksLineDraft.Wire wire : result.links) {
            wire.id = UUID.randomUUID();
            wire.source = ids.get(wire.source); wire.target = ids.get(wire.target);
        }
        result.line = line;
        return result;
    }

    public static int minimumX(CloudworksLineDraft draft) {
        return Math.min(Math.min(draft.machines.stream().mapToInt(m -> m.x).min().orElse(Integer.MAX_VALUE),
                        draft.channels.stream().mapToInt(m -> m.x).min().orElse(Integer.MAX_VALUE)),
                draft.auxiliaries.stream().mapToInt(m -> m.x).min().orElse(Integer.MAX_VALUE));
    }

    public static int minimumY(CloudworksLineDraft draft) {
        return Math.min(Math.min(draft.machines.stream().mapToInt(m -> m.y).min().orElse(Integer.MAX_VALUE),
                        draft.channels.stream().mapToInt(m -> m.y).min().orElse(Integer.MAX_VALUE)),
                draft.auxiliaries.stream().mapToInt(m -> m.y).min().orElse(Integer.MAX_VALUE));
    }
}
