package mekanism.cloudworks.common.network.data.node;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.Port;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.ResourceSummary;

/** Auxiliary node projection: identity is the registered auxiliary item key. */
public final class AuxiliaryNode extends Node {
    public final String auxiliaryItem;

    public AuxiliaryNode(UUID id, String state, String auxiliaryItem, int x, int z, boolean active,
            List<ResourceSummary> input, List<Port> ports) {
        super(id, state, x, z, active, input,
                Collections.<ResourceSummary>emptyList(),
                Collections.<ResourceSummary>emptyList(), ports, null,
                Collections.emptyList());
        this.auxiliaryItem = auxiliaryItem;
    }

    @Override public Kind kind() { return Kind.AUXILIARY; }

    @Override public Node at(int x, int z) {
        return new AuxiliaryNode(id, state, auxiliaryItem, x, z, active, input, ports);
    }
}
