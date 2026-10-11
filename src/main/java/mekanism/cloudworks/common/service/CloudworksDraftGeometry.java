package mekanism.cloudworks.common.service;

import java.util.*;
import mekanism.cloudworks.common.blueprint.CloudworksBlueprint;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.registry.CloudworksRegistries;

/** Identical geometry rules on both sides of the editor protocol. */
public final class CloudworksDraftGeometry {
    public static final int WIDTH = 132, GRID = 24;
    private CloudworksDraftGeometry() {}

    public static int height(CloudworksLineDraft.Machine machine) {
        mekanism.cloudworks.common.slot.MachineSlotLayout slots = CloudworksRegistries.slots(machine.type);
        return 57 + Math.max(1, slots == null ? 0 : slots.slots().size()) * 12;
    }
    public static int channelHeight(CloudworksLineDraft.Channel channel) {
        Set<String> codecs = new HashSet<>();
        mekanism.api.qio.resource.QIOResourceCodecRegistry.INSTANCE.getCodecs().keySet()
                .forEach(codec -> codecs.add(codec.toString()));
        channel.resources.forEach(resource -> codecs.add(resource.descriptor.getCodecId().toString()));
        return 57 + Math.max(1, codecs.size()) * 12;
    }
    public static int snap(double position) { return (int) Math.round(position / GRID) * GRID; }
    public static boolean canMove(CloudworksLineDraft draft, Set<UUID> ids, int dx, int dy) {
        Set<UUID> present = new HashSet<>();
        List<Rect> selected = new ArrayList<>(), rest = new ArrayList<>();
        for (CloudworksLineDraft.Machine machine : draft.machines) {
            Rect rect = new Rect(machine.id, machine.x, machine.y, height(machine));
            (ids.contains(machine.id) ? selected : rest).add(rect); present.add(machine.id);
        }
        for (CloudworksLineDraft.Channel channel : draft.channels) {
            Rect rect = new Rect(channel.id, channel.x, channel.y, channelHeight(channel));
            (ids.contains(channel.id) ? selected : rest).add(rect); present.add(channel.id);
        }
        if (ids.isEmpty() || !present.containsAll(ids)) return false;
        for (Rect moved : selected) {
            long x = (long) moved.x + dx, y = (long) moved.y + dy;
            if (Math.abs(x) > 1_000_000 || Math.abs(y) > 1_000_000) return false;
            for (Rect other : rest)
                if (x < other.x + WIDTH && x + WIDTH > other.x
                        && y < other.y + other.height && y + moved.height > other.y) return false;
        }
        return true;
    }
    public static void move(CloudworksLineDraft draft, Set<UUID> ids, int dx, int dy) {
        if (!canMove(draft, ids, dx, dy)) throw new IllegalStateException("cloudworks.error.node_collision");
        for (CloudworksLineDraft.Machine machine : draft.machines) if (ids.contains(machine.id)) {
            machine.x += dx; machine.y += dy;
        }
        for (CloudworksLineDraft.Channel channel : draft.channels) if (ids.contains(channel.id)) {
            channel.x += dx; channel.y += dy;
        }
    }
    public static void remove(CloudworksLineDraft draft, Set<UUID> ids) {
        draft.machines.removeIf(machine -> ids.contains(machine.id));
        draft.channels.removeIf(channel -> ids.contains(channel.id));
        draft.links.removeIf(wire -> ids.contains(wire.source) || ids.contains(wire.target));
    }
    public static CloudworksLineDraft selection(CloudworksLineDraft draft, Set<UUID> ids) {
        CloudworksLineDraft result = mekanism.cloudworks.common.network.CloudworksLineDraftCodec.copy(draft);
        result.machines.removeIf(machine -> !ids.contains(machine.id));
        result.channels.removeIf(channel -> !ids.contains(channel.id));
        result.links.removeIf(wire -> !ids.contains(wire.source) || !ids.contains(wire.target));
        return result;
    }
    private static final class Rect {
        final UUID id;
        final int x, y, height;
        Rect(UUID id, int x, int y, int height) { this.id = id; this.x = x; this.y = y; this.height = height; }
    }
}
