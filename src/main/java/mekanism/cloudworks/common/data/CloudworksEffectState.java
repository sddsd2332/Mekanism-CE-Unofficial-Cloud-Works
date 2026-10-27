package mekanism.cloudworks.common.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import java.util.LinkedHashSet;
import net.minecraft.nbt.NBTPrimitive;

/**
 * Typed cloud-level effect state: effect id to level. Built-in effects keep
 * their dedicated fields (see the migration mapping in the NBT docs); this
 * map is the single carrier for effects declared against the generic cloud
 * state channel. Unknown ids keep their levels verbatim so a missing
 * integration never strands or duplicates applied cards.
 */
public final class CloudworksEffectState {
    private final Map<ResourceLocation, Long> levels = new LinkedHashMap<>();

    public boolean isEmpty() {
        return levels.isEmpty();
    }

    public int size() {
        return levels.size();
    }

    /** Level of one effect; 0 when absent. */
    public long level(ResourceLocation id) {
        Long value = levels.get(Objects.requireNonNull(id, "id"));
        return value == null ? 0L : value;
    }

    /** Sets one level; zero removes the entry. */
    public void set(ResourceLocation id, long level) {
        Objects.requireNonNull(id, "id");
        if (level < 0) throw new IllegalArgumentException("Negative effect level");
        if (level == 0L) {
            levels.remove(id);
            return;
        }
        levels.put(id, level);
    }

    /** Stable id view. */
    public Iterable<ResourceLocation> ids() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(levels.keySet()));
    }

    /** Reads the optional container; malformed entries are skipped, never thrown. */
    public static CloudworksEffectState read(NBTTagCompound tag, String key) {
        CloudworksEffectState state = new CloudworksEffectState();
        if (tag == null || !tag.hasKey(key, 10)) return state;
        NBTTagCompound container = tag.getCompoundTag(key);
        for (String name : container.getKeySet()) {
            ResourceLocation id;
            try {
                id = new ResourceLocation(name.contains(":") ? name : "cloudworks:" + name);
            } catch (Exception invalid) {
                continue;
            }
            NBTBase raw = container.getTag(name);
            if (!(raw instanceof NBTPrimitive)) continue;
            long level = ((NBTPrimitive) raw).getLong();
            if (level > 0) state.levels.put(id, level);
        }
        return state;
    }

    /** Writes the optional container; absent when empty. */
    public void write(NBTTagCompound tag, String key) {
        Objects.requireNonNull(tag, "tag");
        if (levels.isEmpty()) return;
        NBTTagCompound container = new NBTTagCompound();
        for (Map.Entry<ResourceLocation, Long> entry : levels.entrySet())
            container.setLong(entry.getKey().toString(), entry.getValue());
        tag.setTag(key, container);
    }

    public CloudworksEffectState copy() {
        CloudworksEffectState state = new CloudworksEffectState();
        state.levels.putAll(levels);
        return state;
    }
}
