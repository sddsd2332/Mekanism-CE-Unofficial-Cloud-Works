package mekanism.cloudworks.common.registry;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

public final class MachineKey {
    private final Item item;
    private final int metadata;
    private final NBTTagCompound identityNbt;

    private MachineKey(Item item, int metadata, NBTTagCompound identityNbt) {
        this.item = item;
        this.metadata = metadata;
        this.identityNbt = identityNbt;
    }

    public static MachineKey of(ItemStack stack) {
        return of(stack, MachineIdentityPolicy.none());
    }

    public static MachineKey of(ItemStack stack, MachineIdentityPolicy policy) {
        if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("Machine stack cannot be empty");
        return new MachineKey(stack.getItem(), stack.getMetadata(), normalizeIdentityNbt(stack.getTagCompound(), policy));
    }

    private static NBTTagCompound normalizeIdentityNbt(NBTTagCompound source, MachineIdentityPolicy policy) {
        if (policy.isExactNbt()) return policy.select(source);
        return policy.select(source);
    }

    public static MachineKey serialized(MachineKey template, NBTTagCompound tag, MachineIdentityPolicy policy) {
        return new MachineKey(template.item, template.metadata, normalizeIdentityNbt(tag, policy));
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
        if (!identityNbt.getKeySet().isEmpty()) stack.setTagCompound(identityNbt.copy());
        return stack;
    }
}
