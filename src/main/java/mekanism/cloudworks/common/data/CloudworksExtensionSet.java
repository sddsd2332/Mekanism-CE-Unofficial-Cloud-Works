package mekanism.cloudworks.common.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

/**
 * Namespaced extension payload container shared by lines, drafts and
 * blueprints. Bounds are enforced on every put and re-checked on read;
 * unknown ids, version mismatches and malformed entries are preserved or
 * isolated but never executed and never crash the host save.
 */
public final class CloudworksExtensionSet {
    /** Hard limits of one extension container; the editor rejects earlier. */
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    public static final int MAX_TOTAL_BYTES = 64 * 1024;
    public static final int MAX_DEPTH = 6;

    private final Map<ResourceLocation, Entry> entries = new LinkedHashMap<>();

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /** Registered (or preserved-unknown) entry ids in stable order. */
    public Iterable<Map.Entry<ResourceLocation, Entry>> entries() {
        return Collections.unmodifiableMap(entries).entrySet();
    }

    /**
     * Stores or replaces one payload. Bounds violations throw so editor
     * submissions fail loudly instead of silently dropping data.
     */
    public void put(ResourceLocation id, int version, NBTTagCompound payload) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(payload, "payload");
        if (version <= 0) throw new IllegalArgumentException("Extension version must be positive");
        NBTTagCompound copy = payload.copy();
        int depth = depth(copy);
        if (depth > MAX_DEPTH)
            throw new IllegalArgumentException("Extension payload nesting exceeds " + MAX_DEPTH);
        int bytes = measure(copy);
        if (bytes > MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Extension payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        int total = bytes;
        for (Entry entry : entries.values())
            if (!entry.id.equals(id)) total += entry.bytes;
        if (total > MAX_TOTAL_BYTES)
            throw new IllegalArgumentException("Extension payload total exceeds " + MAX_TOTAL_BYTES + " bytes");
        entries.put(id, new Entry(id, version, copy, bytes));
    }

    /** Read-only view of one entry; null when absent. */
    public Entry get(ResourceLocation id) {
        return entries.get(id);
    }

    /** Verbatim container copy (id -> {version, payload}); server-side blueprint carrier (9.x). */
    public NBTTagCompound container() {
        NBTTagCompound carrier = new NBTTagCompound();
        for (Map.Entry<ResourceLocation, Entry> entry : entries.entrySet()) {
            NBTTagCompound encoded = new NBTTagCompound();
            encoded.setInteger("version", entry.getValue().version);
            encoded.setTag("payload", entry.getValue().payload.copy());
            carrier.setTag(entry.getKey().toString(), encoded);
        }
        return carrier;
    }

    /**
     * Target-wins merge from a blueprint carrier: absent ids are adopted when
     * they fit every bound; existing entries are never overwritten and
     * out-of-bounds carriers are skipped silently, matching the read doctrine.
     */
    public void mergeFrom(NBTTagCompound carrier) {
        if (carrier == null) return;
        for (String key : carrier.getKeySet()) {
            ResourceLocation id;
            try {
                id = new ResourceLocation(key.contains(":") ? key : "cloudworks:" + key);
            } catch (Exception invalid) {
                continue;
            }
            if (entries.containsKey(id)) continue;
            NBTBase raw = carrier.getTag(key);
            if (!(raw instanceof NBTTagCompound)) continue;
            NBTTagCompound entry = (NBTTagCompound) raw;
            if (!entry.hasKey("version", 3) || !entry.hasKey("payload", 10)) continue;
            NBTTagCompound payload = entry.getCompoundTag("payload");
            int depth = depth(payload);
            int bytes = measure(payload);
            long total = bytes;
            for (Entry existing : entries.values()) total += existing.bytes;
            if (depth > MAX_DEPTH || bytes > MAX_PAYLOAD_BYTES || total > MAX_TOTAL_BYTES) continue;
            int version = entry.getInteger("version");
            if (version <= 0) continue;
            entries.put(id, new Entry(id, version, payload.copy(), bytes));
        }
    }

    /**
     * Decodes a stored container. Unknown ids, version mismatches and
     * structurally malformed entries are skipped (isolated); healthy siblings
     * survive. Never throws.
     */
    public static CloudworksExtensionSet read(NBTTagCompound tag) {
        CloudworksExtensionSet set = new CloudworksExtensionSet();
        if (tag == null || !tag.hasKey("extensions", 10)) return set;
        NBTTagCompound container = tag.getCompoundTag("extensions");
        for (String key : container.getKeySet()) {
            ResourceLocation id;
            try {
                id = new ResourceLocation(key.contains(":") ? key : "cloudworks:" + key);
            } catch (Exception invalid) {
                continue;
            }
            NBTBase raw = container.getTag(key);
            if (!(raw instanceof NBTTagCompound)) continue;
            NBTTagCompound entry = (NBTTagCompound) raw;
            if (!entry.hasKey("version", 3) || !entry.hasKey("payload", 10)) continue;
            NBTTagCompound payload = entry.getCompoundTag("payload");
            int depth = depth(payload);
            int bytes = measure(payload);
            if (depth > MAX_DEPTH || bytes > MAX_PAYLOAD_BYTES
                    || set.entries.values().stream().mapToLong(value -> value.bytes).sum() + bytes
                            > MAX_TOTAL_BYTES) {
                // Out-of-bounds entries are dropped at read: put() can never create
                // them, so their presence means external editing — the declared
                // single-entry and depth bounds hold unconditionally, and the host
                // save is protected from the total-budget overflow.
                continue;
            }
            set.entries.put(id, new Entry(id, entry.getInteger("version"), payload.copy(), bytes));
        }
        return set;
    }

    /** Writes the optional container; absent when empty. */
    public void write(NBTTagCompound tag) {
        Objects.requireNonNull(tag, "tag");
        if (entries.isEmpty()) return;
        NBTTagCompound container = new NBTTagCompound();
        for (Entry entry : entries.values()) {
            NBTTagCompound encoded = new NBTTagCompound();
            encoded.setInteger("version", entry.version);
            encoded.setTag("payload", entry.payload.copy());
            container.setTag(entry.id.toString(), encoded);
        }
        tag.setTag("extensions", container);
    }

    public CloudworksExtensionSet copy() {
        CloudworksExtensionSet set = new CloudworksExtensionSet();
        for (Entry entry : entries.values())
            set.entries.put(entry.id, new Entry(entry.id, entry.version, entry.payload.copy(), entry.bytes));
        return set;
    }

    private static int depth(NBTTagCompound tag) {
        int max = 0;
        for (String key : tag.getKeySet()) {
            NBTBase value = tag.getTag(key);
            if (value instanceof NBTTagCompound)
                max = Math.max(max, 1 + depth((NBTTagCompound) value));
        }
        return max;
    }

    /** Deterministic byte accounting: keys, headers and payload data. */
    private static int measure(NBTBase tag) {
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            int total = 3;
            for (String key : compound.getKeySet()) {
                NBTBase value = compound.getTag(key);
                total += 2 + key.length() + 1 + measure(value);
            }
            return total;
        }
        String asString = tag.toString();
        return 4 + asString.length();
    }

    /** One stored payload; unknown types keep their bytes verbatim. */
    public static final class Entry {
        private final ResourceLocation id;
        private final int version;
        private final NBTTagCompound payload;
        private final int bytes;

        private Entry(ResourceLocation id, int version, NBTTagCompound payload, int bytes) {
            this.id = id;
            this.version = version;
            this.payload = payload;
            this.bytes = bytes;
        }

        public ResourceLocation id() { return id; }
        public int version() { return version; }

        /** Copy of the raw payload; callers must not hand this to workers. */
        public NBTTagCompound payload() { return payload.copy(); }
        int bytes() { return bytes; }
    }
}
