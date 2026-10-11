package mekanism.cloudworks.common.data;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Virtual line boundary with per-resource replenishment targets and a durable local buffer.
 */
public final class CloudworksChannelNode {
    private final UUID id;
    private final Mode mode;
    private final Map<QIOResourceDescriptor, Long> resources = new LinkedHashMap<>();
    private CloudworksInventory buffer;
    private int x, z;
    private boolean enabled = true;
    private UUID waitingBlueprint;
    public CloudworksChannelNode(UUID id, Mode mode, long capacity) {
        if (id == null || mode == null) throw new IllegalArgumentException("Invalid channel");
        this.id = id;
        this.mode = mode;
        buffer = new CloudworksInventory(capacity);
    }

    public CloudworksChannelNode copyForRuntime() {
        CloudworksChannelNode copy = new CloudworksChannelNode(id, mode, buffer.capacity());
        copy.resources.putAll(resources);
        copy.buffer = buffer.copy();
        copy.x = x;
        copy.z = z;
        copy.enabled = enabled;
        copy.waitingBlueprint = waitingBlueprint;
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
                Mode.valueOf(tag.getString("mode")), buffer.capacity());
        node.buffer = buffer;
        node.enabled = tag.getBoolean("enabled");
        if (tag.hasKey("waitingBlueprint")) {
            CloudworksDataChecks.field(tag, "waitingBlueprint", NBT.TAG_STRING);
            node.waitingBlueprint = UUID.fromString(tag.getString("waitingBlueprint"));
        }
        node.x = tag.getInteger("x");
        node.z = tag.getInteger("z");
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
        tag.setBoolean("enabled", enabled);
        if (waitingBlueprint != null) tag.setString("waitingBlueprint", waitingBlueprint.toString());
        tag.setInteger("x", x);
        tag.setInteger("z", z);
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
    }
}
