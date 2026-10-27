package mekanism.cloudworks.common.network.data.node;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.ExecutionBranch;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.Port;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.ResourceSummary;
import mekanism.cloudworks.common.data.CloudworksThermalState;

/** Channel / special-node projection: identity is the archetype id (empty = mode channel). */
public final class ChannelNode extends Node {
    public final String archetypeId;
    public final int areaHits;

    public ChannelNode(UUID id, String state, String archetypeId, int x, int z, boolean active,
            List<ResourceSummary> contents, int areaHits) {
        super(id, state, x, z, active, contents,
                Collections.<ResourceSummary>emptyList(),
                Collections.<ResourceSummary>emptyList(),
                Collections.<Port>emptyList(), null,
                Collections.<ExecutionBranch>emptyList());
        if (areaHits < 0) throw new IllegalArgumentException("Negative area hit count");
        this.archetypeId = archetypeId;
        this.areaHits = areaHits;
    }

    @Override public Kind kind() { return Kind.CHANNEL; }

    @Override public Node at(int x, int z) {
        return new ChannelNode(id, state, archetypeId, x, z, active, input, areaHits);
    }
}
