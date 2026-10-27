package mekanism.cloudworks.common.registry;

import mekanism.cloudworks.api.machine.MachineIdentityPolicy;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

public final class MachineKey {
    private final Item item;
    private final int metadata;
    private final NBTTagCompound identityNbt;
    private final MachineIdentityPolicy identityPolicy;

    private MachineKey(Item item, int metadata, NBTTagCompound identityNbt, MachineIdentityPolicy identityPolicy) {
        this.item = item;
        this.metadata = metadata;
        this.identityNbt = identityNbt;
        this.identityPolicy = identityPolicy;
    }

    public static MachineKey of(ItemStack stack, MachineIdentityPolicy policy) {
        if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("Machine stack cannot be empty");
        return new MachineKey(stack.getItem(), stack.getMetadata(), policy.select(stack), policy);
    }

    public static MachineKey serialized(MachineKey template, NBTTagCompound tag, MachineIdentityPolicy policy) {
        ItemStack stack = new ItemStack(template.item, 1, template.metadata);
        if (tag != null && !tag.getKeySet().isEmpty()) stack.setTagCompound(tag.copy());
        return new MachineKey(template.item, template.metadata, policy.select(stack), policy);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MachineKey key)) return false;
        return item == key.item && metadata == key.metadata && identityNbt.equals(key.identityNbt);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * System.identityHashCode(item) + metadata) + identityNbt.hashCode();
    }

    public ItemStack displayStack() {
        ItemStack stack = new ItemStack(item, 1, metadata);
        identityPolicy.apply(stack, identityNbt);
        return stack;
    }
}
