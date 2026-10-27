package mekanism.cloudworks.common.data;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mekanism.cloudworks.api.node.CloudworksPortDefinition;

/**
 * Virtual line boundary with per-resource replenishment targets and a durable local buffer.
 */
public final class CloudworksChannelNode {
    public static final int MAX_CONFIG_CHARS = 8192;
    private final UUID id;
    private final Mode mode;
    private final String archetypeId;
    private final Map<QIOResourceDescriptor, Long> resources = new LinkedHashMap<>();
    private CloudworksInventory buffer;
    private int x, z;
    private boolean enabled = true;
    private UUID waitingBlueprint;
    /** Special-node configuration compound (9.1 deepening); empty for plain channels. */
    private NBTTagCompound config = new NBTTagCompound();
    public CloudworksChannelNode(UUID id, Mode mode, long capacity) {
        this(id, mode, capacity, "");
    }

    public CloudworksChannelNode(UUID id, Mode mode, long capacity, String archetypeId) {
        if (id == null || mode == null) throw new IllegalArgumentException("Invalid channel");
        if (archetypeId == null || archetypeId.length() > 128) throw new IllegalArgumentException("Invalid special node");
        this.id = id;
        this.mode = mode;
        this.archetypeId = archetypeId;
        buffer = new CloudworksInventory(capacity);
    }

    public CloudworksChannelNode copyForRuntime() {
        CloudworksChannelNode copy = new CloudworksChannelNode(id, mode, buffer.capacity(), archetypeId);
        copy.resources.putAll(resources);
        copy.buffer = buffer.copy();
        copy.x = x;
        copy.z = z;
        copy.enabled = enabled;
        copy.waitingBlueprint = waitingBlueprint;
        copy.config = config.copy();
        return copy;
    }

    public static CloudworksChannelNode read(NBTTagCompound tag) {
        CloudworksDataChecks.fields(tag, 10, "buffer");
        CloudworksDataChecks.fields(tag, 8, "id", "mode");
        CloudworksDataChecks.fields(tag, 1, "enabled");
        CloudworksDataChecks.fields(tag, 3, "x", "z");

        CloudworksDataChecks.schema(tag, 1);
        CloudworksInventory buffer = CloudworksInventory.read(tag.getCompoundTag("buffer"));
        CloudworksChannelNode node = new CloudworksChannelNode(UUID.fromString(tag.getString("id")),
                Mode.valueOf(tag.getString("mode")), buffer.capacity(),
                tag.hasKey("archetype", NBT.TAG_STRING) ? tag.getString("archetype") : "");
        node.buffer = buffer;
        node.enabled = tag.getBoolean("enabled");
        if (tag.hasKey("waitingBlueprint")) {
            CloudworksDataChecks.field(tag, "waitingBlueprint", NBT.TAG_STRING);
            node.waitingBlueprint = UUID.fromString(tag.getString("waitingBlueprint"));
        }
        node.x = tag.getInteger("x");
        node.z = tag.getInteger("z");
        if (tag.hasKey("config", NBT.TAG_COMPOUND)) {
            // Size guard: the config is addon-owned data on a special node; a
            // hostile or corrupted compound must not blow up the line file.
            NBTTagCompound stored = tag.getCompoundTag("config");
            if (stored.toString().length() <= MAX_CONFIG_CHARS) node.config = stored.copy();
            else throw new IllegalArgumentException("Special node configuration too large");
        }
        NBTTagList list = CloudworksDataChecks.list(tag, "resources", NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            CloudworksDataChecks.fields(entry, 4, "amount");
            CloudworksDataChecks.fields(entry, 10, "descriptor");
            QIOResourceDescriptor descriptor = CloudworksDataChecks.descriptor(entry.getCompoundTag("descriptor"));
            long amount = entry.getLong("amount");
            if (amount <= 0 || node.resources.put(descriptor, amount) != null)
                throw new IllegalArgumentException("Invalid channel manifest");
        }
        return node;
    }

    public UUID id() {
        return id;
    }

    public Mode mode() {
        return mode;
    }

    public String archetypeId() {
        return archetypeId;
    }

    public boolean special() {
        return !archetypeId.isEmpty();
    }

    /** Live configuration compound for special nodes; empty for plain channels. */
    public NBTTagCompound config() {
        return config;
    }

    /** Replaces the configuration compound; null normalizes to empty. */
    public void setConfig(NBTTagCompound value) {
        if (value != null && value.toString().length() > MAX_CONFIG_CHARS)
            throw new IllegalArgumentException("Special node configuration too large");
        config = value == null ? new NBTTagCompound() : value.copy();
    }

    public boolean enabled() {
        return enabled && waitingBlueprint == null;
    }
    public boolean configuredEnabled() { return enabled; }

    public UUID waitingBlueprint() { return waitingBlueprint; }
    public void setWaitingBlueprint(UUID value) { waitingBlueprint = value; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    public void setPosition(int x, int z) {
        this.x = x;
        this.z = z;
    }

    public CloudworksInventory buffer() {
        return buffer;
    }

    public Map<QIOResourceDescriptor, Long> resources() {
        return Collections.unmodifiableMap(resources);
    }

    public void setResource(QIOResourceDescriptor descriptor, long amount) {
        if (descriptor == null || !descriptor.isResolved() || amount < 0)
            throw new IllegalArgumentException("Invalid channel resource");
        if (amount == 0) resources.remove(descriptor);
        else resources.put(descriptor, amount);
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", 1);
        tag.setString("id", id.toString());
        tag.setString("mode", mode.name());
        if (!archetypeId.isEmpty()) tag.setString("archetype", archetypeId);
        tag.setBoolean("enabled", enabled);
        if (waitingBlueprint != null) tag.setString("waitingBlueprint", waitingBlueprint.toString());
        tag.setInteger("x", x);
        tag.setInteger("z", z);
        if (!config.getKeySet().isEmpty()) {
            if (config.toString().length() > MAX_CONFIG_CHARS)
                throw new IllegalStateException("Special node configuration too large");
            tag.setTag("config", config.copy());
        }
        tag.setTag("buffer", buffer.write());
        NBTTagList list = new NBTTagList();
        resources.forEach((descriptor, amount) -> {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setTag("descriptor", descriptor.write());
            entry.setLong("amount", amount);
            list.appendTag(entry);
        });
        tag.setTag("resources", list);
        return tag;
    }

    public enum Mode {
        RECEIVE_INPUT, SEND_OUTPUT, TRANSFER_INPUT, TRANSFER_OUTPUT;

        public boolean input() {
            return this == RECEIVE_INPUT || this == TRANSFER_INPUT;
        }

        public boolean transfer() {
            return this == TRANSFER_INPUT || this == TRANSFER_OUTPUT;
        }

        /** Additional boundary-pair restriction; null represents a machine or an addon-defined node. */
        public static boolean allowsDirectLink(Mode source, Mode target) {
            return !(source == TRANSFER_INPUT && target == TRANSFER_OUTPUT
                    || source == TRANSFER_OUTPUT && target == TRANSFER_INPUT);
        }

        /**
         * The mode's API port shape (8.1 closeout): the mode-to-capability
         * mapping lives here, on the owning data, so the API contract stays
         * free of internal types. This is the single derivation point for
         * editor canvas, topology validator and Runtime compiler.
         */
        public CloudworksPortDefinition port(String codecId) {
            boolean canOutput = this != SEND_OUTPUT;
            boolean canInput = this != RECEIVE_INPUT;
            boolean outputAnchor = this == RECEIVE_INPUT || this == TRANSFER_INPUT;
            return CloudworksPortDefinition.channel(codecId, canOutput, canInput, outputAnchor);
        }
    }
}
