package mekanism.cloudworks.common.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksChannelNode;
import mekanism.cloudworks.common.data.CloudworksData;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.data.ProductionLineLink;
import java.util.Comparator;
import java.util.concurrent.CancellationException;
import java.util.Objects;

/** Server resource choices from the same frozen source used by the terminal projection. */
public final class CloudworksResourceQuery {
    private final CloudworksGuiSource source;
    private final String query;
    private final int requestedOffset;
    public final UUID line, context;

    public CloudworksResourceQuery(CloudworksGuiSource source, UUID line, UUID context, String query, int offset) {
        this.source = Objects.requireNonNull(source);
        this.line = Objects.requireNonNull(line);
        this.context = context;
        this.query = Objects.requireNonNull(query).toLowerCase(Locale.ROOT);
        requestedOffset = Math.max(0, offset);
    }

    Result execute() {
        ProductionLineData data = source.line(line);
        if (data == null) throw new IllegalArgumentException("cloudworks.error.not_found");
        Set<QIOResourceDescriptor> candidates = new LinkedHashSet<>();
        for (CloudworksChannelNode channel : data.channels()) {
            candidates.addAll(channel.resources().keySet());
            candidates.addAll(channel.buffer().snapshot().keySet());
        }
        candidates.addAll(CloudworksLineConfiguration.markers(data));
        CloudworksData cloud = source.cloud(true);
        candidates.addAll(cloud.receiving().snapshot().keySet());
        candidates.addAll(cloud.transfer().snapshot().keySet());
        candidates.addAll(cloud.sending().snapshot().keySet());
        for (MachineNodeData machine : data.machines()) {
            for (CloudworksResourceStack stack : machine.inputs()) candidates.add(stack.descriptor());
            for (CloudworksResourceStack stack : machine.outputs()) candidates.add(stack.descriptor());
            for (CloudworksResourceStack stack : machine.templates()) candidates.add(stack.descriptor());
        }
        for (ProductionLineLink link : data.links())
            candidates.addAll(link.resourceFilters());
        List<QIOResourceDescriptor> matching = new ArrayList<>();
        for (QIOResourceDescriptor resource : candidates) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (resource.isResolved() && (query.isEmpty()
                    || CloudworksEditorData.resourceName(resource).toLowerCase(Locale.ROOT).contains(query)
                    || resource.getCodecId().toString().toLowerCase(Locale.ROOT).contains(query))) matching.add(resource);
        }
        matching.sort(Comparator.naturalOrder());
        int offset = Math.min(requestedOffset, Math.max(0, matching.size() - 1)) / 64 * 64;
        return new Result(matching.subList(offset, Math.min(matching.size(), offset + 64)), offset, matching.size());
    }

    public static final class Result {
        public final List<QIOResourceDescriptor> resources;
        public final int offset, total;

        private Result(List<QIOResourceDescriptor> resources, int offset, int total) {
            this.resources = Collections.unmodifiableList(new ArrayList<>(resources));
            this.offset = offset;
            this.total = total;
        }
    }
}
