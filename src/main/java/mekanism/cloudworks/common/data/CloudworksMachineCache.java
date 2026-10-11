package mekanism.cloudworks.common.data;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;

import java.util.ArrayList;
import java.util.List;

/**
 * Owner-level machine cache: the single physical home of every deposited
 * machine. Entries are grouped by machine type (item + damage); each entry
 * keeps an ordered queue of NBT variants. Deposits merge into the existing
 * variant position (identical machines are fungible) or append a new variant
 * at the tail; withdrawals drain from the head. Production lines never appear
 * here — claims are derived from the line files by summing machineCount per
 * type, so this file only ever changes through deposit and withdraw.
 */
public final class CloudworksMachineCache {
    public static final int SCHEMA_VERSION = 1;
    /**
     * Transient capability data stripped from every stored machine; not player-visible.
     */
    private static final String FORGE_CAPS = "ForgeCaps";
    private final List<Entry> entries = new ArrayList<>();

    /**
     * Variant of one type whose tag exactly equals the machine's (ForgeCaps-stripped) tag.
     */
    public static Variant findVariant(Entry entry, ItemStack machine) {
        NBTTagCompound tag = normalizedTag(machine);
        for (Variant variant : entry.variants)
            if (variant.tag.equals(tag)) return variant;
        return null;
    }

    /**
     * Strips transient capability data; everything else is preserved byte-for-byte.
     */
    private static NBTTagCompound normalizedTag(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) return new NBTTagCompound();
        NBTTagCompound copy = tag.copy();
        copy.removeTag(FORGE_CAPS);
        return copy;
    }

    public static CloudworksMachineCache read(NBTTagCompound tag) {
        CloudworksDataChecks.schema(tag, SCHEMA_VERSION);

        if (tag.getInteger("schema") != SCHEMA_VERSION)
            throw new IllegalArgumentException("Unknown machine cache schema");
        CloudworksMachineCache cache = new CloudworksMachineCache();
        NBTTagList entryList = CloudworksDataChecks.list(tag, "entries", NBT.TAG_COMPOUND);
        for (int i = 0; i < entryList.tagCount(); i++) {
            NBTTagCompound entryTag = entryList.getCompoundTagAt(i);
            CloudworksDataChecks.field(entryTag, "machine", NBT.TAG_COMPOUND);
            Entry entry = new Entry(new ItemStack(entryTag.getCompoundTag("machine")));
            if (entry.template.isEmpty()) throw new IllegalArgumentException("Invalid machine template");
            NBTTagList variantList = CloudworksDataChecks.list(entryTag, "variants", NBT.TAG_COMPOUND);
            // Tag identity is value based (NBTTagCompound equals/hashCode compare the
            // entry set), so a differently ordered serialization of the same tag is
            // still recognised; a string form would not be.
            java.util.Set<NBTTagCompound> seenTags = new java.util.HashSet<>();
            for (int v = 0; v < variantList.tagCount(); v++) {
                NBTTagCompound variantTag = variantList.getCompoundTagAt(v);
                CloudworksDataChecks.field(variantTag, "count", NBT.TAG_LONG);
                if (variantTag.hasKey("tag")) CloudworksDataChecks.field(variantTag, "tag", NBT.TAG_COMPOUND);
                long count = variantTag.getLong("count");
                if (count <= 0) throw new IllegalArgumentException("Invalid variant count");
                NBTTagCompound variantNbt = variantTag.hasKey("tag", NBT.TAG_COMPOUND)
                        ? variantTag.getCompoundTag("tag") : new NBTTagCompound();
                // Duplicate tags would make variant identity ambiguous.
                if (!seenTags.add(variantNbt))
                    throw new IllegalArgumentException("Duplicate machine variant");
                entry.variants.add(new Variant(variantNbt, count));
            }
            if (entry.variants.isEmpty()) throw new IllegalArgumentException("Machine entry without variants");
            // Duplicate type entries would strand every variant but the first:
            // lookups and deposits resolve the first entry only.
            if (cache.findType(entry.template) != null)
                throw new IllegalArgumentException("Duplicate machine type");
            cache.entries.add(entry);
        }
        return cache;
    }

    public List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    public int typeCount() {
        return entries.size();
    }

    /**
     * Type entry for an item+damage identity, or null.
     */
    public Entry findType(ItemStack machine) {
        for (Entry entry : entries)
            if (ItemStack.areItemsEqual(entry.template, machine)) return entry;
        return null;
    }

    /**
     * Index of the type entry for an item+damage identity, or -1.
     */
    public int typeIndex(ItemStack machine) {
        for (int i = 0; i < entries.size(); i++)
            if (ItemStack.areItemsEqual(entries.get(i).template, machine)) return i;
        return -1;
    }

    /**
     * Deposits a stack: merges into the matching variant position, appends a new
     * variant at the tail, or creates the whole type entry. Returns nothing —
     * the cache is unbounded.
     */
    public void deposit(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() <= 0)
            throw new IllegalArgumentException("machine stack cannot be empty");
        deposit(stack, stack.getCount());
    }

    /**
     * Long-count deposit used by the load-time backfill; the stack only supplies the identity.
     */
    public void deposit(ItemStack stack, long count) {
        if (stack == null || stack.isEmpty() || count <= 0)
            throw new IllegalArgumentException("machine stack cannot be empty");
        Entry entry = findType(stack);
        if (entry == null) {
            ItemStack template = stack.copy();
            template.setTagCompound(null);
            template.setCount(1);
            entry = new Entry(template);
            entries.add(entry);
        }
        NBTTagCompound tag = normalizedTag(stack);
        if (!entry.variants.isEmpty() && entry.variants.get(entry.variants.size() - 1).tag.equals(tag)) {
            Variant tail = entry.variants.get(entry.variants.size() - 1);
            tail.count = Math.addExact(tail.count, count);
            return;
        }
        for (Variant variant : entry.variants) {
            if (variant.tag.equals(tag)) {
                variant.count = Math.addExact(variant.count, count);
                return;
            }
        }
        entry.variants.add(new Variant(tag, count));
    }

    /**
     * Withdraws up to the requested amount from the head variants of one type.
     * The caller supplies per-variant availability (count minus the occupation
     * derived from the production lines). Returns the withdrawn machines as
     * template stacks with the variant tags, split at max stack size.
     */
    public List<ItemStack> withdraw(int typeIndex, long amount, java.util.function.ToIntFunction<Variant> available) {
        Entry entry = entries.get(typeIndex);
        List<ItemStack> result = new ArrayList<>();
        long remaining = amount;
        java.util.Iterator<Variant> iterator = entry.variants.iterator();
        while (remaining > 0 && iterator.hasNext()) {
            Variant variant = iterator.next();
            long free = Math.max(0, available.applyAsInt(variant));
            long taken = Math.min(remaining, free);
            if (taken <= 0) continue;
            variant.count -= taken;
            remaining -= taken;
            while (taken > 0) {
                ItemStack stack = entry.template.copy();
                if (variant.tag.getKeySet().isEmpty()) stack.setTagCompound(null);
                else stack.setTagCompound(variant.tag.copy());
                int batch = (int) Math.min(stack.getMaxStackSize(), taken);
                stack.setCount(batch);
                result.add(stack);
                taken -= batch;
            }
            if (variant.count == 0) iterator.remove();
        }
        if (entry.variants.isEmpty()) entries.remove(typeIndex);
        return result;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", SCHEMA_VERSION);
        NBTTagList entryList = new NBTTagList();
        for (Entry entry : entries) {
            NBTTagCompound entryTag = new NBTTagCompound();
            entryTag.setTag("machine", entry.template.writeToNBT(new NBTTagCompound()));
            NBTTagList variantList = new NBTTagList();
            for (Variant variant : entry.variants) {
                NBTTagCompound variantTag = new NBTTagCompound();
                if (!variant.tag.getKeySet().isEmpty()) variantTag.setTag("tag", variant.tag);
                variantTag.setLong("count", variant.count);
                variantList.appendTag(variantTag);
            }
            entryTag.setTag("variants", variantList);
            entryList.appendTag(entryTag);
        }
        tag.setTag("entries", entryList);
        return tag;
    }

    /**
     * One NBT variant of a machine type; all machines in it are byte-identical.
     */
    public static final class Variant {
        private final NBTTagCompound tag;
        private long count;

        private Variant(NBTTagCompound tag, long count) {
            this.tag = tag;
            this.count = count;
        }

        public NBTTagCompound tag() {
            return tag.copy();
        }

        public long count() {
            return count;
        }
    }

    /**
     * One machine type: template identity plus the ordered variant queue.
     */
    public static final class Entry {
        private final ItemStack template;
        private final List<Variant> variants = new ArrayList<>();

        private Entry(ItemStack template) {
            this.template = template;
        }

        public ItemStack template() {
            return template.copy();
        }

        public List<Variant> variants() {
            return new ArrayList<>(variants);
        }

        public long count() {
            long total = 0;
            for (Variant variant : variants) total = Math.addExact(total, variant.count());
            return total;
        }
    }
}
