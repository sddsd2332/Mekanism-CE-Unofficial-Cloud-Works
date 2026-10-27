package mekanism.cloudworks.api.machine;

import java.util.Objects;
import net.minecraft.item.ItemStack;
import mekanism.cloudworks.api.module.MachineModuleType;

/** Deferred, typed mutation of a registered machine, applied before registry freeze. */
public final class CloudworksMachineMutation {
    public enum Kind { ALIAS, PROFILE, TEMPLATE, ADD_ADAPTER, CLEAR_ADAPTERS, GENERATOR, THERMAL, ACCEPT_UPGRADE }

    public final Kind kind;
    private final ItemStack machine, target;
    private final MachineExecutionProfile profile;
    private final CloudworksMachineAdapter adapter;
    private final CloudworksEnergyGenerator generator;
    private final CloudworksThermalPlan thermal;
    private final MachineModuleType upgrade;
    private final int upgradeLimit;

    public CloudworksMachineMutation(Kind kind, ItemStack machine, ItemStack target, MachineExecutionProfile profile,
            CloudworksMachineAdapter adapter, CloudworksEnergyGenerator generator, CloudworksThermalPlan thermal) {
        this(kind, machine, target, profile, adapter, generator, thermal, null, 0);
    }

    public CloudworksMachineMutation(Kind kind, ItemStack machine, ItemStack target, MachineExecutionProfile profile,
                              CloudworksMachineAdapter adapter, CloudworksEnergyGenerator generator, CloudworksThermalPlan thermal,
                              MachineModuleType upgrade, int upgradeLimit) {
        this.kind = Objects.requireNonNull(kind);
        this.machine = single(machine);
        this.target = target == null ? ItemStack.EMPTY : single(target);
        this.profile = profile;
        this.adapter = adapter;
        this.generator = generator;
        this.thermal = thermal;
        this.upgrade = upgrade;
        this.upgradeLimit = upgradeLimit;
    }

    private static ItemStack single(ItemStack stack) {
        if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("Empty machine identity");
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }

    public ItemStack machine() { return machine.copy(); }
    public ItemStack target() { return target.copy(); }
    public MachineExecutionProfile profile() { return profile; }
    public CloudworksMachineAdapter adapter() { return adapter; }
    public CloudworksEnergyGenerator generator() { return generator; }
    public CloudworksThermalPlan thermal() { return thermal; }
    public MachineModuleType upgrade() { return upgrade; }
    public int upgradeLimit() { return upgradeLimit; }
}
