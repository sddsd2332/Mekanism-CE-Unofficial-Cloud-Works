package mekanism.cloudworks.common.service;

import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.data.ProductionLineLink;

import java.util.*;

public final class CloudworksTopologyOrder {
    private CloudworksTopologyOrder() {
    }

    public static List<MachineNodeData> order(ProductionLineData line) {
        Map<UUID, MachineNodeData> nodes = new HashMap<>();
        Map<UUID, Integer> indegree = new HashMap<>();
        Map<UUID, List<UUID>> outgoing = new HashMap<>();
        for (MachineNodeData node : line.machines()) {
            nodes.put(node.nodeId(), node);
            indegree.put(node.nodeId(), 0);
            outgoing.put(node.nodeId(), new ArrayList<>());
        }
        for (ProductionLineLink link : line.links()) {
            if (!link.enabled() || !nodes.containsKey(link.sourceNode()) || !nodes.containsKey(link.targetNode()))
                continue;
            outgoing.get(link.sourceNode()).add(link.targetNode());
            indegree.put(link.targetNode(), indegree.get(link.targetNode()) + 1);
        }
        ArrayDeque<UUID> ready = new ArrayDeque<>();
        indegree.forEach((id, value) -> {
            if (value == 0) ready.add(id);
        });
        List<MachineNodeData> result = new ArrayList<>();
        while (!ready.isEmpty()) {
            UUID id = ready.removeFirst();
            result.add(nodes.get(id));
            for (UUID next : outgoing.get(id)) {
                int remaining = indegree.compute(next, (ignored, value) -> value - 1);
                if (remaining == 0) ready.add(next);
            }
        }
        return result.size() == nodes.size() ? result : Collections.emptyList();
    }
}
