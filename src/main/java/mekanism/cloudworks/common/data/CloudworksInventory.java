package mekanism.cloudworks.common.data;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.CloudworksWarehouseCaps;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;

/**
 * Owner-thread inventory. Callers must serialize transfers at the cloud boundary.
 *
 * <p>Per-type caps ({@link CloudworksWarehouseCaps}) bound how much of ONE
 * resource type may be stored, independent of other types. Content above a cap
 * (a lowered cap, or legacy data) stays readable — QIO over-capacity semantics —
 * but nothing more of that type enters until extraction brings it back within
 * the cap. The total capacity remains a separate, coarser bound.</p>
 */
public final class CloudworksInventory {
    private static final int PAGE_COUNT = 256;
    private final Page[] pages;
    private long capacity;
    private long total, nextOrder;

    public CloudworksInventory(long capacity) {
        if (capacity < 0) throw new IllegalArgumentException("Negative inventory capacity");
        this.capacity = capacity;
        pages = new Page[PAGE_COUNT];
    }

    private CloudworksInventory(CloudworksInventory source) {
        capacity = source.capacity;
        total = source.total;
        nextOrder = source.nextOrder;
        pages = source.pages.clone();
        for (Page page : pages) if (page != null) page.shared = true;
    }

    /** Copies only page references; a mutation detaches its resource page. */
    public CloudworksInventory copy() {
        return new CloudworksInventory(this);
    }

    /**
     * Loads directly into the content map: per-type caps are NOT enforced here so
     * over-cap records stay readable (inserts of that type stay blocked until
     * extraction brings it back within the cap). Only the total capacity remains
     * a hard integrity check.
     */
    public static CloudworksInventory read(NBTTagCompound tag) {
        CloudworksDataChecks.fields(tag, 4, "capacity");

        CloudworksDataChecks.schema(tag, 1);
        CloudworksInventory inventory = new CloudworksInventory(tag.getLong("capacity"));
        NBTTagList entries = CloudworksDataChecks.list(tag, "contents", NBT.TAG_COMPOUND);
        long total = 0;
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound value = entries.getCompoundTagAt(i);
            CloudworksDataChecks.fields(value, 4, "amount");
            CloudworksDataChecks.fields(value, 10, "descriptor");
            long amount = value.getLong("amount");
            if (amount <= 0) throw new IllegalArgumentException("Invalid inventory entry");
            QIOResourceDescriptor descriptor = CloudworksDataChecks.descriptor(value.getCompoundTag("descriptor"));
            if (inventory.amount(descriptor) != 0)
                throw new IllegalArgumentException("Invalid duplicate inventory entry");
            inventory.put(descriptor, amount);
            total = Math.addExact(total, amount);
        }
        if (total > inventory.capacity) throw new IllegalArgumentException("Inventory exceeds capacity");
        inventory.total = total;
        return inventory;
    }

    public long capacity() {
        return capacity;
    }

    public long amount(QIOResourceDescriptor descriptor) {
        if (descriptor == null) return 0;
        Page page = pages[page(descriptor)];
        Value value = page == null ? null : page.values.get(descriptor);
        return value == null ? 0 : value.amount;
    }

    public Map<QIOResourceDescriptor, Long> snapshot() {
        List<Map.Entry<QIOResourceDescriptor, Value>> ordered = new ArrayList<>();
        for (Page page : pages) if (page != null) ordered.addAll(page.values.entrySet());
        ordered.sort(Comparator.comparingLong(entry -> entry.getValue().order));
        Map<QIOResourceDescriptor, Long> snapshot = new LinkedHashMap<>();
        for (Map.Entry<QIOResourceDescriptor, Value> entry : ordered)
            snapshot.put(entry.getKey(), entry.getValue().amount);
        return Collections.unmodifiableMap(snapshot);
    }

    public long total() {
        return total;
    }

    public long room() {
        return capacity - total();
    }

    public void setCapacity(long capacity) {
        if (capacity < total()) throw new IllegalArgumentException("Capacity below stored amount");
        this.capacity = capacity;
    }

    /**
     * How much of this resource type could be accepted right now (ask before moving).
     */
    public long insertable(QIOResourceDescriptor descriptor, long amount) {
        if (descriptor == null || amount < 0) throw new IllegalArgumentException("Invalid insertable query");
        long perTypeRoom = CloudworksWarehouseCaps.capOf(descriptor.getFamily()) - amount(descriptor);
        return Math.max(0L, Math.min(amount, Math.min(room(), perTypeRoom)));
    }

    public long insert(QIOResourceDescriptor descriptor, long amount) {
        if (descriptor == null || amount < 0) throw new IllegalArgumentException("Invalid insert");
        long accepted = insertable(descriptor, amount);
        if (accepted > 0) {
            put(descriptor, Math.addExact(amount(descriptor), accepted));
            total = Math.addExact(total, accepted);
        }
        return accepted;
    }

    /**
     * Restores a previously drained amount, bypassing the per-type cap but still
     * honoring the total capacity. Over-cap baselines stay readable (QIO
     * over-capacity semantics); only growth beyond a baseline must fit the cap,
     * which callers check with {@link #insertable} first.
     */
    public long insertIgnoringTypeCap(QIOResourceDescriptor descriptor, long amount) {
        if (descriptor == null || amount < 0) throw new IllegalArgumentException("Invalid restore");
        long accepted = Math.min(amount, Math.min(room(), Long.MAX_VALUE - amount(descriptor)));
        if (accepted > 0) {
            put(descriptor, Math.addExact(amount(descriptor), accepted));
            total = Math.addExact(total, accepted);
        }
        return accepted;
    }

    public long extract(QIOResourceDescriptor descriptor, long amount) {
        if (descriptor == null || amount < 0) throw new IllegalArgumentException("Invalid extract");
        long taken = Math.min(amount, amount(descriptor));
        if (taken > 0) {
            long left = amount(descriptor) - taken;
            put(descriptor, left);
            total -= taken;
        }
        return taken;
    }

    /**
     * All-or-nothing multi-resource reservation. No inputs are removed on failure.
     */
    public boolean takeBatch(Map<QIOResourceDescriptor, Long> requested) {
        for (Map.Entry<QIOResourceDescriptor, Long> entry : requested.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0
                    || amount(entry.getKey()) < entry.getValue()) return false;
        }
        for (Map.Entry<QIOResourceDescriptor, Long> entry : requested.entrySet())
            extract(entry.getKey(), entry.getValue());
        return true;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", 1);
        tag.setLong("capacity", capacity);
        NBTTagList entries = new NBTTagList();
        for (Map.Entry<QIOResourceDescriptor, Long> entry : snapshot().entrySet()) {
            NBTTagCompound value = new NBTTagCompound();
            value.setTag("descriptor", entry.getKey().write());
            value.setLong("amount", entry.getValue());
            entries.appendTag(value);
        }
        tag.setTag("contents", entries);
        return tag;
    }

    public void collectChanges(CloudworksInventory previous, String warehouse, CloudworksWarehouseDelta delta) {
        for (int index = 0; index < PAGE_COUNT; index++) {
            Page before = previous.pages[index], after = pages[index];
            if (before == after) continue;
            if (before != null) for (QIOResourceDescriptor resource : before.values.keySet())
                if (amount(resource) != previous.amount(resource)) delta.set(warehouse, resource, amount(resource));
            if (after != null) for (QIOResourceDescriptor resource : after.values.keySet())
                if (previous.amount(resource) == 0) delta.set(warehouse, resource, amount(resource));
        }
    }

    private static int page(QIOResourceDescriptor descriptor) {
        int hash = descriptor.hashCode();
        return (hash ^ (hash >>> 16)) & (PAGE_COUNT - 1);
    }

    private void put(QIOResourceDescriptor descriptor, long amount) {
        int index = page(descriptor);
        Page page = pages[index];
        if (page == null) pages[index] = page = new Page();
        else if (page.shared) pages[index] = page = new Page(page);
        Value previous = page.values.get(descriptor);
        if (amount == 0) page.values.remove(descriptor);
        else page.values.put(descriptor, new Value(amount, previous == null ? nextOrder++ : previous.order));
    }

    private static final class Page {
        final Map<QIOResourceDescriptor, Value> values = new LinkedHashMap<>();
        volatile boolean shared;
        Page() {}
        Page(Page source) { values.putAll(source.values); }
    }

    private static final class Value {
        final long amount, order;
        Value(long amount, long order) { this.amount = amount; this.order = order; }
    }
}
