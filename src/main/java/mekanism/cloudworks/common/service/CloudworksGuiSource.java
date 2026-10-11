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

/**
 * References exclusively to already-published, frozen tags. The constructor
 * copies only the line index; all deep copies and decoding happen on a worker.
 */
public final class CloudworksGuiSource {
    private final CloudworksData cloud;
    private final Map<UUID, NBTTagCompound> lines;
    private final NBTTagCompound machines, recycle;
    private final long revision;
    private final int clockTick;

    CloudworksGuiSource(CloudworksData cloud, Map<UUID, NBTTagCompound> lines,
            NBTTagCompound machines, NBTTagCompound recycle, long revision, int clockTick) {
        this.cloud = java.util.Objects.requireNonNull(cloud).copyForRuntime();
        this.lines = Collections.unmodifiableMap(new LinkedHashMap<>(lines));
        this.machines = java.util.Objects.requireNonNull(machines);
        this.recycle = java.util.Objects.requireNonNull(recycle);
        this.revision = revision;
        this.clockTick = Math.max(1, Math.min(24000, clockTick));
    }

    public long revision() { return revision; }
    public int clockTick() { return clockTick; }

    public CloudworksData cloud(boolean warehouses) {
        CloudworksData copy = cloud.copyForRuntime();
        if (!warehouses) copy.resetStagingInventories();
        return copy;
    }

    public ProductionLineData line(UUID id) {
        NBTTagCompound tag = lines.get(id);
        return tag == null ? null : ProductionLineData.read(tag.copy());
    }

    public List<ProductionLineData> lines() {
        List<ProductionLineData> result = new ArrayList<>(lines.size());
        for (NBTTagCompound tag : lines.values()) result.add(ProductionLineData.read(tag.copy()));
        return result;
    }

    public CloudworksMachineCache machines() { return CloudworksMachineCache.read(machines.copy()); }
    public CloudworksRecycleBin recycle() { return CloudworksRecycleBin.read(recycle.copy()); }

    /** Overview needs only line metadata, not thousands of decoded machine inventories. */
    public CloudworksGuiData.Line overview(UUID id) {
        NBTTagCompound tag = lines.get(id);
        if (tag == null)
            return new CloudworksGuiData.Line(id, "", false, false, 0, 0, 0, false, false, ItemStack.EMPTY);
        NBTTagList nodes = tag.getTagList("machines", 10);
        ItemStack icon = nodes.tagCount() == 0 ? ItemStack.EMPTY
                : CloudworksRegistries.identityKey(new ItemStack(nodes.getCompoundTagAt(0)
                        .getCompoundTag("machine").copy())).displayStack();
        CloudworksGuiData.Line line = new CloudworksGuiData.Line(id, tag.getString("name"), true,
                tag.getBoolean("enabled"), nodes.tagCount(), tag.getDouble("energy"), tag.getDouble("maxEnergy"),
                tag.getBoolean("energyFlowReceivingToLine"), tag.getBoolean("energyFlowLineToSending"), icon,
                tag.getBoolean("runWhileOwnerOffline"));
        List<String> encoded = new ArrayList<>();
        NBTTagList markers = tag.getTagList("dynamicResourceDisplayList", 8);
        for (int index = 0; index < markers.tagCount(); index++) encoded.add(markers.getStringTagAt(index));
        line.markers.addAll(CloudworksLineConfiguration.markers(encoded));
        return line;
    }
}
