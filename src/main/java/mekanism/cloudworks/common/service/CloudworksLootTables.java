package mekanism.cloudworks.common.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemSword;
import net.minecraft.util.ResourceLocation;

/**
 * Pipeline stages 2-3 (frozen): entity and chest loot tables read from the
 * classpath as plain JSON (the same resource path vanilla's loader uses),
 * fingerprinted with SHA-256 and re-interpreted into a fully data-driven IR.
 * The cloud runtime rolls kills against the frozen IR only — zero live world
 * access. Per-roll semantics preserve the distribution: pools roll
 * independently, each pool rolls its count range and draws one weighted
 * entry; independent pool rolls remain separate.
 *
 * First-version rulings (documented in the batch record):
 * - machine kills count as player kills; looting is always 0
 * - non-minecraft conditions or functions reject the whole pool (IC2 precedent)
 * - vanilla conditions that need live context evaluate as unmet unless they
 *   can be reduced to a plain probability
 * - equipment drops (armor/swords/bows) are skipped; experience never rolls
 * - entries whose item is missing from the registry are skipped
 */
public final class CloudworksLootTables {

    /** One rolled drop: item id, metadata and count. */
    public static final class LootDrop {
        public final ResourceLocation item;
        public final int meta;
        public final int count;

        private LootDrop(ResourceLocation item, int meta, int count) {
            this.item = item;
            this.meta = meta;
            this.count = count;
        }

        @Override public String toString() { return count + "x " + item + "@" + meta; }
    }

    /** Package-private drop factory for the manual injection layer. */
    static LootDrop drop(ResourceLocation item, int meta, int count) {
        return new LootDrop(item, meta, count);
    }

    private static final class Entry {
        final ResourceLocation item;
        final int meta;
        final int minCount;
        final int maxCount;
        final int weight;

        Entry(ResourceLocation item, int meta, int minCount, int maxCount, int weight) {
            this.item = item;
            this.meta = meta;
            this.minCount = minCount;
            this.maxCount = maxCount;
            this.weight = weight;
        }
    }

    private static final class Pool {
        final float chance;          // 1 = unconditional
        final int minRolls;
        final int maxRolls;
        final List<Entry> entries;

        Pool(float chance, int minRolls, int maxRolls, List<Entry> entries) {
            this.chance = chance;
            this.minRolls = minRolls;
            this.maxRolls = maxRolls;
            this.entries = entries;
        }
    }

    /** Immutable frozen loot table with a SHA-256 fingerprint of the source JSON. */
    public static final class FrozenLootTable {
        private final ResourceLocation id;
        private final List<Pool> pools;
        private final String fingerprint;

        private FrozenLootTable(ResourceLocation id, List<Pool> pools, String fingerprint) {
            this.id = id;
            this.pools = Collections.unmodifiableList(new ArrayList<>(pools));
            this.fingerprint = fingerprint;
        }

        public ResourceLocation id() { return id; }
        public String fingerprint() { return fingerprint; }
        public int poolCount() { return pools.size(); }

        /** Rolls one kill against every pool independently; each pool result remains a separate drop event. */
        public List<LootDrop> roll(Random random) {
            List<LootDrop> drops = new ArrayList<>();
            for (Pool pool : pools) {
                if (pool.chance < 1F && random.nextFloat() >= pool.chance) continue;
                int rolls = pool.minRolls >= pool.maxRolls ? pool.minRolls
                        : pool.minRolls + random.nextInt(pool.maxRolls - pool.minRolls + 1);
                for (int roll = 0; roll < rolls; roll++)
                    rollOne(pool, random, drops);
            }
            return drops;
        }

        private void rollOne(Pool pool, Random random, List<LootDrop> drops) {
            long total = 0;
            for (Entry entry : pool.entries) total += entry.weight;
            if (total <= 0) return;
            long cursor = (long) (random.nextDouble() * total);
            Entry chosen = pool.entries.get(pool.entries.size() - 1);
            for (Entry entry : pool.entries) {
                cursor -= entry.weight;
                if (cursor < 0) { chosen = entry; break; }
            }
            if (chosen.item == null) return; // weighted empty entry: no drop
            int count = chosen.minCount >= chosen.maxCount ? chosen.minCount
                    : chosen.minCount + random.nextInt(chosen.maxCount - chosen.minCount + 1);
            if (count <= 0) return;
            drops.add(new LootDrop(chosen.item, chosen.meta, count));
        }
    }

    private static final Map<ResourceLocation, FrozenLootTable> CACHE = new ConcurrentHashMap<>();

    private CloudworksLootTables() {}

    /**
     * Frozen loot table by id ("minecraft:entities/zombie", "minecraft:chests/nether_bridge").
     * Absent, malformed or entirely rejected tables resolve to an id-preserving
     * empty table (poolCount 0) — callers treat that as the stage-2 refusal.
     */
    public static FrozenLootTable table(ResourceLocation id) {
        return CACHE.computeIfAbsent(id, CloudworksLootTables::load);
    }

    /** Conventional entity loot-table id for a registry entity name ("minecraft:zombie" -> entities/zombie). */
    public static ResourceLocation entityIdTable(String entityName) {
        ResourceLocation entity = new ResourceLocation(entityName);
        return new ResourceLocation(entity.getNamespace(), "entities/" + entity.getPath());
    }

    /** Stage-2 helper: entity name -> frozen table; missing data has poolCount 0. */
    public static FrozenLootTable entityTable(String entityName) {
        return table(entityIdTable(entityName));
    }

    /** Parses loot JSON directly (tests, declared payloads); null when rejected outright. */
    static FrozenLootTable parse(ResourceLocation id, String json) {
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            List<Pool> pools = new ArrayList<>();
            if (root.has("pools") && root.get("pools").isJsonArray())
                for (JsonElement poolElement : root.getAsJsonArray("pools")) {
                    Pool pool = parsePool(poolElement);
                    if (pool != null) pools.add(pool);
                }
            return new FrozenLootTable(id, pools, sha256(json));
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static Pool parsePool(JsonElement poolElement) {
        JsonObject poolJson = poolElement.getAsJsonObject();
        float chance = 1F;
        if (poolJson.has("conditions") && !poolJson.get("conditions").isJsonArray()) return null;
        if (poolJson.has("conditions")) {
            for (JsonElement conditionElement : poolJson.getAsJsonArray("conditions")) {
                JsonObject condition = conditionElement.getAsJsonObject();
                String type = qualified(condition, "condition");
                if (type == null) return null; // unstructured condition: reject the pool
                if (!"minecraft".equals(namespace(type))) return null; // modded condition: reject the pool
                switch (type) {
                    case "minecraft:random_chance":
                        chance *= clampChance(condition, "chance");
                        break;
                    case "minecraft:random_chance_with_looting":
                        // looting is always 0 for machine kills; the base chance stays
                        chance *= clampChance(condition, "chance");
                        break;
                    case "minecraft:killed_by_player":
                        break; // machine kills count as player kills (ruling)
                    default:
                        return null; // context-dependent vanilla condition: unmet -> pool skipped
                }
            }
        }
        int[] rolls = range(poolJson, "rolls", 0);
        if (rolls == null) rolls = new int[] {1, 1};
        List<Entry> entries = new ArrayList<>();
        if (poolJson.has("entries") && !poolJson.get("entries").isJsonArray()) return null;
        if (poolJson.has("entries")) {
            for (JsonElement entryElement : poolJson.getAsJsonArray("entries")) {
                ParsedEntry parsed = parseEntry(entryElement);
                if (parsed.rejectPool) return null;
                if (parsed.entry != null) entries.add(parsed.entry);
            }
        }
        if (entries.isEmpty()) return null;
        return new Pool(chance, rolls[0], rolls[1], entries);
    }

    private static final class ParsedEntry {
        final Entry entry;
        final boolean rejectPool;

        ParsedEntry(Entry entry, boolean rejectPool) {
            this.entry = entry;
            this.rejectPool = rejectPool;
        }

        static ParsedEntry keep(Entry entry) { return new ParsedEntry(entry, false); }
        static ParsedEntry skip() { return new ParsedEntry(null, false); }
        static ParsedEntry reject() { return new ParsedEntry(null, true); }
    }

    private static ParsedEntry parseEntry(JsonElement entryElement) {
        JsonObject entryJson = entryElement.getAsJsonObject();
        String type = qualified(entryJson, "type");
        if (type == null) return ParsedEntry.skip();
        if (!"minecraft".equals(namespace(type))) return ParsedEntry.skip(); // modded entry type: skip the entry
        if (type.equals("minecraft:empty")) {
            int emptyWeight = entryJson.has("weight") ? Math.max(0, entryJson.get("weight").getAsInt()) : 1;
            return ParsedEntry.keep(new Entry(null, 0, 0, 0, emptyWeight)); // weighted no-drop
        }
        if (!type.equals("minecraft:item")) return ParsedEntry.skip(); // nested tables/groups: out of first version
        if (!entryJson.has("name")) return ParsedEntry.skip();
        ResourceLocation item;
        try {
            item = new ResourceLocation(entryJson.get("name").getAsString());
        } catch (RuntimeException invalid) {
            return ParsedEntry.skip();
        }
        Item registered = Item.REGISTRY.getObject(item);
        if (registered == null) return ParsedEntry.skip(); // missing item (unloaded mod): refuse the entry
        if (isEquipment(item, registered))
            return ParsedEntry.skip(); // equipment drops stay out of the first version
        int weight = entryJson.has("weight") ? Math.max(0, entryJson.get("weight").getAsInt()) : 1;
        int meta = 0, min = 1, max = 1;
        if (entryJson.has("functions") && !entryJson.get("functions").isJsonArray())
            return ParsedEntry.reject();
        if (entryJson.has("functions")) {
            for (JsonElement functionElement : entryJson.getAsJsonArray("functions")) {
                JsonObject function = functionElement.getAsJsonObject();
                String functionType = qualified(function, "function");
                if (functionType == null || !"minecraft".equals(namespace(functionType)))
                    return ParsedEntry.reject(); // unsafe functions reject the complete pool
                switch (functionType) {
                    case "minecraft:set_count": {
                        int[] count = range(function, "count", 1);
                        if (count != null) { min = count[0]; max = count[1]; }
                        break;
                    }
                    case "minecraft:set_data": {
                        int[] data = range(function, "data", 0);
                        if (data != null) meta = data[0];
                        break;
                    }
                    case "minecraft:looting_enchant":
                        // Machine kills use looting level zero; the vanilla
                        // function therefore contributes no extra count.
                        break;
                    default:
                        return ParsedEntry.reject(); // unknown or context-sensitive functions are unsafe
                }
            }
        }
        if (min < 0) min = 0;
        if (max < min) max = min;
        return ParsedEntry.keep(new Entry(item, meta, min, max, weight));
    }

    private static boolean isEquipment(ResourceLocation id, Item item) {
        String path = id.getPath();
        return item instanceof ItemArmor || item instanceof ItemSword || item instanceof ItemBow
                || path.endsWith("_horse_armor") || "saddle".equals(path) || "elytra".equals(path);
    }

    private static String qualified(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()) return null;
        String raw = object.get(field).getAsString();
        if (raw == null || raw.isEmpty()) return null;
        return raw.indexOf(':') >= 0 ? raw : "minecraft:" + raw;
    }

    private static String namespace(String qualified) {
        int separator = qualified.indexOf(':');
        return separator < 0 ? qualified : qualified.substring(0, separator);
    }

    private static float clampChance(JsonObject condition, String field) {
        if (!condition.has(field)) return 0F;
        try {
            return Math.max(0F, Math.min(1F, condition.get(field).getAsFloat()));
        } catch (RuntimeException invalid) {
            return 0F;
        }
    }

    private static int[] range(JsonObject object, String field, int fallback) {
        if (!object.has(field)) return new int[] {fallback, fallback};
        JsonElement element = object.get(field);
        if (element.isJsonPrimitive()) {
            int value = Math.max(0, element.getAsInt());
            return new int[] {value, value};
        }
        if (element.isJsonObject()) {
            JsonObject rangeJson = element.getAsJsonObject();
            int min = rangeJson.has("min") ? rangeJson.get("min").getAsInt() : fallback;
            int max = rangeJson.has("max") ? rangeJson.get("max").getAsInt() : min;
            return new int[] {Math.max(0, min), Math.max(Math.max(0, min), max)};
        }
        return new int[] {fallback, fallback};
    }

    private static FrozenLootTable load(ResourceLocation id) {
        String path = "/assets/" + id.getNamespace() + "/loot_tables/" + id.getPath() + ".json";
        try (InputStream stream = CloudworksLootTables.class.getResourceAsStream(path)) {
            if (stream == null) return empty(id);
            StringBuilder json = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) json.append(line).append((char) 10);
            }
            FrozenLootTable table = parse(id, json.toString());
            return table == null ? empty(id) : table;
        } catch (IOException unreadable) {
            return empty(id);
        }
    }

    private static FrozenLootTable empty(ResourceLocation id) {
        return new FrozenLootTable(id, Collections.emptyList(), sha256("{}"));
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Test-only: clears the frozen cache. */
    public static void resetForTests() {
        CACHE.clear();
    }
}
