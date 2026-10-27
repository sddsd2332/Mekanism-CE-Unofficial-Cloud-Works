package mekanism.cloudworks.api.machine;

import java.util.Objects;
import net.minecraft.item.ItemStack;

/** Immutable registration view supplied to an addon adapter during lifecycle freeze. */
public final class CloudworksMachineRegistration {
    private final ItemStack machine;
    private final MachineExecutionProfile profile;
    private final MachineIdentityPolicy identity;
    private final MachineSlotLayout slots;
    private final String recipeType;

    public CloudworksMachineRegistration(ItemStack machine, MachineExecutionProfile profile,
            MachineIdentityPolicy identity, MachineSlotLayout slots, String recipeType) {
        if (machine == null || machine.isEmpty()) throw new IllegalArgumentException("Empty machine registration");
        this.machine = machine.copy();
        this.machine.setCount(1);
        this.profile = Objects.requireNonNull(profile);
        this.identity = Objects.requireNonNull(identity);
        this.slots = Objects.requireNonNull(slots).frozenCopy();
        this.recipeType = recipeType == null ? "" : recipeType;
    }

    public ItemStack machine() { return machine.copy(); }
    public MachineExecutionProfile profile() { return profile; }
    public MachineIdentityPolicy identity() { return identity; }
    public MachineSlotLayout slots() { return slots; }
    public String recipeType() { return recipeType; }
}
