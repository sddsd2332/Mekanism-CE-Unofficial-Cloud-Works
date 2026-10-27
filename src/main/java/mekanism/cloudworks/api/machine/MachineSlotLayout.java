package mekanism.cloudworks.api.machine;

import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MachineSlotLayout {
    private final List<Slot> slots = new ArrayList<>();
    private boolean frozen;

    public MachineSlotLayout add(String id, ResourceLocation codecId, PortRole role, int capacity) {
        return add(id, codecId, role, capacity, true);
    }

    public MachineSlotLayout add(String id, ResourceLocation codecId, PortRole role, int capacity, boolean multipleConnections) {
        return add(id, codecId, role, capacity, multipleConnections, role != null && role.isInput());
    }

    public MachineSlotLayout add(String id, ResourceLocation codecId, PortRole role, int capacity,
            boolean multipleConnections, boolean recipeFilteredInput) {
        if (frozen) throw new IllegalStateException("Registered machine slot layout is immutable");
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("Slot id cannot be empty");
        if (codecId == null) throw new IllegalArgumentException("Slot codec cannot be null");
        if (role == null) throw new IllegalArgumentException("Slot role cannot be null");
        if (capacity <= 0) throw new IllegalArgumentException("Slot capacity must be positive");
        if (find(id) != null) throw new IllegalArgumentException("Duplicate machine slot id: " + id);
        slots.add(new Slot(id, codecId, role, capacity, multipleConnections, recipeFilteredInput));
        return this;
    }

    /** True accepts only declared recipe inputs; false keeps a generic codec buffer. */
    public MachineSlotLayout recipeFilteredInput(String id, boolean enabled) {
        if (frozen) throw new IllegalStateException("Registered machine slot layout is immutable");
        Slot previous = find(id);
        if (previous == null || !previous.role.isInput()) throw new IllegalArgumentException("Missing input port: " + id);
        slots.set(slots.indexOf(previous), new Slot(previous.id, previous.codecId, previous.role,
                previous.capacity, previous.multipleConnections, enabled));
        return this;
    }

    public List<Slot> slots() {
        return Collections.unmodifiableList(slots);
    }

    public Slot find(String id) {
        for (Slot slot : slots) if (slot.id().equals(id)) return slot;
        return null;
    }

    /** Prevent callers from changing registered capacities without a registry version change. */
    public MachineSlotLayout frozenCopy() {
        MachineSlotLayout copy = new MachineSlotLayout();
        copy.slots.addAll(slots);
        copy.frozen = true;
        return copy;
    }

    public enum PortRole {
        INPUT, OUTPUT, CONFIGURATION;

        public boolean isInput() { return this != OUTPUT; }
    }

    public static final class Slot {
        private final String id;
        private final ResourceLocation codecId;
        private final PortRole role;
        private final int capacity;
        private final boolean multipleConnections;
        private final boolean recipeFilteredInput;

        public Slot(String id, ResourceLocation codecId, PortRole role, int capacity) {
            this(id, codecId, role, capacity, true);
        }

        public Slot(String id, ResourceLocation codecId, PortRole role, int capacity, boolean multipleConnections) {
            this(id, codecId, role, capacity, multipleConnections, role != null && role.isInput());
        }

        public Slot(String id, ResourceLocation codecId, PortRole role, int capacity,
                boolean multipleConnections, boolean recipeFilteredInput) {
            if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("Slot id cannot be empty");
            if (codecId == null) throw new IllegalArgumentException("Slot codec cannot be null");
            if (role == null) throw new IllegalArgumentException("Slot role cannot be null");
            if (capacity <= 0) throw new IllegalArgumentException("Slot capacity must be positive");
            this.id = id;
            this.codecId = codecId;
            this.role = role;
            this.capacity = capacity;
            this.multipleConnections = multipleConnections;
            this.recipeFilteredInput = role.isInput() && recipeFilteredInput;
        }

        public String id() {
            return id;
        }

        public ResourceLocation codecId() {
            return codecId;
        }

        public PortRole role() {
            return role;
        }

        public int capacity() {
            return capacity;
        }

        public boolean multipleConnections() {
            return multipleConnections;
        }

        public boolean recipeFilteredInput() { return recipeFilteredInput; }

        boolean sameDefinition(Slot other) {
            return other != null && id.equals(other.id) && codecId.equals(other.codecId)
                    && role == other.role && capacity == other.capacity
                    && multipleConnections == other.multipleConnections
                    && recipeFilteredInput == other.recipeFilteredInput;
        }
    }

    /**
     * Registration merge contract: repeated declarations for the same machine
     * may add recipes, but their slot layouts must be identical. A conflicting
     * addon declaration is rejected instead of being silently ignored.
     */
    public boolean sameDefinition(MachineSlotLayout other) {
        if (other == null || slots.size() != other.slots.size()) return false;
        for (Slot slot : slots)
            if (!slot.sameDefinition(other.find(slot.id()))) return false;
        return true;
    }
}
