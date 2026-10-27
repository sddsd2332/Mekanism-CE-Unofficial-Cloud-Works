package mekanism.cloudworks.common.runtime;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/** Sparse inventory ledger with inventory-local quantities and reservations. */
public final class CloudworksRuntimeResourceStore {
    private final Long2IntOpenHashMap indexes = new Long2IntOpenHashMap();
    private int inventoryCount, size;
    private long version;
    private int[] heads = new int[32];
    private long[] totals = new long[32];
    private long[] inventoryVersions = new long[32];
    private int[] resourceId = new int[64], next = new int[64];
    private long[] amount = new long[64], reserved = new long[64];
    /**
     * Per-entry monotonic versions (GLM handoff §3.4): every amount/reservation
     * mutation of one entry also stamps it, so warehouse projections can diff
     * against their last published snapshot instead of rebuilding the whole
     * inventory. Stamps share the global version counter, so no ABA is possible.
     */
    private long[] entryVersion = new long[64];
    private int[] entryInventory = new int[64];
    private boolean[] dirty = new boolean[64];
    private IntArrayList[] changed =
            new IntArrayList[32];
    private long[][] groupTotals = new long[32][];
    private int[][][] resourceGroups = new int[32][][];

    public CloudworksRuntimeResourceStore() {
        indexes.defaultReturnValue(-1);
        Arrays.fill(heads, -1);
    }

    public int inventoryCount() { return inventoryCount; }
    public int size() { return size; }
    public long version() { return version; }
    public long version(int inventory) { return inventoryVersions[checkInventory(inventory)]; }

    public int createInventory() {
        if (inventoryCount == heads.length) {
            int old = heads.length;
            heads = Arrays.copyOf(heads, old * 2);
            totals = Arrays.copyOf(totals, old * 2);
            inventoryVersions = Arrays.copyOf(inventoryVersions, old * 2);
            changed = Arrays.copyOf(changed, old * 2);
            groupTotals = Arrays.copyOf(groupTotals, old * 2);
            resourceGroups = Arrays.copyOf(resourceGroups, old * 2);
            Arrays.fill(heads, old, heads.length, -1);
        }
        heads[inventoryCount] = -1;
        totals[inventoryCount] = 0;
        changed[inventoryCount] = new IntArrayList();
        inventoryVersions[inventoryCount] = ++version;
        return inventoryCount++;
    }

    public long total(int inventory) { return totals[checkInventory(inventory)]; }

    public long amount(int inventory, int resource) {
        int index = find(inventory, resource);
        return index < 0 ? 0 : amount[index];
    }

    public long reserved(int inventory, int resource) {
        int index = find(inventory, resource);
        return index < 0 ? 0 : reserved[index];
    }

    public long available(int inventory, int resource) {
        int index = find(inventory, resource);
        return index < 0 ? 0 : amount[index] - reserved[index];
    }

    public void add(int inventory, int resource, long value) {
        nonnegative(value);
        if (value == 0) return;
        int index = index(inventory, resource);
        long nextAmount = Math.addExact(amount[index], value);
        long nextTotal = Math.addExact(totals[inventory], value);
        adjustGroups(inventory, resource, value);
        amount[index] = nextAmount;
        totals[inventory] = nextTotal;
        entryVersion[index] = inventoryVersions[inventory] = ++version;
        markChanged(index);
    }

    public void take(int inventory, int resource, long value) {
        nonnegative(value);
        if (value == 0) return;
        int index = find(inventory, resource);
        if (index < 0 || value > amount[index] - reserved[index])
            throw new IllegalArgumentException("Runtime inventory underflow");
        adjustGroups(inventory, resource, -value);
        amount[index] -= value;
        totals[inventory] -= value;
        entryVersion[index] = inventoryVersions[inventory] = ++version;
        markChanged(index);
    }

    public void move(int source, int target, int resource, long value) {
        nonnegative(value);
        if (source == target || value == 0) return;
        if (available(source, resource) < value)
            throw new IllegalArgumentException("Runtime transfer underflow");
        // Check destination arithmetic before debiting the source.
        add(target, resource, value);
        take(source, resource, value);
    }

    public boolean reserve(int inventory, int resource, long value) {
        nonnegative(value);
        if (value == 0) return true;
        int index = find(inventory, resource);
        if (index < 0 || amount[index] - reserved[index] < value) return false;
        reserved[index] += value;
        entryVersion[index] = inventoryVersions[inventory] = ++version;
        markChanged(index);
        return true;
    }

    public void release(int inventory, int resource, long value) {
        int index = find(inventory, resource);
        nonnegative(value);
        if (value == 0) return;
        if (index < 0 || value > reserved[index])
            throw new IllegalArgumentException("Runtime reservation underflow");
        reserved[index] -= value;
        entryVersion[index] = inventoryVersions[inventory] = ++version;
        markChanged(index);
    }

    public void consumeReserved(int inventory, int resource, long value) {
        int index = find(inventory, resource);
        nonnegative(value);
        if (value == 0) return;
        if (index < 0 || value > reserved[index])
            throw new IllegalArgumentException("Runtime reservation underflow");
        adjustGroups(inventory, resource, -value);
        reserved[index] -= value;
        amount[index] -= value;
        totals[inventory] -= value;
        entryVersion[index] = inventoryVersions[inventory] = ++version;
        markChanged(index);
    }

    public int first(int inventory) { return heads[checkInventory(inventory)]; }
    public int next(int entry) { return next[checkEntry(entry)]; }
    public int resource(int entry) { return resourceId[checkEntry(entry)]; }
    public long amountAt(int entry) { return amount[checkEntry(entry)]; }
    public long reservedAt(int entry) { return reserved[checkEntry(entry)]; }
    public long availableAt(int entry) { return amountAt(entry) - reservedAt(entry); }
    /** Monotonic per-entry stamp; unchanged since the last read means unchanged data. */
    public long entryVersion(int entry) { return entryVersion[checkEntry(entry)]; }

    public int[] drainChanges(int inventory) {
        IntArrayList entries = changed[checkInventory(inventory)];
        int[] result = entries.toIntArray();
        entries.clear();
        for (int entry : result) dirty[entry] = false;
        return result;
    }

    void registerGroups(int inventory, int groupCount, int[][] memberships) {
        checkInventory(inventory);
        long[] sums = new long[groupCount];
        for (int entry = first(inventory); entry >= 0; entry = next(entry))
            for (int group : memberships[resource(entry)])
                sums[group] = Math.addExact(sums[group], amountAt(entry));
        groupTotals[inventory] = sums;
        resourceGroups[inventory] = memberships;
    }

    long groupTotal(int inventory, int group) {
        return groupTotals[checkInventory(inventory)][group];
    }

    private void adjustGroups(int inventory, int resource, long delta) {
        long[] sums = groupTotals[inventory];
        if (sums == null) return;
        int[] groups = resourceGroups[inventory][resource];
        for (int group : groups) {
            long value = Math.addExact(sums[group], delta);
            if (value < 0) throw new IllegalStateException("Runtime aggregate underflow");
        }
        for (int group : groups) sums[group] += delta;
    }

    private void markChanged(int entry) {
        if (!dirty[entry]) {
            dirty[entry] = true;
            changed[entryInventory[entry]].add(entry);
        }
    }

    public void clearInventory(int inventory) {
        if (total(inventory) > 0) inventoryVersions[inventory] = ++version;
        for (int entry = first(inventory); entry >= 0; entry = next[entry]) {
            if (amount[entry] != 0 || reserved[entry] != 0) {
                entryVersion[entry] = version;
                markChanged(entry);
            }
            amount[entry] = 0;
            reserved[entry] = 0;
        }
        totals[inventory] = 0;
        if (groupTotals[inventory] != null) Arrays.fill(groupTotals[inventory], 0);
    }

    public void clear() {
        indexes.clear();
        Arrays.fill(heads, 0, inventoryCount, -1);
        Arrays.fill(totals, 0, inventoryCount, 0);
        Arrays.fill(inventoryVersions, 0, inventoryCount, 0);
        Arrays.fill(amount, 0, size, 0);
        Arrays.fill(reserved, 0, size, 0);
        Arrays.fill(entryVersion, 0, size, 0);
        Arrays.fill(dirty, 0, size, false);
        Arrays.fill(changed, 0, inventoryCount, null);
        Arrays.fill(groupTotals, 0, inventoryCount, null);
        Arrays.fill(resourceGroups, 0, inventoryCount, null);
        inventoryCount = 0;
        size = 0;
        version = 0;
    }

    private int find(int inventory, int resource) {
        checkInventory(inventory);
        if (resource < 0) throw new IllegalArgumentException("Negative runtime resource id");
        return indexes.get(key(inventory, resource));
    }

    private int index(int inventory, int resource) {
        int current = find(inventory, resource);
        if (current >= 0) return current;
        ensure(size + 1);
        int index = size++;
        resourceId[index] = resource;
        entryInventory[index] = inventory;
        amount[index] = 0;
        reserved[index] = 0;
        entryVersion[index] = ++version;
        next[index] = heads[inventory];
        heads[inventory] = index;
        indexes.put(key(inventory, resource), index);
        markChanged(index);
        return index;
    }

    private static long key(int inventory, int resource) {
        return ((long) inventory << 32) | (resource & 0xFFFFFFFFL);
    }

    private static void nonnegative(long value) {
        if (value < 0) throw new IllegalArgumentException("Negative runtime quantity");
    }

    private int checkInventory(int inventory) {
        if (inventory < 0 || inventory >= inventoryCount)
            throw new IndexOutOfBoundsException("Runtime inventory index");
        return inventory;
    }

    private int checkEntry(int entry) {
        if (entry < 0 || entry >= size) throw new IndexOutOfBoundsException("Runtime resource entry");
        return entry;
    }

    private void ensure(int required) {
        if (required <= resourceId.length) return;
        int capacity = Math.max(required, resourceId.length * 2);
        resourceId = Arrays.copyOf(resourceId, capacity);
        next = Arrays.copyOf(next, capacity);
        amount = Arrays.copyOf(amount, capacity);
        reserved = Arrays.copyOf(reserved, capacity);
        entryVersion = Arrays.copyOf(entryVersion, capacity);
        entryInventory = Arrays.copyOf(entryInventory, capacity);
        dirty = Arrays.copyOf(dirty, capacity);
    }
}
