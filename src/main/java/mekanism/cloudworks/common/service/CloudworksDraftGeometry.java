package mekanism.cloudworks.common.service;

import java.util.*;
import mekanism.cloudworks.common.blueprint.CloudworksBlueprint;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.api.CloudworksAreaGeometry;
import mekanism.api.qio.resource.QIOResourceCodecRegistry;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.common.network.CloudworksLineDraftCodec;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;

/** Identical geometry rules on both sides of the editor protocol. */
public final class CloudworksDraftGeometry {
    public static final int WIDTH = CloudworksAreaGeometry.NODE_WIDTH,
            GRID = CloudworksAreaGeometry.GRID;
    public static final int ENVIRONMENT_SOURCE_HEIGHT = CloudworksAreaGeometry.nodeHeight(0);
    private CloudworksDraftGeometry() {}

    public static int height(CloudworksLineDraft.Machine machine) {
        MachineSlotLayout slots =
                CloudworksNodeMechanisms.slots(machine);
        return CloudworksAreaGeometry.nodeHeight(slots == null ? 0 : slots.slots().size());
    }
    public static int channelHeight(CloudworksLineDraft.Channel channel) {
        if (CloudworksNodeEnvironment.source(channel.archetypeId)) return ENVIRONMENT_SOURCE_HEIGHT;
        Set<String> codecs = new HashSet<>();
        QIOResourceCodecRegistry.INSTANCE.getCodecs().keySet()
                .forEach(codec -> codecs.add(codec.toString()));
        channel.resources.forEach(resource -> codecs.add(resource.descriptor.getCodecId().toString()));
        return CloudworksAreaGeometry.nodeHeight(codecs.size());
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
        for (CloudworksLineDraft.Auxiliary auxiliary : draft.auxiliaries) {
            Rect rect = new Rect(auxiliary.id, auxiliary.x, auxiliary.y,
                    Math.max(ENVIRONMENT_SOURCE_HEIGHT, CloudworksAreaGeometry.nodeHeight(1)));
            (ids.contains(auxiliary.id) ? selected : rest).add(rect); present.add(auxiliary.id);
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
        for (CloudworksLineDraft.Auxiliary auxiliary : draft.auxiliaries) if (ids.contains(auxiliary.id)) {
            auxiliary.x += dx; auxiliary.y += dy;
        }
    }
    public static void remove(CloudworksLineDraft draft, Set<UUID> ids) {
        draft.machines.removeIf(machine -> ids.contains(machine.id));
        draft.channels.removeIf(channel -> ids.contains(channel.id));
        draft.auxiliaries.removeIf(auxiliary -> ids.contains(auxiliary.id));
        draft.links.removeIf(wire -> ids.contains(wire.source) || ids.contains(wire.target));
    }
    public static CloudworksLineDraft selection(CloudworksLineDraft draft, Set<UUID> ids) {
        CloudworksLineDraft result = CloudworksLineDraftCodec.copy(draft);
        result.machines.removeIf(machine -> !ids.contains(machine.id));
        result.channels.removeIf(channel -> !ids.contains(channel.id));
        result.auxiliaries.removeIf(auxiliary -> !ids.contains(auxiliary.id));
        result.links.removeIf(wire -> !ids.contains(wire.source) || !ids.contains(wire.target));
        return result;
    }
    private static final class Rect {
        final UUID id;
        final int x, y, height;
        Rect(UUID id, int x, int y, int height) { this.id = id; this.x = x; this.y = y; this.height = height; }
    }
}
