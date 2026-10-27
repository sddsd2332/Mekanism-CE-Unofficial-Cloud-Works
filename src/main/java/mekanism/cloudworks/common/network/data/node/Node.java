package mekanism.cloudworks.common.network.data.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.ExecutionBranch;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.Port;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.ResourceSummary;
import mekanism.cloudworks.common.data.CloudworksThermalState;

/**
 * Base projection of one line node. Identity is a client fact resolved from the
 * frozen registries: icons, display names and port layouts derive locally from
 * the concrete subclass's identity fields. Only runtime state crosses the wire.
 */
public abstract class Node {
    public final UUID id;
    public final String state;
    public final int x, z;
    public final boolean active;
    public final List<ResourceSummary> input, output, templates;
    public final List<Port> ports;
    public final CloudworksThermalState thermal;
    public final List<ExecutionBranch> branches;

    Node(UUID id, String state, int x, int z, boolean active,
            List<ResourceSummary> input, List<ResourceSummary> output, List<ResourceSummary> templates,
            List<Port> ports, CloudworksThermalState thermal, List<ExecutionBranch> branches) {
        this.id = id;
        this.state = state;
        this.x = x;
        this.z = z;
        this.active = active;
        this.input = safe(input);
        this.output = safe(output);
        this.templates = safe(templates);
        this.ports = safe(ports);
        this.thermal = thermal;
        this.branches = safe(branches);
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    /** Wire discriminator; one per concrete subclass. */
    public enum Kind { MACHINE, AUXILIARY, CHANNEL }

    public abstract Kind kind();

    /** Copy of this projection at another canvas position; all other facts are shared. */
    public abstract Node at(int x, int z);
}
