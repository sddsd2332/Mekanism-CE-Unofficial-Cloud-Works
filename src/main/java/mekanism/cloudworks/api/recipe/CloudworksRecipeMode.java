package mekanism.cloudworks.api.recipe;

import java.util.Objects;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.MachineSlotLayout;

/** One named recipe family and its data-freeze adapter. */
public final class CloudworksRecipeMode {
    private final String id;
    private final int priority;
    private final MachineSlotLayout slots;
    private final CloudworksMachineAdapter adapter;

    public CloudworksRecipeMode(String id, int priority, MachineSlotLayout slots,
            CloudworksMachineAdapter adapter) {
        if (id == null || id.isEmpty() || id.length() > 128 || !id.matches("[a-z0-9_.:-]+"))
            throw new IllegalArgumentException("Invalid recipe mode id");
        this.id = id;
        this.priority = priority;
        this.slots = slots == null ? null : slots.frozenCopy();
        this.adapter = Objects.requireNonNull(adapter);
    }

    public String id() { return id; }
    public int priority() { return priority; }
    public MachineSlotLayout slots() { return slots; }
    public CloudworksMachineAdapter adapter() { return adapter; }
}
