package mekanism.cloudworks.common.registry;

import net.minecraft.nbt.NBTTagCompound;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagByte;

/**
 * Explicit NBT identity selector owned by a machine family registration.
 */
public final class MachineIdentityPolicy {
    private final Set<String> keys;
    private final Map<String, NBTBase> defaults;
    private final boolean exact;

    private MachineIdentityPolicy(Set<String> keys) {
        this(keys, Collections.emptyMap());
    }

    private MachineIdentityPolicy(Set<String> keys, Map<String, NBTBase> defaults) {
        this(keys, defaults, false);
    }

    private MachineIdentityPolicy(Set<String> keys, Map<String, NBTBase> defaults, boolean exact) {
        this.keys = Collections.unmodifiableSet(new LinkedHashSet<>(keys));
        this.defaults = Collections.unmodifiableMap(new LinkedHashMap<>(defaults));
        this.exact = exact;
    }

    public static MachineIdentityPolicy none() {
        return new MachineIdentityPolicy(Collections.emptySet());
    }

    public static MachineIdentityPolicy exactNbt() {
        return new MachineIdentityPolicy(Collections.emptySet(), Collections.emptyMap(), true);
    }

    public boolean isExactNbt() { return exact; }

    public static MachineIdentityPolicy keys(String... names) {
        Set<String> values = new LinkedHashSet<>();
        if (names == null) throw new IllegalArgumentException("Identity keys cannot be null");
        for (String name : names) {
            if (name == null || name.trim().isEmpty() || !values.add(name))
                throw new IllegalArgumentException("Empty or duplicate identity key");
        }
        return new MachineIdentityPolicy(values);
    }

    public MachineIdentityPolicy defaultInt(String key, int value) {
        return withDefault(key, new NBTTagInt(value));
    }

    public MachineIdentityPolicy defaultBoolean(String key, boolean value) {
        return withDefault(key, new NBTTagByte((byte) (value ? 1 : 0)));
    }

    private MachineIdentityPolicy withDefault(String key, NBTBase value) {
        if (!keys.contains(key)) throw new IllegalArgumentException("Default for an unselected identity key");
        Map<String, NBTBase> values = new LinkedHashMap<>(defaults);
        values.put(key, value);
        return new MachineIdentityPolicy(keys, values);
    }

    public boolean sameDefinition(MachineIdentityPolicy other) {
        return other != null && exact == other.exact && keys.equals(other.keys) && defaults.equals(other.defaults);
    }

    public NBTTagCompound select(NBTTagCompound source) {
        if (exact) return source == null ? new NBTTagCompound() : source.copy();
        NBTTagCompound selected = new NBTTagCompound();
        for (String key : keys) {
            String[] path = key.split("\\.");
            NBTTagCompound current = source;
            for (int index = 0; current != null && index < path.length - 1; index++)
                current = current.hasKey(path[index], 10) ? current.getCompoundTag(path[index]) : null;
            NBTBase value = current == null ? null : current.getTag(path[path.length - 1]);
            if (value == null) value = defaults.get(key);
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
}
