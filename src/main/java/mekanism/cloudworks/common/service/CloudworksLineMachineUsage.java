package mekanism.cloudworks.common.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.registry.MachineKey;
import net.minecraft.item.ItemStack;

/** Read-only grouping of the current line projection; excludes special channel nodes. */
public final class CloudworksLineMachineUsage {
    private CloudworksLineMachineUsage() {}

    public static List<Type> collect(CloudworksLineDraft line) {
        if (line == null) return Collections.emptyList();
        Map<MachineKey, List<Node>> groups = new LinkedHashMap<>();
        for (CloudworksLineDraft.Machine machine : line.machines) {
            MachineKey key = CloudworksRegistries.identityKey(machine.type);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new Node(machine.id, machine.x, machine.y, machine.count));
        }
        List<Type> types = new ArrayList<>();
        groups.forEach((key, nodes) -> types.add(new Type(key, nodes)));
        return Collections.unmodifiableList(types);
    }

    public static final class Type {
        public final MachineKey key;
        public final long count;
        public final List<Node> nodes;

        private Type(MachineKey key, List<Node> nodes) {
            this.key = key;
            this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
            long total = 0;
            for (Node node : nodes) total = Math.addExact(total, node.count);
            count = total;
        }

        public ItemStack stack() { return key.displayStack(); }
    }

    public static final class Node {
        public final UUID id;
        public final int x, y;
        public final long count;

        private Node(UUID id, int x, int y, long count) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.count = count;
        }
    }
}
