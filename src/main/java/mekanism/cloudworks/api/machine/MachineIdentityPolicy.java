package mekanism.cloudworks.api.machine;

import mekanism.common.base.ITierItem;
import mekanism.common.tier.BaseTier;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;

/**
 * Explicit NBT identity selector owned by a machine family registration.
 */
public final class MachineIdentityPolicy {
    private final Set<String> keys;
    private final Map<String, NBTBase> defaults;
    private final boolean exact;
    private final boolean tierItem;

    private MachineIdentityPolicy(Set<String> keys, boolean exact, boolean tierItem) {
        this(keys, Collections.emptyMap(), exact, tierItem);
    }

    private MachineIdentityPolicy(Set<String> keys, Map<String, NBTBase> defaults, boolean exact, boolean tierItem) {
        this.keys = Collections.unmodifiableSet(new LinkedHashSet<>(keys));
        Map<String, NBTBase> copy = new LinkedHashMap<>();
        defaults.forEach((key, value) -> copy.put(key, value.copy()));
        this.defaults = Collections.unmodifiableMap(copy);
        this.exact = exact;
        this.tierItem = tierItem;
    }

    public static MachineIdentityPolicy none() {
        return new MachineIdentityPolicy(Collections.emptySet(), false, false);
    }

    public static MachineIdentityPolicy exactNbt() {
        return new MachineIdentityPolicy(Collections.emptySet(), true, false);
    }

    /**
     * Uses Mekanism's ITierItem contract as the identity source and writer.
     */
    public static MachineIdentityPolicy tierItem() {
        return new MachineIdentityPolicy(Collections.emptySet(), false, true);
    }

    public NBTTagCompound select(ItemStack stack) {
        if (tierItem) {
            if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof ITierItem item)) {
                throw new IllegalArgumentException("Tier identity requires Mekanism's ITierItem API");
            }
            NBTTagCompound selected = new NBTTagCompound();
            selected.setString("nativeTier", item.getBaseTier(stack).name());
            return selected;
        }
        return select(stack == null ? null : stack.getTagCompound());
    }

    public static MachineIdentityPolicy keys(String... names) {
        Set<String> values = new LinkedHashSet<>();
        if (names == null) throw new IllegalArgumentException("Identity keys cannot be null");
        for (String name : names) {
            if (name == null || name.trim().isEmpty() || !values.add(name))
                throw new IllegalArgumentException("Empty or duplicate identity key");
        }
        return new MachineIdentityPolicy(values, false, false);
    }

    public static MachineIdentityPolicy keysWithDefaultByte(String name, byte value) {
        if (name == null || name.trim().isEmpty())
            throw new IllegalArgumentException("Empty identity key");
        Set<String> values = new LinkedHashSet<>();
        values.add(name);
        Map<String, NBTBase> defaults = new LinkedHashMap<>();
        defaults.put(name, new NBTTagByte(value));
        return new MachineIdentityPolicy(values, defaults, false, false);
    }

    public boolean sameDefinition(MachineIdentityPolicy other) {
        return other != null && exact == other.exact && tierItem == other.tierItem
                && keys.equals(other.keys) && defaults.equals(other.defaults);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keys, defaults, exact, tierItem);
    }

    public NBTTagCompound select(NBTTagCompound source) {
        if (tierItem) throw new IllegalStateException("Tier identity must be read through ITierItem");
        if (exact) return source == null ? new NBTTagCompound() : source.copy();
        NBTTagCompound selected = new NBTTagCompound();
        for (String key : keys) {
            String[] path = key.split("\\.");
            NBTTagCompound current = source;
            for (int index = 0; current != null && index < path.length - 1; index++)
                current = current.hasKey(path[index], 10) ? current.getCompoundTag(path[index]) : null;
            NBTBase value = current == null ? null : current.getTag(path[path.length - 1]);
            if (value == null && path.length == 1) value = defaults.get(key);
            if (value == null) continue;
            NBTTagCompound target = selected;
            for (int index = 0; index < path.length - 1; index++) {
                if (!target.hasKey(path[index], 10)) target.setTag(path[index], new NBTTagCompound());
                target = target.getCompoundTag(path[index]);
            }
            target.setTag(path[path.length - 1], value.copy());
        }
        return selected;
    }

    public void apply(ItemStack stack, NBTTagCompound identity) {
        if (tierItem) {
            if (!(stack.getItem() instanceof ITierItem item) || !identity.hasKey("nativeTier", 8)) {
                throw new IllegalArgumentException("Invalid Mekanism tier identity");
            }
            BaseTier tier;
            try {
                tier = BaseTier.valueOf(identity.getString("nativeTier"));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid Mekanism tier", failure);
            }
            item.setBaseTier(stack, tier);
        } else if (!identity.getKeySet().isEmpty()) {
            stack.setTagCompound(identity.copy());
        }
    }
}
