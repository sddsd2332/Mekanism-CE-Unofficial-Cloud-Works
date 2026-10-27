package mekanism.cloudworks.common.network.data.node;

import java.util.List;
import java.util.UUID;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.ExecutionBranch;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.Port;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.ResourceSummary;
import mekanism.cloudworks.common.data.CloudworksThermalState;

/** Machine node projection: identity is the registry identity key string. */
public final class MachineNode extends Node {
    public final String machineIdentity;
    public final int count, progress, duration;

    public MachineNode(UUID id, String state, String machineIdentity, int x, int z,
            int count, int progress, int duration, boolean active,
            List<ResourceSummary> input, List<ResourceSummary> output, List<ResourceSummary> templates,
            List<Port> ports, CloudworksThermalState thermal, List<ExecutionBranch> branches) {
        super(id, state, x, z, active, input, output, templates, ports, thermal, branches);
        this.machineIdentity = machineIdentity;
        this.count = count;
        this.progress = progress;
        this.duration = duration;
    }

    @Override public Kind kind() { return Kind.MACHINE; }

    @Override public Node at(int x, int z) {
        return new MachineNode(id, state, machineIdentity, x, z, count, progress, duration,
                active, input, output, templates, ports, thermal, branches);
    }
}
