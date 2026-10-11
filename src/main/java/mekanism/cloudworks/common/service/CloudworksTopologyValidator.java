package mekanism.cloudworks.common.service;

import mekanism.cloudworks.common.data.CloudworksChannelNode;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.data.ProductionLineLink;
import mekanism.cloudworks.common.slot.MachineSlotLayout;

import java.util.*;

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
            if (channel.mode() == CloudworksChannelNode.Mode.RECEIVE_INPUT) receiving++;
            if (channel.mode() == CloudworksChannelNode.Mode.SEND_OUTPUT) sending++;
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
        if (receiving > 1) return Result.invalid("line has multiple cloud input channels");
        if (sending > 1) return Result.invalid("line has multiple cloud output channels");
        for (ProductionLineLink link : line.links()) {
            if (!linkIds.add(link.linkId())) return Result.invalid("duplicate link id");
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
            CloudworksChannelNode sourceChannel = channel(line, link.sourceNode());
            CloudworksChannelNode targetChannel = channel(line, link.targetNode());
            if (sourceChannel != null || targetChannel != null) {
                if (!validChannelLink(line, link, sourceChannel, targetChannel))
                    return Result.invalid("channel link roles or codecs are incompatible");
                graph.get(link.sourceNode()).add(link.targetNode());
                continue;
            }
            MachineNodeData source = find(line, link.sourceNode());
            MachineNodeData target = find(line, link.targetNode());
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
            if (sourceSlot.codecId().equals(mekanism.cloudworks.api.CloudworksThermalPlan.HEAT)) {
                if (source.capabilities().thermal() == null || target.capabilities().thermal() == null
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

    private static boolean validChannelLink(ProductionLineData line, ProductionLineLink link,
                                            CloudworksChannelNode source, CloudworksChannelNode target) {
        if (source != null && target != null) {
            if (link.ratio() != 1D || source.mode().input() != target.mode().input()
                    || source.mode().input() && (source.mode() != CloudworksChannelNode.Mode.RECEIVE_INPUT
                    || target.mode() != CloudworksChannelNode.Mode.TRANSFER_INPUT)
                    || !source.mode().input() && (source.mode() != CloudworksChannelNode.Mode.TRANSFER_OUTPUT
                    || target.mode() != CloudworksChannelNode.Mode.SEND_OUTPUT)
                    || !link.sourcePort().equals(link.targetPort())
                    || !mekanism.api.qio.resource.QIOResourceCodecRegistry.INSTANCE.isRegistered(
                    new net.minecraft.util.ResourceLocation(link.sourcePort())))
                return false;
            return link.resourceFilters().stream()
                    .allMatch(filter -> filter.getCodecId().toString().equals(link.sourcePort()));
        }
        boolean input = source != null;
        CloudworksChannelNode boundary = input ? source : target;
        if (boundary.mode().input() != input || link.ratio() != 1D) return false;
        MachineNodeData machine = find(line, input ? link.targetNode() : link.sourceNode());
        MachineSlotLayout layout = machine == null ? null : machine.capabilities().slots();
        MachineSlotLayout.Slot slot = layout == null ? null : layout.find(input ? link.targetPort() : link.sourcePort());
        if (slot == null || input && !slot.role().isInput() || !input && slot.role() != MachineSlotLayout.PortRole.OUTPUT)
            return false;
        if (!slot.codecId().toString().equals(input ? link.sourcePort() : link.targetPort())) return false;
        if (link.resourceFilters().stream().anyMatch(filter -> !slot.codecId().equals(filter.getCodecId()))) return false;
        // Only input channels require a manifest; output channels accept resources allowed by their links.
        return mekanism.api.qio.resource.QIOResourceCodecRegistry.INSTANCE.isRegistered(slot.codecId());
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
