package mekanism.cloudworks.api.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;

/** Immutable routing snapshot, separate from machine and resource-boundary node views. */
public final class CloudworksClientLinkView {
    private final UUID id, line, source, target;
    private final String sourcePort, targetPort;
    private final boolean enabled, editing;
    private final int priority;
    private final double ratio;
    private final List<QIOResourceDescriptor> filters;

    public CloudworksClientLinkView(UUID id, UUID line, UUID source, String sourcePort,
            UUID target, String targetPort, boolean enabled, boolean editing, int priority,
            double ratio, List<QIOResourceDescriptor> filters) {
        this.id = Objects.requireNonNull(id);
        this.line = Objects.requireNonNull(line);
        this.source = Objects.requireNonNull(source);
        this.target = Objects.requireNonNull(target);
        this.sourcePort = Objects.requireNonNull(sourcePort);
        this.targetPort = Objects.requireNonNull(targetPort);
        this.enabled = enabled;
        this.editing = editing;
        this.priority = priority;
        if (!Double.isFinite(ratio) || ratio < 0 || ratio > 1)
            throw new IllegalArgumentException("Invalid link ratio");
        this.ratio = ratio;
        List<QIOResourceDescriptor> copy = new ArrayList<>(Objects.requireNonNull(filters));
        for (QIOResourceDescriptor resource : copy) Objects.requireNonNull(resource);
        this.filters = Collections.unmodifiableList(copy);
    }

    public UUID id() { return id; }
    public UUID line() { return line; }
    public UUID sourceNode() { return source; }
    public UUID targetNode() { return target; }
    public String sourcePort() { return sourcePort; }
    public String targetPort() { return targetPort; }
    public boolean enabled() { return enabled; }
    public boolean editing() { return editing; }
    public int priority() { return priority; }
    public double ratio() { return ratio; }
    public List<QIOResourceDescriptor> filters() { return filters; }
}
