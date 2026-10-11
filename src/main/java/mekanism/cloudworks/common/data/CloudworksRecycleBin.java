package mekanism.cloudworks.common.data;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owner-level recycle bin: the receiver for resources held by deleted nodes
 * and lines. Bounded voiding buffer — at most {@link #MAX_TYPES} descriptors,
 * each capped at the configured per-type capacity; anything beyond is discarded. The bin
 * never drains on its own: the player routes it to a warehouse or clears it.
 */
public final class CloudworksRecycleBin {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_TYPES = 128;

    private final Map<QIOResourceDescriptor, Long> entries = new LinkedHashMap<>();

    public static CloudworksRecycleBin read(NBTTagCompound tag) {
        CloudworksDataChecks.schema(tag, SCHEMA_VERSION);

        if (tag.getInteger("schema") != SCHEMA_VERSION)
            throw new IllegalArgumentException("Unknown recycle bin schema");
        CloudworksRecycleBin bin = new CloudworksRecycleBin();
        NBTTagList list = CloudworksDataChecks.list(tag, "entries", NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            CloudworksDataChecks.fields(entry, 4, "amount");
            CloudworksDataChecks.fields(entry, 10, "descriptor");
            QIOResourceDescriptor descriptor = CloudworksDataChecks.descriptor(entry.getCompoundTag("descriptor"));
            long amount = entry.getLong("amount");
            if (amount <= 0 || amount > Long.MAX_VALUE / MAX_TYPES || bin.entries.put(descriptor, amount) != null
                    || bin.entries.size() > MAX_TYPES)
                throw new IllegalArgumentException("Invalid recycle entry");
        }
        return bin;
    }

    /**
     * Amount of one descriptor currently in the bin.
     */
    public long amount(QIOResourceDescriptor descriptor) {
        return entries.getOrDefault(descriptor, 0L);
    }

    public Map<QIOResourceDescriptor, Long> entries() {
        return new LinkedHashMap<>(entries);
    }

    public int typeCount() {
        return entries.size();
    }

    public long total() {
        long total = 0;
        for (long amount : entries.values()) total = Math.addExact(total, amount);
        return total;
    }

    /**
     * Deposits resources, capping per type and by type count. Returns the
     * discarded amount so the caller can notify the player.
     */
    public long deposit(QIOResourceDescriptor descriptor, long amount) {
        if (descriptor == null || !descriptor.isResolved() || amount <= 0)
            throw new IllegalArgumentException("Invalid recycle deposit");
        long current = entries.getOrDefault(descriptor, 0L);
        if (current == 0L && entries.size() >= MAX_TYPES) return amount;
        long capacity = mekanism.cloudworks.common.config.CloudworksConfig.settings().recycleCapacityPerType;
        long accepted = Math.min(amount, Math.max(0L, capacity - current));
        long discarded = amount - accepted;
        if (accepted > 0) entries.put(descriptor, current + accepted);
        return discarded;
    }

    /**
     * Removes and returns everything; used by the manual route-to-warehouse action.
     */
    public Map<QIOResourceDescriptor, Long> drain() {
        Map<QIOResourceDescriptor, Long> drained = entries();
        entries.clear();
        return drained;
    }

    /**
     * Clears everything (manual purge with confirmation).
     */
    public void clear() {
        entries.clear();
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", SCHEMA_VERSION);
        NBTTagList list = new NBTTagList();
        entries.forEach((descriptor, amount) -> {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setTag("descriptor", descriptor.write());
            entry.setLong("amount", amount);
            list.appendTag(entry);
        });
        tag.setTag("entries", list);
        return tag;
    }
}
