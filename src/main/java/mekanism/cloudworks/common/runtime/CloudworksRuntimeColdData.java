package mekanism.cloudworks.common.runtime;

import java.util.ArrayList;
import java.util.List;
import mekanism.cloudworks.common.data.CloudworksData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import java.util.Objects;
import mekanism.cloudworks.common.data.CloudworksChannelNode;
import mekanism.cloudworks.common.data.MachineNodeData;

/**
 * Worker-private templates. They are decoded once per configuration rebuild,
 * never published to the manager and never mutated by a GUI or physical source.
 */
final class CloudworksRuntimeColdData {
    private final CloudworksData cloud;
    private final List<ProductionLineData> lines;
    private final CloudworksPlayerDataManager.VirtualSnapshot origin;
    private final List<MachineNodeData.RuntimeProjection[]> machines = new ArrayList<>();
    private final List<CloudworksRuntimeLineSnapshot.InventoryValue[]> channels = new ArrayList<>();
    private final List<CloudworksRuntimeObjectSnapshot<MachineNodeData.RuntimeProjection>> machinePages = new ArrayList<>();
    private final List<CloudworksRuntimeObjectSnapshot<CloudworksRuntimeLineSnapshot.InventoryValue>> channelPages = new ArrayList<>();
    private final List<CloudworksRuntimeObjectSnapshot<CloudworksRuntimeLineSnapshot.AuxiliaryValue>> auxiliaryPages = new ArrayList<>();

    CloudworksRuntimeColdData(CloudworksPlayerDataManager.VirtualSnapshot origin) {
        this(origin, origin.lines());
    }

    CloudworksRuntimeColdData(CloudworksPlayerDataManager.VirtualSnapshot origin, List<ProductionLineData> decodedLines) {
        cloud = origin.cloud();
        this.origin = origin;
        lines = decodedLines;
        for (ProductionLineData line : lines) {
            MachineNodeData.RuntimeProjection[] initial = new MachineNodeData.RuntimeProjection[line.machines().size()];
            for (int index = 0; index < initial.length; index++) {
                MachineNodeData node = line.machines().get(index);
                if (node.frozenByMissingMod()) initial[index] = node.runtimeProjection(null, true, true, true);
            }
            machines.add(initial);
            channels.add(new CloudworksRuntimeLineSnapshot.InventoryValue[line.channels().size()]);
            machinePages.add(null);
            channelPages.add(null);
            auxiliaryPages.add(null);
        }
    }

    CloudworksData cloud() { return cloud.copyForRuntime(); }

    ProductionLineData cachedLine(int index) { return lines.get(index); }

    void cacheMachine(int line, int node, MachineNodeData value,
            boolean inputChanged, boolean outputChanged, boolean operationChanged, boolean templateChanged) {
        MachineNodeData.RuntimeProjection[] values = machines.get(line);
        if (!value.frozenByMissingMod())
            values[node] = value.runtimeProjection(values[node], inputChanged, outputChanged, templateChanged);
    }

    void cacheChannel(int line, int node, CloudworksChannelNode value) {
        channels.get(line)[node] = new CloudworksRuntimeLineSnapshot.InventoryValue(value.buffer());
    }

    CloudworksRuntimeLineSnapshot snapshot(int index, ProductionLineData projection) {
        MachineNodeData.RuntimeProjection[] nodes = machines.get(index);
        CloudworksRuntimeLineSnapshot.InventoryValue[] buffers = channels.get(index);
        CloudworksRuntimeObjectSnapshot<MachineNodeData.RuntimeProjection> nodePages =
                CloudworksRuntimeObjectSnapshot.capture(nodes.length, node -> nodes[node], machinePages.get(index));
        CloudworksRuntimeObjectSnapshot<CloudworksRuntimeLineSnapshot.InventoryValue> bufferPages =
                CloudworksRuntimeObjectSnapshot.capture(buffers.length, node -> Objects.requireNonNull(buffers[node]), channelPages.get(index));
        CloudworksRuntimeObjectSnapshot<CloudworksRuntimeLineSnapshot.AuxiliaryValue> supportPages =
                CloudworksRuntimeObjectSnapshot.capture(projection.auxiliaries().size(), node -> {
                    mekanism.cloudworks.common.data.CloudworksAuxiliaryNodeData value = projection.auxiliaries().get(node);
                    return new CloudworksRuntimeLineSnapshot.AuxiliaryValue(value.input(), value.costRemainders());
                }, auxiliaryPages.get(index));
        machinePages.set(index, nodePages);
        channelPages.set(index, bufferPages);
        auxiliaryPages.set(index, supportPages);
        double[] remainders = new double[projection.links().size()];
        for (int link = 0; link < remainders.length; link++) remainders[link] = projection.links().get(link).ratioRemainder();
        return new CloudworksRuntimeLineSnapshot(origin, projection, nodePages, bufferPages, supportPages, remainders);
    }

}
