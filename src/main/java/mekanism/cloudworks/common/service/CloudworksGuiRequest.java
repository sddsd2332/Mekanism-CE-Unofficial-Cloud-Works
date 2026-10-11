package mekanism.cloudworks.common.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;

/** Immutable query and response address, without a container, player or world reference. */
public final class CloudworksGuiRequest {
    final CloudworksGuiSource source;
    final int offset, cacheType, resourceOffset, resourceTotal, window;
    final UUID graphLine, resourceContext, session;
    final boolean warehouses, recycle;
    final List<QIOResourceDescriptor> choices;
    final String status;
    public final long sequence;

    public CloudworksGuiRequest(CloudworksGuiSource source, int offset, UUID graphLine, int cacheType,
            boolean warehouses, boolean recycle, List<QIOResourceDescriptor> choices,
            int resourceOffset, int resourceTotal, UUID resourceContext,
            int window, UUID session, long sequence, String status) {
        this.source = source;
        this.offset = offset;
        this.graphLine = graphLine;
        this.cacheType = cacheType;
        this.warehouses = warehouses;
        this.recycle = recycle;
        this.choices = Collections.unmodifiableList(new ArrayList<>(choices));
        this.resourceOffset = resourceOffset;
        this.resourceTotal = resourceTotal;
        this.resourceContext = resourceContext;
        this.window = window;
        this.session = Objects.requireNonNull(session);
        this.sequence = sequence;
        this.status = Objects.requireNonNull(status);
    }

    public boolean sameView(CloudworksGuiRequest other) {
        return other != null && offset == other.offset && cacheType == other.cacheType
                && warehouses == other.warehouses && recycle == other.recycle
                && resourceOffset == other.resourceOffset && resourceTotal == other.resourceTotal
                && Objects.equals(graphLine, other.graphLine) && Objects.equals(resourceContext, other.resourceContext)
                && choices.equals(other.choices);
    }

    public String status() { return status; }
}
