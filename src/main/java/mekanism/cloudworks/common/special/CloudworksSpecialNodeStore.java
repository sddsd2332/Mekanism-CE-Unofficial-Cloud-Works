package mekanism.cloudworks.common.special;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import java.util.Objects;
import mekanism.common.Mekanism;

/** Reads once per owner; publication and writing are owned by the normal cloud intent queue. */
public final class CloudworksSpecialNodeStore {
    public static final int SCHEMA_VERSION = 1;
    public static final String NODE_FILE = "special_nodes.dat";
    public static final String RESOURCE_FILE = "infinite_resources.dat";
    private final Path directory;
    private final Function<Path, NBTTagCompound> reader;
    private Snapshot snapshot;
    private boolean recoveryBlocked;

    public CloudworksSpecialNodeStore(Path directory, Function<Path, NBTTagCompound> reader) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.reader = Objects.requireNonNull(reader);
    }

    public Snapshot load() {
        if (snapshot == null) {
            Path nodes = directory.resolve(NODE_FILE), resources = directory.resolve(RESOURCE_FILE);
            NBTTagCompound nodeTag = loadFile(nodes, CloudworksSpecialNodeStore::validateNodes);
            Map<QIOResourceDescriptor, Entry> entries = new LinkedHashMap<>();
            NBTTagCompound resourceTag = loadFile(resources,
                    tag -> entries.putAll(parseResources(tag, resources)));
            if (resourceTag.isEmpty()) entries.clear();
            snapshot = new Snapshot(nodeTag, resourceTag, entries);
        }
        return snapshot;
    }

    public void publish(Snapshot value) {
        requireWritable();
        snapshot = Objects.requireNonNull(value);
    }

    public void requireWritable() {
        if (recoveryBlocked) throw new IllegalStateException("cloudworks.error.storage");
    }

    private NBTTagCompound loadFile(Path file, Consumer<NBTTagCompound> validator) {
        if (!Files.exists(file)) return new NBTTagCompound();
        try {
            NBTTagCompound tag = reader.apply(file);
            validator.accept(tag);
            return tag;
        } catch (RuntimeException invalid) {
            quarantine(file, invalid);
            return new NBTTagCompound();
        }
    }

    private void quarantine(Path file, RuntimeException failure) {
        Path backup = null;
        try {
            if (!file.getParent().equals(directory) || !file.getFileName().toString().equals(NODE_FILE)
                    && !file.getFileName().toString().equals(RESOURCE_FILE))
                throw new IOException("Refusing recovery outside the special-node files");
            String suffix = ".corrupt-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            backup = file.resolveSibling(file.getFileName() + suffix);
            for (int index = 1; Files.exists(backup); index++)
                backup = file.resolveSibling(file.getFileName() + suffix + "-" + index);
            Files.move(file, backup);
        } catch (IOException recoveryFailure) {
            failure.addSuppressed(recoveryFailure);
            recoveryBlocked = true;
        }
        if (Mekanism.logger != null)
            Mekanism.logger.warn("Cloudworks special-node recovery for owner {}: {} -> {}",
                    directory.getParent().getFileName(), file, recoveryBlocked ? "backup failed; writes blocked" : backup,
                    failure);
    }

    public static Snapshot parse(NBTTagCompound nodes, NBTTagCompound resources) {
        if (resources.isEmpty()) {
            if (!nodes.isEmpty()) validateNodes(nodes);
            return new Snapshot(nodes, new NBTTagCompound(), Collections.emptyMap());
        }
        validateNodes(nodes);
        return new Snapshot(nodes, resources, parseResources(resources, null));
    }

    private static Map<QIOResourceDescriptor, Entry> parseResources(NBTTagCompound resources,
            Path sourceFile) {
        require(resources, "schema", 3);
        if (resources.getInteger("schema") != SCHEMA_VERSION
                || !CloudworksSpecialNodeArchetypes.INFINITE_RESOURCES.toString().equals(resources.getString("id"))
                || !"SOURCE".equals(resources.getString("type")))
            throw invalid();
        require(resources, "id", 8);
        require(resources, "displayName", 8);
        require(resources, "icon", 10);
        require(resources, "data", 10);
        require(resources, "resources", 10);
        NBTTagList types = list(resources, "resourceTypes", 8);
        NBTTagCompound groups = resources.getCompoundTag("resources");
        Map<QIOResourceDescriptor, Entry> entries = new LinkedHashMap<>();
        Set<String> seenTypes = new LinkedHashSet<>();
        for (int i = 0; i < types.tagCount(); i++) {
            String type = types.getStringTagAt(i);
            if (type.isEmpty() || !seenTypes.add(type) || !groups.hasKey(type, 10)) throw invalid();
            NBTTagCompound group = groups.getCompoundTag(type);
            for (String key : group.getKeySet()) {
                require(group, key, 10);
                NBTTagCompound tag = group.getCompoundTag(key);
                require(tag, "resourceId", 8);
                require(tag, "descriptor", 10);
                require(tag, "data", 10);
                QIOResourceDescriptor descriptor = QIOResourceDescriptor.read(tag.getCompoundTag("descriptor"));
                if (!descriptor.isResolved()) {
                    if (sourceFile == null) throw invalid();
                    if (Mekanism.logger != null)
                        Mekanism.logger.warn(
                                "Skipping unavailable Cloudworks infinite resource {}:{} for owner {} in {}",
                                type, tag.getString("resourceId"),
                                sourceFile.getParent().getParent().getFileName(), sourceFile);
                    continue;
                }
                Entry entry = new Entry(type, tag.getString("resourceId"), descriptor, tag.getCompoundTag("data"));
                if (entries.put(descriptor, entry) != null) throw invalid();
            }
        }
        if (!seenTypes.equals(groups.getKeySet())) throw invalid();
        return entries;
    }

    private static void validateNodes(NBTTagCompound nodes) {
        require(nodes, "schema", 3);
        if (nodes.getInteger("schema") != SCHEMA_VERSION) throw invalid();
        NBTTagList definitions = list(nodes, "nodes", 10);
        Set<String> ids = new LinkedHashSet<>();
        boolean sourcePresent = false;
        for (int i = 0; i < definitions.tagCount(); i++) {
            NBTTagCompound definition = definitions.getCompoundTagAt(i);
            require(definition, "id", 8);
            require(definition, "displayName", 8);
            require(definition, "icon", 10);
            require(definition, "type", 8);
            require(definition, "data", 10);
            String id = definition.getString("id"), type = definition.getString("type");
            if (id.isEmpty() || !ids.add(id) || !("SOURCE".equals(type) || "SINK".equals(type))) throw invalid();
            if (CloudworksSpecialNodeArchetypes.INFINITE_RESOURCES.toString().equals(id)) {
                if (!"SOURCE".equals(type)) throw invalid();
                sourcePresent = true;
            }
        }
        if (!sourcePresent) throw invalid();
    }

    private static void require(NBTTagCompound tag, String name, int type) {
        if (!tag.hasKey(name, type)) throw invalid();
    }

    private static NBTTagList list(NBTTagCompound tag, String name, int type) {
        require(tag, name, 9);
        NBTTagList list = (NBTTagList) tag.getTag(name);
        if (list.tagCount() > 0 && list.getTagType() != type) throw invalid();
        return list;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid special-node data file");
    }

    private static NBTTagCompound header() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", CloudworksSpecialNodeArchetypes.INFINITE_RESOURCES.toString());
        tag.setString("displayName", "cloudworks.special.infinite_resources");
        tag.setTag("icon", new ItemStack(Items.PAPER).writeToNBT(new NBTTagCompound()));
        tag.setString("type", "SOURCE");
        tag.setTag("data", new NBTTagCompound());
        return tag;
    }

    public enum AddResult { ADDED, INCREASED, DUPLICATE }

    public static final class Change {
        public final AddResult result;
        public final Snapshot next;
        private Change(AddResult result, Snapshot next) { this.result = result; this.next = next; }
    }

    public static final class Snapshot {
        public final boolean available;
        public final Map<QIOResourceDescriptor, Entry> resources;
        private final NBTTagCompound nodes, resourceTag;

        private Snapshot(NBTTagCompound nodes, NBTTagCompound resourceTag,
                Map<QIOResourceDescriptor, Entry> resources) {
            this.nodes = nodes.copy();
            this.resourceTag = resourceTag.copy();
            this.resources = Collections.unmodifiableMap(new LinkedHashMap<>(resources));
            available = !resources.isEmpty();
        }

        public static Snapshot empty() {
            return new Snapshot(new NBTTagCompound(), new NBTTagCompound(), Collections.emptyMap());
        }

        public NBTTagCompound nodesTag() { return nodes.copy(); }
        public NBTTagCompound resourcesTag() { return resourceTag.copy(); }

        public Change add(CloudworksInfiniteResourceCardData card) {
            Map<QIOResourceDescriptor, Entry> next = new LinkedHashMap<>(resources);
            Entry existing = next.get(card.descriptor);
            NBTTagCompound data = card.data();
            AddResult result = AddResult.ADDED;
            if (existing != null) {
                CloudworksInfiniteResourceRules rules = CloudworksInfiniteResourceRules.parse(data);
                if (!CloudworksInfiniteResourceRules.improves(existing.rules, rules))
                    return new Change(AddResult.DUPLICATE, this);
                data = CloudworksInfiniteResourceRules.best(existing.rules, rules).applyTo(existing.data());
                result = AddResult.INCREASED;
            }
            next.put(card.descriptor, new Entry(existing == null ? card.type : existing.type,
                    existing == null ? card.resourceId : existing.resourceId, card.descriptor, data));
            NBTTagCompound nodeTag = nodes.copy();
            if (nodeTag.isEmpty()) {
                nodeTag.setInteger("schema", SCHEMA_VERSION);
                NBTTagList definitions = new NBTTagList();
                definitions.appendTag(header());
                nodeTag.setTag("nodes", definitions);
            }
            NBTTagCompound root = resourceTag.isEmpty() ? header() : resourceTag.copy();
            root.setInteger("schema", SCHEMA_VERSION);
            NBTTagList types = new NBTTagList();
            NBTTagCompound groups = new NBTTagCompound();
            for (Entry entry : next.values()) {
                if (!groups.hasKey(entry.type)) {
                    groups.setTag(entry.type, new NBTTagCompound());
                    types.appendTag(new NBTTagString(entry.type));
                }
                NBTTagCompound group = groups.getCompoundTag(entry.type);
                String key = entry.resourceId;
                int variant = 0;
                while (group.hasKey(key)) key = entry.resourceId + "#" + ++variant;
                NBTTagCompound value = new NBTTagCompound();
                value.setString("resourceId", entry.resourceId);
                value.setTag("descriptor", entry.descriptor.write());
                value.setTag("data", entry.data());
                group.setTag(key, value);
            }
            root.setTag("resourceTypes", types);
            root.setTag("resources", groups);
            return new Change(result, new Snapshot(nodeTag, root, next));
        }
    }

    public static final class Entry {
        public final String type, resourceId;
        public final QIOResourceDescriptor descriptor;
        public final CloudworksInfiniteResourceRules rules;
        private final NBTTagCompound data;

        private Entry(String type, String id, QIOResourceDescriptor descriptor, NBTTagCompound data) {
            this.type = type;
            resourceId = id;
            this.descriptor = descriptor;
            this.data = data.copy();
            rules = CloudworksInfiniteResourceRules.parse(data);
        }

        public NBTTagCompound data() { return data.copy(); }
        public long outputAmount(long room) { return Math.min(room, rules.outputAmount); }
    }
}
