package mekanism.cloudworks.common.service;

import mekanism.cloudworks.common.data.CloudworksChannelNode;
import mekanism.cloudworks.common.data.CloudworksAuxiliaryNodeData;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.data.ProductionLineLink;
import mekanism.cloudworks.api.node.CloudworksPortDefinition;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.api.node.CloudworksAuxiliaryNodeType;
import mekanism.cloudworks.common.registry.CloudworksAuxiliaryNodeRegistry;

import java.util.*;
import mekanism.api.qio.resource.QIOResourceCodecRegistry;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetype;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetypes;

public final class CloudworksTopologyValidator {
    private CloudworksTopologyValidator() {
    }

    public static Result validate(ProductionLineData line) {
        Map<UUID, Set<UUID>> graph = new HashMap<>();
        Set<UUID> linkIds = new HashSet<>();
        Map<String, Integer> sourcePortUse = new HashMap<>();
        Map<String, Integer> targetPortUse = new HashMap<>();
        Set<UUID> machineIds = new HashSet<>();
        for (MachineNodeData node : line.machines()) {
            // P1-12: duplicate machine ids are classified, not silently merged.
            if (!machineIds.add(node.nodeId())) return Result.invalid("duplicate machine node id");
            graph.put(node.nodeId(), new HashSet<>());
        }
        int receiving = 0, sending = 0;
        for (CloudworksChannelNode channel : line.channels()) {
            if (graph.putIfAbsent(channel.id(), new HashSet<>()) != null)
                return Result.invalid(machineIds.contains(channel.id())
                        ? "channel id collides with a machine node id" : "duplicate channel id");
            if (channel.archetypeId().isEmpty() && channel.mode() == CloudworksChannelNode.Mode.RECEIVE_INPUT) receiving++;
            if (channel.archetypeId().isEmpty() && channel.mode() == CloudworksChannelNode.Mode.SEND_OUTPUT) sending++;
            if (channel.mode().input()) {
                long declared = 0;
                try {
                    for (long amount : channel.resources().values()) declared = Math.addExact(declared, amount);
                } catch (ArithmeticException overflow) {
                    return Result.invalid("channel manifest overflow");
                }
                if (declared > channel.buffer().capacity()) return Result.invalid("channel manifest exceeds buffer");
            }
        }
        for (CloudworksAuxiliaryNodeData auxiliary : line.auxiliaries()) {
            if (graph.putIfAbsent(auxiliary.nodeId(), new HashSet<>()) != null)
                return Result.invalid("duplicate auxiliary node id");
            if (CloudworksAuxiliaryNodeRegistry.byItem(auxiliary.type()) == null)
                return Result.invalid("unknown auxiliary node type");
            if (auxiliary.input().total() > auxiliary.input().capacity())
                return Result.invalid("auxiliary input exceeds capacity");
            for (UUID target : auxiliary.linkedTargets())
                if (!machineIds.contains(target))
                    return Result.invalid("auxiliary target is not a machine");
        }
        if (receiving > 1) return Result.invalid("line has multiple cloud input channels");
        if (sending > 1) return Result.invalid("line has multiple cloud output channels");
        boolean forbiddenTransferLink = false;
        for (ProductionLineLink link : line.links()) {
            if (!linkIds.add(link.linkId())) return Result.invalid("duplicate link id");
            CloudworksChannelNode sourceChannel = channel(line, link.sourceNode());
            CloudworksChannelNode targetChannel = channel(line, link.targetNode());
            CloudworksAuxiliaryNodeData sourceAuxiliary = auxiliary(line, link.sourceNode());
            CloudworksAuxiliaryNodeData targetAuxiliary = auxiliary(line, link.targetNode());
            if (!CloudworksChannelNode.Mode.allowsDirectLink(
                    sourceChannel == null || sourceChannel.special() ? null : sourceChannel.mode(),
                    targetChannel == null || targetChannel.special() ? null : targetChannel.mode()))
                forbiddenTransferLink = true;
            if (!link.enabled()) continue;
            if (link.resourceFilters().stream().anyMatch(filter -> !filter.isResolved()))
                return Result.invalid("unresolved link filter");
            // Count machine ports before branching to endpoint validation: a QIO wire
            // and a machine wire compete for the same single-connection port.
            if (!claimPort(line, link.sourceNode(), link.sourcePort(), sourcePortUse)
                    || !claimPort(line, link.targetNode(), link.targetPort(), targetPortUse))
                return Result.invalid("port does not allow multiple connections");
            if (!graph.containsKey(link.sourceNode()) || !graph.containsKey(link.targetNode())) {
                return Result.invalid("link references an unknown node");
            }
            if (sourceChannel != null || targetChannel != null || sourceAuxiliary != null || targetAuxiliary != null) {
                if (!validChannelLink(line, link, sourceChannel, targetChannel))
                    return Result.invalid("channel link roles or codecs are incompatible");
                graph.get(link.sourceNode()).add(link.targetNode());
                continue;
            }
            MachineNodeData source = find(line, link.sourceNode());
            MachineNodeData target = find(line, link.targetNode());
            // B5: links touching a frozen node stay persisted and pass structural
            // checks; new or edited ones are refused by the edit guard instead.
            if (source != null && source.frozenByMissingMod() || target != null && target.frozenByMissingMod()) {
                graph.get(link.sourceNode()).add(link.targetNode());
                continue;
            }
            MachineSlotLayout sourceLayout = source.capabilities().slots();
            MachineSlotLayout targetLayout = target.capabilities().slots();
            if (sourceLayout == null || targetLayout == null)
                return Result.invalid("machine slot layout is not registered");
            MachineSlotLayout.Slot sourceSlot = sourceLayout.find(link.sourcePort());
            MachineSlotLayout.Slot targetSlot = targetLayout.find(link.targetPort());
            if (sourceSlot == null || targetSlot == null) return Result.invalid("link references an unknown port");
            if (sourceSlot.role() != MachineSlotLayout.PortRole.OUTPUT || !targetSlot.role().isInput())
                return Result.invalid("link roles are incompatible");
            if (!sourceSlot.codecId().equals(targetSlot.codecId()))
                return Result.invalid("link resource codecs are incompatible");
            if (!Double.isFinite(link.ratio()) || link.ratio() < 0D || link.ratio() > 1D)
                return Result.invalid("link ratio is invalid");
            if (sourceSlot.codecId().equals(CloudworksThermalPlan.HEAT)) {
                if (source.capabilities().thermalPortPlan() == null || target.capabilities().thermalPortPlan() == null
                        || !link.resourceFilters().isEmpty() || source.nodeId().equals(target.nodeId())
                        || !link.sourcePort().equals("heat_output") || !link.targetPort().equals("heat_input"))
                    return Result.invalid("invalid thermal connection");
                continue;
            }
            graph.get(link.sourceNode()).add(link.targetNode());
            if (link.resourceFilters().stream().anyMatch(filter -> !sourceSlot.codecId().equals(filter.getCodecId())))
                return Result.invalid("link filter codec is incompatible");
        }
        Set<UUID> visiting = new HashSet<>();
        Set<UUID> visited = new HashSet<>();
        for (UUID node : graph.keySet()) {
            if (cycle(node, graph, visiting, visited)) return Result.invalid("production line contains a cycle");
        }
        if (forbiddenTransferLink) return Result.invalid("transfer input and transfer output cannot be linked directly");
        return Result.validResult();
    }

    private static MachineNodeData find(ProductionLineData line, UUID id) {
        for (MachineNodeData node : line.machines()) if (node.nodeId().equals(id)) return node;
        return null;
    }

    private static CloudworksChannelNode channel(ProductionLineData line, UUID id) {
        for (CloudworksChannelNode value : line.channels()) if (value.id().equals(id)) return value;
        return null;
    }

    private static CloudworksAuxiliaryNodeData auxiliary(ProductionLineData line, UUID id) {
        for (CloudworksAuxiliaryNodeData value : line.auxiliaries())
            if (value.nodeId().equals(id)) return value;
        return null;
    }

    private static boolean validChannelLink(ProductionLineData line, ProductionLineLink link,
                                            CloudworksChannelNode source, CloudworksChannelNode target) {
        CloudworksPortDefinition sourcePort = port(line, link.sourceNode(), link.sourcePort(), source);
        CloudworksPortDefinition targetPort = port(line, link.targetNode(), link.targetPort(), target);
        if (sourcePort == null || targetPort == null || !sourcePort.canOutput() || !targetPort.canInput()
                || !sourcePort.codec().equals(targetPort.codec())
                || !QIOResourceCodecRegistry.INSTANCE.isRegistered(sourcePort.codec())
                || link.resourceFilters().stream().anyMatch(filter -> !sourcePort.codec().equals(filter.getCodecId())))
            return false;
        if (source != null && !sourcePort.id().equals(link.sourcePort())) return false;
        if (target != null && !targetPort.id().equals(link.targetPort())) return false;
        return true;
    }

    private static CloudworksPortDefinition port(ProductionLineData line, UUID nodeId, String portId,
            CloudworksChannelNode channel) {
        if (channel != null) {
            try {
                // 9.1: explicit archetype ports are the whole rule surface; port ids
                // outside the declared set are invalid. Undeclared archetypes keep
                // the historical mode-derived per-codec behaviour.
                CloudworksSpecialNodeArchetype archetype =
                        CloudworksSpecialNodeArchetypes.get(channel.archetypeId());
                if (archetype != null && archetype.explicitPorts()) {
                    for (CloudworksPortDefinition port : archetype.ports())
                        if (port.id().equals(portId)) return port;
                    return null;
                }
                return channel.mode().port(portId);
            } catch (RuntimeException invalidCodec) {
                return null;
            }
        }
        MachineNodeData machine = find(line, nodeId);
        if (machine == null) {
            CloudworksAuxiliaryNodeData auxiliary = auxiliary(line, nodeId);
            if (auxiliary == null) return null;
            CloudworksAuxiliaryNodeType type =
                    CloudworksAuxiliaryNodeRegistry.byItem(auxiliary.type());
            if (type == null) return null;
            for (CloudworksPortDefinition value : type.ports())
                if (value.id().equals(portId)) return value;
            return null;
        }
        MachineSlotLayout layout = machine == null ? null : machine.capabilities().slots();
        MachineSlotLayout.Slot slot = layout == null ? null : layout.find(portId);
        return slot == null ? null : CloudworksPortDefinition.machine(slot);
    }

    private static boolean claimPort(ProductionLineData line, UUID nodeId, String portId, Map<String, Integer> uses) {
        MachineNodeData node = find(line, nodeId);
        if (node == null) return true;
        MachineSlotLayout layout = node.capabilities().slots();
        MachineSlotLayout.Slot slot = layout == null ? null : layout.find(portId);
        if (slot == null) return true; // The role/type validator reports the missing port.
        int count = uses.merge(nodeId + ":" + portId, 1, Integer::sum);
        return slot.multipleConnections() || count == 1;
    }

    private static boolean cycle(UUID node, Map<UUID, Set<UUID>> graph, Set<UUID> visiting, Set<UUID> visited) {
        if (visiting.contains(node)) return true;
        if (!visited.add(node)) return false;
        visiting.add(node);
        for (UUID next : graph.get(node)) if (cycle(next, graph, visiting, visited)) return true;
        visiting.remove(node);
        return false;
    }

    public static final class Result {
        private final boolean valid;
        private final String reason;

        private Result(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        public static Result validResult() {
            return new Result(true, "");
        }

        public static Result invalid(String reason) {
            return new Result(false, reason);
        }

        public boolean valid() {
            return valid;
        }

        public String reason() {
            return reason;
        }

        /** Stable translation key for the editor; reason remains diagnostic text for logs/tests. */
        public String errorKey() {
            switch (reason) {
                case "duplicate machine node id": return "editor_duplicate_machine_id";
                case "duplicate channel id": return "editor_duplicate_channel_id";
                case "channel id collides with a machine node id": return "editor_node_id_collision";
                case "line has multiple cloud input channels": return "editor_duplicate_line_input";
                case "line has multiple cloud output channels": return "editor_duplicate_line_output";
                case "channel manifest overflow": return "editor_manifest_overflow";
                case "channel manifest exceeds buffer": return "editor_manifest_capacity";
                case "duplicate link id": return "editor_duplicate_link";
                case "unresolved link filter": return "editor_unresolved_filter";
                case "port does not allow multiple connections": return "editor_port_connection_limit";
                case "link references an unknown node": return "editor_link_unknown_node";
                case "channel link roles or codecs are incompatible": return "editor_channel_link_incompatible";
                case "transfer input and transfer output cannot be linked directly": return "editor_transfer_channels_direct";
                case "machine slot layout is not registered": return "editor_machine_layout_missing";
                case "link references an unknown port": return "editor_link_unknown_port";
                case "link roles are incompatible": return "editor_link_role_incompatible";
                case "link resource codecs are incompatible": return "editor_link_codec_incompatible";
                case "invalid thermal connection": return "editor_thermal_link_invalid";
                case "link filter codec is incompatible": return "editor_filter_codec_incompatible";
                case "link ratio is invalid": return "editor_link_ratio_invalid";
                case "production line contains a cycle": return "editor_topology_cycle";
                default: return "invalid_link";
            }
        }
    }
}
