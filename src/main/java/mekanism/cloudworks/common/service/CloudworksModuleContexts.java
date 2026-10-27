package mekanism.cloudworks.common.service;

import java.util.*;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.api.module.MachineContextSnapshot;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.data.ProductionLineLink;

/** Capture-scoped immutable machine overview and adjacency; never a cache of a mutable line. */
public final class CloudworksModuleContexts {
    private final ProductionLineData line;
    private final MachineContextSnapshot.MachineNodes machines;
    private final Map<UUID, List<MachineContextSnapshot.Link>> incident = new HashMap<>();

    private CloudworksModuleContexts(ProductionLineData line) {
        this.line = line;
        List<MachineContextSnapshot.MachineNode> nodes = new ArrayList<>();
        if (line != null) {
            for (MachineNodeData node : line.machines())
                nodes.add(new MachineContextSnapshot.MachineNode(node.nodeId(), node.machine(),
                        node.active(), node.machineCount()));
            for (ProductionLineLink link : line.links()) {
                MachineContextSnapshot.Link snapshot = new MachineContextSnapshot.Link(link.linkId(),
                        link.sourceNode(), link.sourcePort(), link.targetNode(), link.targetPort(),
                        link.enabled(), link.ratio());
                incident.computeIfAbsent(link.sourceNode(), key -> new ArrayList<>()).add(snapshot);
                if (!link.sourceNode().equals(link.targetNode()))
                    incident.computeIfAbsent(link.targetNode(), key -> new ArrayList<>()).add(snapshot);
            }
        }
        machines = new MachineContextSnapshot.MachineNodes(nodes);
        incident.replaceAll((id, links) -> Collections.unmodifiableList(links));
    }

    public static CloudworksModuleContexts capture(ProductionLineData line) {
        return new CloudworksModuleContexts(line);
    }

    public MachineContextSnapshot forMachine(MachineNodeData machine, CloudworksGenerationContext environment) {
        if (line == null || machine == null) return MachineContextSnapshot.empty(environment);
        return CloudworksModuleBinding.context(line, machine, environment, machines,
                incident.getOrDefault(machine.nodeId(), Collections.emptyList()));
    }
}
