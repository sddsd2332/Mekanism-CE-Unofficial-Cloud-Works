package mekanism.cloudworks.common.runtime;

import java.util.ArrayList;
import java.util.List;
import mekanism.cloudworks.common.data.CloudworksData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;

/**
 * Worker-private templates. They are decoded once per configuration rebuild,
 * never published to the manager and never mutated by a GUI or physical source.
 */
final class CloudworksRuntimeColdData {
    private final CloudworksData cloud;
    private final List<ProductionLineData> lines;
    private final List<net.minecraft.nbt.NBTTagCompound[]> machineTags = new ArrayList<>();
    private final List<net.minecraft.nbt.NBTTagCompound[]> channelTags = new ArrayList<>();
    private final java.util.Map<java.util.UUID, net.minecraft.nbt.NBTTagCompound> linkTags = new java.util.HashMap<>();

    CloudworksRuntimeColdData(CloudworksPlayerDataManager.VirtualSnapshot origin) {
        this(origin, origin.lines());
    }

    CloudworksRuntimeColdData(CloudworksPlayerDataManager.VirtualSnapshot origin, List<ProductionLineData> decodedLines) {
        cloud = origin.cloud();
        lines = decodedLines;
        for (ProductionLineData line : lines) {
            machineTags.add(new net.minecraft.nbt.NBTTagCompound[line.machines().size()]);
            channelTags.add(new net.minecraft.nbt.NBTTagCompound[line.channels().size()]);
            for (mekanism.cloudworks.common.data.ProductionLineLink link : line.links())
                linkTags.put(link.linkId(), link.write());
        }
    }

    CloudworksData cloud() { return cloud.copyForRuntime(); }

    ProductionLineData cachedLine(int index) { return lines.get(index); }

    void cacheMachine(int line, int node, mekanism.cloudworks.common.data.MachineNodeData value,
            boolean inputChanged, boolean outputChanged, boolean operationChanged, boolean templateChanged) {
        net.minecraft.nbt.NBTTagCompound[] tags = machineTags.get(line);
        tags[node] = value.writeRuntimeDelta(tags[node], inputChanged, outputChanged, operationChanged, templateChanged);
    }

    void cacheChannel(int line, int node, mekanism.cloudworks.common.data.CloudworksChannelNode value) {
        channelTags.get(line)[node] = value.write();
    }

    void cacheLink(mekanism.cloudworks.common.data.ProductionLineLink value) {
        // Link metadata never changes inside a compiled Runtime epoch.
        net.minecraft.nbt.NBTTagCompound previous = linkTags.get(value.linkId());
        net.minecraft.nbt.NBTTagCompound next = new net.minecraft.nbt.NBTTagCompound();
        for (String key : previous.getKeySet()) next.setTag(key, previous.getTag(key));
        next.setDouble("ratioRemainder", value.ratioRemainder());
        linkTags.put(value.linkId(), next);
    }

    net.minecraft.nbt.NBTTagCompound tag(int index, ProductionLineData projection) {
        net.minecraft.nbt.NBTTagList nodes = new net.minecraft.nbt.NBTTagList();
        for (net.minecraft.nbt.NBTTagCompound node : machineTags.get(index))
            nodes.appendTag(java.util.Objects.requireNonNull(node));
        net.minecraft.nbt.NBTTagList channels = new net.minecraft.nbt.NBTTagList();
        for (net.minecraft.nbt.NBTTagCompound node : channelTags.get(index))
            channels.appendTag(java.util.Objects.requireNonNull(node));
        net.minecraft.nbt.NBTTagList links = new net.minecraft.nbt.NBTTagList();
        for (mekanism.cloudworks.common.data.ProductionLineLink link : projection.links())
            links.appendTag(linkTags.get(link.linkId()));
        return projection.writeProjection(nodes, links, channels);
    }

}
