package mekanism.cloudworks.common.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;
import java.util.LinkedHashMap;
import java.util.Map;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.nbt.NBTTagString;

/**
 * Durable support-node placement. The input inventory stores the cost resource
 * supplied by a normal line input link; target selection is compiled from the
 * node type's targeting rule.
 */
public final class CloudworksAuxiliaryNodeData {
    public static final int SCHEMA_VERSION = 1;
    private final UUID nodeId;
    private final ItemStack type;
    private final List<UUID> linkedTargets = new ArrayList<>();
    private final CloudworksInventory input;
    private int x, z;
    private boolean active = true;
    private int range;
    private UUID transferId, sourceId;
    private final Map<String, Double> costRemainders = new LinkedHashMap<>();
    public Map<String, Double> costRemainders() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(costRemainders));
    }
    public void setCostRemainders(Map<String, Double> values) {
        for (Double value : values.values())
            if (value == null || !Double.isFinite(value) || value < 0 || value >= 1)
                throw new IllegalArgumentException("Invalid auxiliary cost remainder");
        costRemainders.clear(); costRemainders.putAll(values);
    }

    public CloudworksAuxiliaryNodeData(UUID nodeId, ItemStack type, int x, int z) {
        if (nodeId == null || type == null || type.isEmpty()) throw new IllegalArgumentException("Invalid auxiliary node");
        this.nodeId = nodeId;
        this.type = type.copy();
        this.type.setCount(1);
        this.x = x;
        this.z = z;
        this.input = CloudworksInventory.perResourceCapacity(10_000);
    }

    private CloudworksAuxiliaryNodeData(CloudworksAuxiliaryNodeData source) {
        nodeId = source.nodeId;
        type = source.type.copy();
        x = source.x;
        z = source.z;
        active = source.active;
        range = source.range;
        transferId = source.transferId;
        sourceId = source.sourceId;
        input = source.input.copy();
        linkedTargets.addAll(source.linkedTargets);
        costRemainders.putAll(source.costRemainders);
    }

    public CloudworksAuxiliaryNodeData copyForRuntime() {
        return new CloudworksAuxiliaryNodeData(this);
    }

    public static CloudworksAuxiliaryNodeData read(NBTTagCompound tag) {
        CloudworksDataChecks.schema(tag, SCHEMA_VERSION);
        CloudworksDataChecks.fields(tag, 8, "id");
        CloudworksDataChecks.fields(tag, 10, "type", "input");
        CloudworksAuxiliaryNodeData node = new CloudworksAuxiliaryNodeData(
                UUID.fromString(tag.getString("id")),
                new ItemStack(tag.getCompoundTag("type")),
                tag.getInteger("x"), tag.getInteger("z"));
        node.active = !tag.hasKey("active", NBT.TAG_BYTE) || tag.getBoolean("active");
        node.range = tag.hasKey("range", NBT.TAG_INT) ? tag.getInteger("range") : 0;
        if (tag.hasKey("transferId", NBT.TAG_STRING)) node.transferId = UUID.fromString(tag.getString("transferId"));
        if (tag.hasKey("sourceId", NBT.TAG_STRING)) node.sourceId = UUID.fromString(tag.getString("sourceId"));
        node.input.setCapacity(CloudworksInventory.read(tag.getCompoundTag("input")).capacity());
        CloudworksInventory stored = CloudworksInventory.read(tag.getCompoundTag("input"));
        for (Map.Entry<QIOResourceDescriptor, Long> entry : stored.snapshot().entrySet())
            node.input.insertIgnoringTypeCap(entry.getKey(), entry.getValue());
        if (tag.hasKey("targets", NBT.TAG_LIST)) {
            NBTTagList targets = tag.getTagList("targets", NBT.TAG_STRING);
            Set<UUID> seen = new LinkedHashSet<>();
            for (int i = 0; i < targets.tagCount(); i++) {
                UUID id = UUID.fromString(targets.getStringTagAt(i));
                if (!seen.add(id)) throw new IllegalArgumentException("Duplicate auxiliary target");
                node.linkedTargets.add(id);
            }
        }
        NBTTagCompound remainders = tag.getCompoundTag("costRemainders");
        Map<String, Double> decoded = new LinkedHashMap<>();
        for (String key : remainders.getKeySet()) decoded.put(key, remainders.getDouble(key));
        node.setCostRemainders(decoded);
        return node;
    }

    public UUID nodeId() { return nodeId; }
    public ItemStack type() { return type.copy(); }
    public int x() { return x; }
    public int z() { return z; }
    public boolean active() { return active; }
    public int range() { return range; }
    public CloudworksInventory input() { return input; }
    public List<UUID> linkedTargets() {
        return Collections.unmodifiableList(new ArrayList<>(linkedTargets));
    }
    public UUID transferId() { return transferId; }
    public UUID sourceId() { return sourceId; }

    public void setPosition(int x, int z) { this.x = x; this.z = z; }
    public void setActive(boolean value) { active = value; }
    public void setRange(int value) {
        if (value < 0 || value > 1_000_000) throw new IllegalArgumentException("Invalid auxiliary range");
        range = value;
    }
    public void setLinkedTargets(List<UUID> targets) {
        linkedTargets.clear();
        Set<UUID> seen = new LinkedHashSet<>();
        for (UUID target : targets) if (target == null || !seen.add(target))
            throw new IllegalArgumentException("Invalid auxiliary target");
        linkedTargets.addAll(targets);
    }
    public void setUploadOrigin(UUID transferId, UUID sourceId) {
        this.transferId = transferId;
        this.sourceId = sourceId;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", SCHEMA_VERSION);
        tag.setString("id", nodeId.toString());
        tag.setTag("type", type.writeToNBT(new NBTTagCompound()));
        tag.setInteger("x", x);
        tag.setInteger("z", z);
        tag.setBoolean("active", active);
        tag.setInteger("range", range);
        if (transferId != null) tag.setString("transferId", transferId.toString());
        if (sourceId != null) tag.setString("sourceId", sourceId.toString());
        tag.setTag("input", input.write());
        NBTTagList targets = new NBTTagList();
        for (UUID id : linkedTargets) targets.appendTag(new NBTTagString(id.toString()));
        tag.setTag("targets", targets);
        NBTTagCompound remainders = new NBTTagCompound();
        costRemainders.forEach(remainders::setDouble);
        tag.setTag("costRemainders", remainders);
        return tag;
    }
}
