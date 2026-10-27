package mekanism.cloudworks.common.data;

import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.registry.MachineKey;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.Iterator;

/**
 * Owner-level machine cache: the single physical home of every deposited
 * machine. Entries use the registered machine identity (item, damage and
 * selected identity NBT); each entry keeps an ordered queue of complete NBT
 * variants. Deposits merge into the existing variant position (identical
 * machines are fungible) or append a new variant at the tail; withdrawals
 * drain from the head. Production lines never appear here — claims are derived
 * from the line files by summing machineCount per type.
 */
public final class CloudworksMachineCache {
    public static final int SCHEMA_VERSION = 3;
    private static final int LEGACY_SCHEMA_VERSION = 1;
    /** Schema 2 and earlier rejected entries whose item vanished; 3 keeps them frozen. */
    private static final int PRE_FREEZE_SCHEMA_VERSION = 2;
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

    private static MachineKey identityKey(ItemStack machine) {
        ItemStack normalized = machine.copy();
        NBTTagCompound tag = normalized.getTagCompound();
        if (tag != null) {
            tag.removeTag(FORGE_CAPS);
            if (tag.getKeySet().isEmpty()) normalized.setTagCompound(null);
        }
        return CloudworksRegistries.identityKey(normalized);
    }

    public static CloudworksMachineCache read(NBTTagCompound tag) {
        CloudworksDataChecks.field(tag, "schema", NBT.TAG_INT);
        int schema = tag.getInteger("schema");
        if (schema != LEGACY_SCHEMA_VERSION && schema != SCHEMA_VERSION)
            throw new IllegalArgumentException("Unknown machine cache schema");
        CloudworksMachineCache cache = new CloudworksMachineCache();
        NBTTagList entryList = CloudworksDataChecks.list(tag, "entries", NBT.TAG_COMPOUND);
        Set<MachineKey> declaredTypes = new HashSet<>();
        for (int i = 0; i < entryList.tagCount(); i++) {
            NBTTagCompound entryTag = entryList.getCompoundTagAt(i);
            CloudworksDataChecks.field(entryTag, "machine", NBT.TAG_COMPOUND);
            NBTTagCompound machineTag = entryTag.getCompoundTag("machine");
            ItemStack template = new ItemStack(machineTag);
            if (template.isEmpty()) {
                // B5: the item came from a removed mod. Keep the entry frozen with
                // its raw NBT so a returning mod restores the machines on next load;
                // native namespaces cannot vanish while this mod runs.
                if (schema < SCHEMA_VERSION || !hasExternalNamespace(machineTag))
                    throw new IllegalArgumentException("Invalid machine template");
                cache.entries.add(Entry.frozen(entryTag.copy()));
                continue;
            }
            MachineKey declaredType = identityKey(template);
            if (!declaredTypes.add(declaredType)) throw new IllegalArgumentException("Duplicate machine type");
            NBTTagList variantList = CloudworksDataChecks.list(entryTag, "variants", NBT.TAG_COMPOUND);
            // Tag identity is value based (NBTTagCompound equals/hashCode compare the
            // entry set), so a differently ordered serialization of the same tag is
            // still recognised; a string form would not be.
            Set<NBTTagCompound> seenTags = new HashSet<>();
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
                ItemStack variantStack = template.copy();
                variantStack.setTagCompound(variantNbt.getKeySet().isEmpty() ? null : variantNbt.copy());
                MachineKey variantType = identityKey(variantStack);
                if (schema == SCHEMA_VERSION && !declaredType.equals(variantType))
                    throw new IllegalArgumentException("Machine variant identity does not match its type");
                Entry entry = cache.findType(variantStack);
                if (entry == null) {
                    entry = new Entry(variantType.displayStack(), variantType);
                    cache.entries.add(entry);
                } else if (findVariant(entry, variantStack) != null) {
                    throw new IllegalArgumentException("Duplicate machine variant");
                }
                entry.variants.add(new Variant(variantNbt, count));
            }
            if (variantList.tagCount() == 0) throw new IllegalArgumentException("Machine entry without variants");
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
     * Type entry for the machine's registered identity, or null.
     */
    public Entry findType(ItemStack machine) {
        return findType(identityKey(machine));
    }

    private Entry findType(MachineKey key) {
        for (Entry entry : entries)
            if (!entry.frozen() && entry.identity.equals(key)) return entry;
        return null;
    }

    /**
     * Index of the type entry for the machine's registered identity, or -1.
     */
    public int typeIndex(ItemStack machine) {
        MachineKey key = identityKey(machine);
        for (int i = 0; i < entries.size(); i++)
            if (!entries.get(i).frozen() && entries.get(i).identity.equals(key)) return i;
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
        MachineKey key = identityKey(stack);
        Entry entry = findType(key);
        if (entry == null) {
            entry = new Entry(key.displayStack(), key);
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
    public List<ItemStack> withdraw(int typeIndex, long amount, ToIntFunction<Variant> available) {
        Entry entry = entries.get(typeIndex);
        if (entry.frozen()) return new ArrayList<>(); // B5: no item exists to hand back
        List<ItemStack> result = new ArrayList<>();
        long remaining = amount;
        Iterator<Variant> iterator = entry.variants.iterator();
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

    /** Moves exact variant counts to another virtual store without materializing ItemStacks. */
    public long extractVariant(ItemStack template, long amount) {
        if (amount <= 0) return 0;
        Entry entry = findType(template);
        if (entry == null) return 0;
        Variant variant = findVariant(entry, template);
        if (variant == null) return 0;
        long taken = Math.min(amount, variant.count);
        variant.count -= taken;
        if (variant.count == 0) entry.variants.remove(variant);
        if (entry.variants.isEmpty()) entries.remove(entry);
        return taken;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", SCHEMA_VERSION);
        NBTTagList entryList = new NBTTagList();
        for (Entry entry : entries) {
            NBTTagCompound entryTag = new NBTTagCompound();
            if (entry.frozen()) {
                // B5: byte-identical round-trip (machine + variants) so a returning
                // mod restores the machines on the next load.
                entryList.appendTag(entry.frozenNbt.copy());
                continue;
            }
            entryTag.setTag("machine", entry.template.writeToNBT(new NBTTagCompound()));
            NBTTagList variantList = new NBTTagList();
            for (Variant variant : entry.variants) {
                NBTTagCompound variantTag = new NBTTagCompound();
                if (!variant.tag.getKeySet().isEmpty()) variantTag.setTag("tag", variant.tag.copy());
                variantTag.setLong("count", variant.count);
                variantList.appendTag(variantTag);
            }
            entryTag.setTag("variants", variantList);
            entryList.appendTag(entryTag);
        }
        tag.setTag("entries", entryList);
        return tag;
    }

    /** True when the raw stack NBT names a non-native item namespace (B5 freeze predicate). */
    static boolean hasExternalNamespace(NBTTagCompound machineTag) {
        if (!machineTag.hasKey("id", 8)) return false;
        String id = machineTag.getString("id");
        int separator = id.indexOf(':');
        if (separator <= 0) return false;
        String namespace = id.substring(0, separator);
        return !"minecraft".equals(namespace) && !"cloudworks".equals(namespace);
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
        private final MachineKey identity;
        /**
         * B5 frozen entries: the complete raw entry tag (machine template plus
         * every variant and count) of a removed mod's item; null on live entries.
         */
        private final NBTTagCompound frozenNbt;
        private final List<Variant> variants = new ArrayList<>();

        private Entry(ItemStack template, MachineKey identity) {
            this(template, identity, null);
        }

        private Entry(ItemStack template, MachineKey identity, NBTTagCompound frozenNbt) {
            this.template = template;
            this.identity = identity;
            this.frozenNbt = frozenNbt;
        }

        static Entry frozen(NBTTagCompound rawEntryTag) {
            return new Entry(ItemStack.EMPTY, null, rawEntryTag);
        }

        /** True when the item came from a removed mod; the raw entry survives via {@link #frozenNbt()}. */
        public boolean frozen() { return frozenNbt != null; }

        public NBTTagCompound frozenNbt() { return frozenNbt == null ? null : frozenNbt.copy(); }

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
