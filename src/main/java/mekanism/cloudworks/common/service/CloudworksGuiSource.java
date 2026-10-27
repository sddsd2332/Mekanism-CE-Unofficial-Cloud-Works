package mekanism.cloudworks.common.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mekanism.cloudworks.common.data.CloudworksData;
import mekanism.cloudworks.common.data.CloudworksMachineCache;
import mekanism.cloudworks.common.data.CloudworksRecycleBin;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import java.util.Objects;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetypes;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeStore;

/**
 * References exclusively to already-published, frozen tags. The constructor
 * copies only the line index; all deep copies and decoding happen on a worker.
 */
public final class CloudworksGuiSource {
    private final CloudworksData cloud;
    private final Map<UUID, NBTTagCompound> lines;
    private final Map<UUID, mekanism.cloudworks.common.runtime.CloudworksRuntimeLineSnapshot> runtimeLines;
    private final NBTTagCompound machines, recycle;
    private final CloudworksSpecialNodeStore.Snapshot specialNodes;
    private final long revision;
    private final int clockTick;
    public final CloudworksGuiData.Team team;

    CloudworksGuiSource(CloudworksData cloud, Map<UUID, NBTTagCompound> lines,
            NBTTagCompound machines, NBTTagCompound recycle, long revision, int clockTick,
            CloudworksSpecialNodeStore.Snapshot specialNodes) {
        this(cloud, lines, machines, recycle, revision, clockTick, specialNodes, CloudworksGuiData.Team.EMPTY);
    }

    private CloudworksGuiSource(CloudworksData cloud, Map<UUID, NBTTagCompound> lines,
            NBTTagCompound machines, NBTTagCompound recycle, long revision, int clockTick,
            CloudworksSpecialNodeStore.Snapshot specialNodes,
            CloudworksGuiData.Team team) {
        this(cloud, lines, machines, recycle, revision, clockTick, specialNodes, team, Collections.emptyMap());
    }

    CloudworksGuiSource(CloudworksData cloud, Map<UUID, NBTTagCompound> lines,
            NBTTagCompound machines, NBTTagCompound recycle, long revision, int clockTick,
            CloudworksSpecialNodeStore.Snapshot specialNodes,
            Map<UUID, mekanism.cloudworks.common.runtime.CloudworksRuntimeLineSnapshot> runtimeLines) {
        this(cloud, lines, machines, recycle, revision, clockTick, specialNodes, CloudworksGuiData.Team.EMPTY, runtimeLines);
    }

    private CloudworksGuiSource(CloudworksData cloud, Map<UUID, NBTTagCompound> lines,
            NBTTagCompound machines, NBTTagCompound recycle, long revision, int clockTick,
            CloudworksSpecialNodeStore.Snapshot specialNodes, CloudworksGuiData.Team team,
            Map<UUID, mekanism.cloudworks.common.runtime.CloudworksRuntimeLineSnapshot> runtimeLines) {
        this.cloud = Objects.requireNonNull(cloud).copyForRuntime();
        this.lines = Collections.unmodifiableMap(new LinkedHashMap<>(lines));
        this.runtimeLines = Collections.unmodifiableMap(new LinkedHashMap<>(runtimeLines));
        this.machines = Objects.requireNonNull(machines);
        this.recycle = Objects.requireNonNull(recycle);
        this.specialNodes = Objects.requireNonNull(specialNodes);
        this.revision = revision;
        this.clockTick = Math.max(1, Math.min(24000, clockTick));
        this.team = team;
    }

    public CloudworksGuiSource withTeam(CloudworksGuiData.Team team) {
        return new CloudworksGuiSource(cloud, lines, machines, recycle, revision, clockTick, specialNodes, team, runtimeLines);
    }

    CloudworksGuiSource(CloudworksData cloud, Map<UUID, NBTTagCompound> lines,
            NBTTagCompound machines, NBTTagCompound recycle, long revision, int clockTick) {
        this(cloud, lines, machines, recycle, revision, clockTick,
                CloudworksSpecialNodeStore.Snapshot.empty());
    }

    public long revision() { return revision; }
    public int netherSourceUsage(UUID excludedLine) {
        int count = 0;
        for (UUID id : cloud.lineIds()) {
            if (id.equals(excludedLine)) continue;
            NBTTagList channels = channels(id);
            for (int index = 0; index < channels.tagCount(); index++)
                if (CloudworksNodeEnvironment.source(channels.getCompoundTagAt(index).getString("archetype"))) count++;
        }
        return count;
    }
    public int clockTick() { return clockTick; }

    public CloudworksData cloud(boolean warehouses) {
        CloudworksData copy = cloud.copyForRuntime();
        if (!warehouses) copy.resetStagingInventories();
        return copy;
    }

    public ProductionLineData line(UUID id) {
        mekanism.cloudworks.common.runtime.CloudworksRuntimeLineSnapshot snapshot = runtimeLines.get(id);
        if (snapshot != null) return ProductionLineData.read(snapshot.write());
        NBTTagCompound tag = lines.get(id);
        return tag == null ? null : ProductionLineData.read(tag.copy());
    }

    public List<ProductionLineData> lines() {
        List<ProductionLineData> result = new ArrayList<>(lines.size());
        for (UUID id : cloud.lineIds()) {
            ProductionLineData line = line(id);
            if (line != null) result.add(line);
        }
        return result;
    }

    public CloudworksMachineCache machines() { return CloudworksMachineCache.read(machines.copy()); }
    public CloudworksRecycleBin recycle() { return CloudworksRecycleBin.read(recycle.copy()); }
    public CloudworksSpecialNodeStore.Snapshot specialNodes() {
        return specialNodes;
    }

    public Map<QIOResourceDescriptor, Long> specialResourceUsage(UUID excludedLine) {
        Map<QIOResourceDescriptor, Long> counts = new LinkedHashMap<>();
        for (UUID id : cloud.lineIds()) {
            if (id.equals(excludedLine)) continue;
            NBTTagList channels = channels(id);
            for (int index = 0; index < channels.tagCount(); index++) {
                NBTTagCompound channel = channels.getCompoundTagAt(index);
                if (!CloudworksSpecialNodeArchetypes.INFINITE_RESOURCES
                        .toString().equals(channel.getString("archetype"))) continue;
                NBTTagList resources = channel.getTagList("resources", 10);
                for (int resource = 0; resource < resources.tagCount(); resource++) {
                    QIOResourceDescriptor descriptor =
                            QIOResourceDescriptor.read(
                                    resources.getCompoundTagAt(resource).getCompoundTag("descriptor"));
                    counts.merge(descriptor, 1L, Math::addExact);
                }
            }
        }
        return counts;
    }

    /** Overview needs only line metadata, not thousands of decoded machine inventories. */
    public CloudworksGuiData.Line overview(UUID id) {
        mekanism.cloudworks.common.runtime.CloudworksRuntimeLineSnapshot snapshot = runtimeLines.get(id);
        NBTTagCompound tag = snapshot == null ? lines.get(id) : snapshot.overview();
        if (tag == null)
            return new CloudworksGuiData.Line(id, "", false, false, 0, 0, 0, false, false, ItemStack.EMPTY);
        NBTTagList nodes = tag.getTagList("machines", 10);
        ItemStack icon = nodes.tagCount() == 0 ? ItemStack.EMPTY
                : CloudworksRegistries.identityKey(new ItemStack(nodes.getCompoundTagAt(0)
                        .getCompoundTag("machine").copy())).displayStack();
        CloudworksGuiData.Line line = new CloudworksGuiData.Line(id, tag.getString("name"), true,
                tag.getBoolean("enabled"), snapshot == null ? nodes.tagCount() : tag.getInteger("runtimeMachineCount"), tag.getDouble("energy"), tag.getDouble("maxEnergy"),
                tag.getBoolean("energyFlowReceivingToLine"), tag.getBoolean("energyFlowLineToSending"), icon,
                tag.getBoolean("runWhileOwnerOffline"));
        List<String> encoded = new ArrayList<>();
        NBTTagList markers = tag.getTagList("dynamicResourceDisplayList", 8);
        for (int index = 0; index < markers.tagCount(); index++) encoded.add(markers.getStringTagAt(index));
        line.markers.addAll(CloudworksLineConfiguration.markers(encoded));
        return line;
    }

    private NBTTagList channels(UUID id) {
        mekanism.cloudworks.common.runtime.CloudworksRuntimeLineSnapshot snapshot = runtimeLines.get(id);
        if (snapshot != null) return snapshot.configuredChannels();
        NBTTagCompound tag = lines.get(id);
        return tag == null ? new NBTTagList() : tag.getTagList("channels", 10);
    }
}
