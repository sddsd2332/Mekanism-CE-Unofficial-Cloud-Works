package mekanism.cloudworks.common.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import java.util.LinkedHashSet;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;

/**
 * Counted custom-upgrade container shared by nodes, line pools and the cloud
 * pool. Counts are per type id; unknown ids (missing integration) keep their
 * counts verbatim so assets never vanish or duplicate. Bounds are enforced on
 * every mutation.
 */
public final class CloudworksUpgradeSet {
    public static final int MAX_ID_CHARS = 128;

    private final Map<ResourceLocation, Long> counts = new LinkedHashMap<>();
    private long version;

    public long version() { return version; }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    public int size() {
        return counts.size();
    }

    /** Count of one type; 0 when absent. */
    public long count(ResourceLocation id) {
        Long value = counts.get(Objects.requireNonNull(id, "id"));
        return value == null ? 0L : value;
    }

    /** Sets one count; zero removes the entry. Bounds throw on violation. */
    public void set(ResourceLocation id, long count) {
        Objects.requireNonNull(id, "id");
        if (id.toString().length() > MAX_ID_CHARS) throw new IllegalArgumentException("Upgrade id too long");
        if (count < 0) throw new IllegalArgumentException("Negative upgrade count");
        if (count == 0L) {
            if (counts.remove(id) != null) version++;
            return;
        }
        Long previous = counts.put(id, count);
        if (previous == null || previous != count) version++;
    }

    /** Adds delta to one count; the result must stay non-negative. */
    public void add(ResourceLocation id, long delta) {
        long current = count(id);
        if (delta > 0 && current > Long.MAX_VALUE - delta)
            throw new IllegalArgumentException("Upgrade count overflow");
        set(id, current + delta);
    }

    /** Replaces the whole content in one step; validated before any mutation (8.5-B5). */
    public void setAll(Map<ResourceLocation, Long> replacement) {
        LinkedHashMap<ResourceLocation, Long> next = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Long> entry : replacement.entrySet()) {
            long value = entry.getValue();
            if (entry.getKey() != null && entry.getKey().toString().length() > MAX_ID_CHARS)
                throw new IllegalArgumentException("Upgrade id too long");
            if (entry.getKey() == null || value <= 0)
                throw new IllegalArgumentException("Invalid upgrade count");
            next.put(entry.getKey(), value);
        }
        if (!counts.equals(next)) {
            counts.clear();
            counts.putAll(next);
            version++;
        }
    }

    /** Sum over all types; saturating. */
    public long total() {
        long sum = 0;
        for (long value : counts.values()) {
            sum += value;
            if (sum < 0) return Long.MAX_VALUE;
        }
        return sum;
    }

    /** Stable id view. */
    public Iterable<ResourceLocation> ids() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(counts.keySet()));
    }

    /** Reads the optional container; malformed entries are skipped, never thrown. */
    public static CloudworksUpgradeSet read(NBTTagCompound tag, String key) {
        CloudworksUpgradeSet set = new CloudworksUpgradeSet();
        if (tag == null || !tag.hasKey(key, 10)) return set;
        NBTTagCompound container = tag.getCompoundTag(key);
        for (String name : container.getKeySet()) {
            ResourceLocation id;
            try {
                id = new ResourceLocation(name.contains(":") ? name : "cloudworks:" + name);
            } catch (Exception invalid) {
                continue;
            }
            if (id.toString().length() > MAX_ID_CHARS) continue;
            NBTBase raw = container.getTag(name);
            if (!(raw instanceof NBTPrimitive)) continue;
            long count = ((NBTPrimitive) raw).getLong();
            if (count > 0) set.counts.put(id, count);
        }
        return set;
    }

    /** Writes the optional container; absent when empty. */
    public void write(NBTTagCompound tag, String key) {
        Objects.requireNonNull(tag, "tag");
        if (counts.isEmpty()) return;
        NBTTagCompound container = new NBTTagCompound();
        for (Map.Entry<ResourceLocation, Long> entry : counts.entrySet())
            container.setLong(entry.getKey().toString(), entry.getValue());
        tag.setTag(key, container);
    }

    public CloudworksUpgradeSet copy() {
        CloudworksUpgradeSet set = new CloudworksUpgradeSet();
        set.counts.putAll(counts);
        set.version = version;
        return set;
    }
}
