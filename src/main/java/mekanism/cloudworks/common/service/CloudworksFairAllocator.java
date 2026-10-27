package mekanism.cloudworks.common.service;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksInventory;

import java.util.*;

/**
 * Round-robin whole-batch claims against a shared cloud inventory.
 */
public final class CloudworksFairAllocator {
    private CloudworksFairAllocator() {
    }

    public static Result allocate(CloudworksInventory inventory, List<Demand> demands, int cursor) {
        if (inventory == null || demands == null) throw new IllegalArgumentException("Missing allocation data");
        if (demands.isEmpty()) return new Result(Collections.emptyList(), 0);
        int start = Math.floorMod(cursor, demands.size());
        List<UUID> granted = new ArrayList<>();
        Set<QIOResourceDescriptor> blocked = new HashSet<>();
        int next = (start + 1) % demands.size();
        boolean waiting = false;
        for (int offset = 0; offset < demands.size(); offset++) {
            int index = (start + offset) % demands.size();
            Demand demand = demands.get(index);
            if (demand.resources().isEmpty() || !Collections.disjoint(blocked, demand.resources().keySet())) continue;
            if (inventory.takeBatch(demand.resources())) {
                granted.add(demand.lineId());
                if (!waiting) next = (index + 1) % demands.size();
            } else {
                blocked.addAll(demand.resources().keySet());
                if (!waiting) {
                    next = index;
                    waiting = true;
                }
            }
        }
        return new Result(granted, next);
    }

    public static final class Demand {
        private final UUID lineId;
        private final Map<QIOResourceDescriptor, Long> resources;

        public Demand(UUID lineId, Map<QIOResourceDescriptor, Long> resources) {
            if (lineId == null || resources == null) throw new IllegalArgumentException("Invalid demand");
            this.lineId = lineId;
            this.resources = Collections.unmodifiableMap(new LinkedHashMap<>(resources));
        }

        public UUID lineId() {
            return lineId;
        }

        public Map<QIOResourceDescriptor, Long> resources() {
            return resources;
        }
    }

    public static final class Result {
        private final List<UUID> granted;
        private final int nextCursor;

        private Result(List<UUID> granted, int nextCursor) {
            this.granted = Collections.unmodifiableList(new ArrayList<>(granted));
            this.nextCursor = nextCursor;
        }

        public List<UUID> granted() {
            return granted;
        }

        public int nextCursor() {
            return nextCursor;
        }
    }
}
