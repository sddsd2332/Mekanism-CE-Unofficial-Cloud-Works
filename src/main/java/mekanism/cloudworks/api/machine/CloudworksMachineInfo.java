package mekanism.cloudworks.api.machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.item.ItemStack;

/** Immutable registry view for addon diagnostics and startup-time customization. */
public final class CloudworksMachineInfo {
    private final ItemStack machine;
    private final ItemStack aliasTarget;
    private final MachineExecutionProfile profile;
    private final MachineSlotLayout slots;
    private final List<String> recipeTypes;
    private final int adapterCount;
    private final boolean registered, hasEnergyGenerator;
    private final CloudworksThermalPlan thermal;
    private final CloudworksMachineCategory category;

    public CloudworksMachineInfo(ItemStack machine, ItemStack aliasTarget, MachineExecutionProfile profile,
            MachineSlotLayout slots, List<String> recipeTypes, int adapterCount, boolean registered,
            boolean hasEnergyGenerator, CloudworksThermalPlan thermal) {
        this(machine, aliasTarget, profile, slots, recipeTypes, adapterCount, registered,
                hasEnergyGenerator, thermal, null);
    }

    public CloudworksMachineInfo(ItemStack machine, ItemStack aliasTarget, MachineExecutionProfile profile,
            MachineSlotLayout slots, List<String> recipeTypes, int adapterCount, boolean registered,
            boolean hasEnergyGenerator, CloudworksThermalPlan thermal, CloudworksMachineCategory category) {
        this.machine = copy(machine);
        this.aliasTarget = copy(aliasTarget);
        this.profile = profile;
        this.slots = slots == null ? null : slots.frozenCopy();
        this.recipeTypes = Collections.unmodifiableList(new ArrayList<>(recipeTypes));
        this.adapterCount = Math.max(0, adapterCount);
        this.registered = registered;
        this.hasEnergyGenerator = hasEnergyGenerator;
        this.thermal = thermal;
        this.category = category;
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? ItemStack.EMPTY : stack.copy();
    }

    public ItemStack machine() { return copy(machine); }
    public ItemStack aliasTarget() { return copy(aliasTarget); }
    public MachineExecutionProfile profile() { return profile; }
    public MachineSlotLayout slots() { return slots == null ? null : slots.frozenCopy(); }
    public List<String> recipeTypes() { return recipeTypes; }
    public int adapterCount() { return adapterCount; }
    public boolean registered() { return registered; }
    public boolean hasEnergyGenerator() { return hasEnergyGenerator; }
    public CloudworksThermalPlan thermal() { return thermal; }
    /** Explicit addon category, or null when the core category is inferred. */
    public CloudworksMachineCategory category() { return category; }
}
