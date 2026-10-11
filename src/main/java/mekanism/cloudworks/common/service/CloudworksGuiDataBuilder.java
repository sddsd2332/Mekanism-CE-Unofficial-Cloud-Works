package mekanism.cloudworks.common.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksData;
import mekanism.cloudworks.common.data.CloudworksChannelNode;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.data.ProductionLineLink;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.*;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import mekanism.common.content.qio.QIOAmount;
import mekanism.common.content.qio.QIOResourceEntry;
import net.minecraft.item.ItemStack;

/** Sanitized GUI projection. Inventories, journals and endpoint credentials stay on the server. */
public final class CloudworksGuiDataBuilder {
    private CloudworksGuiDataBuilder() {
    }

    /** Pure cold projection; the builder never consults live manager state. */
    public static CloudworksGuiData build(CloudworksGuiSource source, int offset,
            UUID graphLine, int selectedType, boolean warehouses, boolean recycle,
            List<QIOResourceDescriptor> choices, int choiceOffset, int choiceTotal, UUID choiceContext) {
            CloudworksData cloud = source.cloud(warehouses);
            if (!cloud.enabled()) throw new IllegalStateException("cloudworks.error.disabled");
            int total = cloud.lineIds().size();
            int pageOffset = Math.max(0, Math.min(offset, Math.max(0, total - 1)));
            pageOffset = pageOffset / CloudworksPlayerDataManager.PAGE_SIZE * CloudworksPlayerDataManager.PAGE_SIZE;
            List<Line> lines = new ArrayList<>();
            for (int index = pageOffset; index < Math.min(total, pageOffset + CloudworksPlayerDataManager.PAGE_SIZE); index++) {
                UUID id = cloud.lineIds().get(index);
                lines.add(source.overview(id));
            }
            mekanism.cloudworks.common.config.CloudworksConfig.Settings settings =
                    mekanism.cloudworks.common.config.CloudworksConfig.settings();
            Overview overview = new Overview(pageOffset, total, Math.min(cloud.unlockedLines(), settings.maxLines), settings.maxLines,
                    Math.min(cloud.workRate(), settings.maxWorkRate), source.revision(), cloud.energy(), cloud.maxEnergy(),
                    cloud.unlimitedEnergy(), cloud.receivingEnergy(), cloud.sendingEnergy(),
                    cloud.energyFlowReceivingToCloud(), cloud.energyFlowCloudToSending(),
                    cloud.upgradePool(true), cloud.upgradePool(false), source.clockTick(), lines,
                    cloud.simulatedHeight(), cloud.lavaHeatCards(), Math.min(cloud.maxMachineNodes(), settings.maxMachineNodes),
                    cloud.offlineProcessingUnlocked(), cloud.netherEnvironmentCard(),
                    cloud.receivingEnergyCapacity(), cloud.sendingEnergyCapacity(), settings.offlineProcessingEnabled,
                    settings.maxMachineNodes, settings.maxMachinesPerNode, cloud.recycleToTransfer());
            List<String> names = new ArrayList<>();
            for (QIOResourceDescriptor resource : choices) names.add(CloudworksEditorData.resourceName(resource));
            MachineCache machines = selectedType >= -1
                    ? CloudworksMachineCacheProjection.build(source.machines(), source.lines(), selectedType)
                    : MachineCache.EMPTY;
            Warehouses warehouseData = warehouses ? new Warehouses(resources(cloud.receiving().snapshot()),
                    resources(cloud.transfer().snapshot()), resources(cloud.sending().snapshot())) : Warehouses.EMPTY;
            Graph graph = graph(graphLine == null ? null : source.line(graphLine),
                    Math.min(cloud.maxMachineNodes(), settings.maxMachineNodes));
            if (graph.configuration != null && graph.configuration.machines.stream().anyMatch(machine -> machine.waiting != null))
                CloudworksBlueprintShortages.apply(graph.configuration, source.machines(), source.lines());
            return new CloudworksGuiData(overview, graph,
                    machines, new Choices(choiceOffset, choiceTotal, names, choices, choiceContext),
                    warehouseData, recycle ? resources(source.recycle().entries()) : Collections.emptyList());
    }

    private static Graph graph(ProductionLineData line, int maxNodes) {
        if (line == null) return Graph.EMPTY;
        List<Node> nodes = new ArrayList<>();
        for (MachineNodeData node : line.machines()) {
            List<Port> ports = new ArrayList<>();
            MachineSlotLayout layout = CloudworksRegistries.slots(node.machine());
            if (layout != null) {
                for (MachineSlotLayout.Slot slot : layout.slots()) {
                    ports.add(new Port(slot.id(), slot.codecId().toString(), slot.role() == MachineSlotLayout.PortRole.OUTPUT));
                }
            }
            String state = node.waitingBlueprint() != null ? "WAITING_BLUEPRINT"
                    : !node.active() ? "OFF" : !line.enabled() ? "LINE_STOPPED" : node.state();
            nodes.add(new Node(node.nodeId(), node.machine().getDisplayName(), state,
                    CloudworksRegistries.identityKey(node.machine()).displayStack(), node.x(), node.z(),
                    (int) Math.min(Integer.MAX_VALUE, node.machineCount()), node.progress(), node.totalTicks(),
                    node.active(), summaries(node.inputs()), summaries(node.outputs()), summaries(node.templates()), ports, node.thermalState()));
        }
        // Channel manifests remain in configuration; observation uses the actual buffered resources.
        for (CloudworksChannelNode channel : line.channels()) {
            String state = channel.waitingBlueprint() != null ? "WAITING_BLUEPRINT"
                    : !channel.enabled() ? "OFF" : !line.enabled() ? "LINE_STOPPED"
                    : channel.buffer().total() == 0 ? "BUFFER_EMPTY"
                    : channel.buffer().total() >= channel.buffer().capacity() ? "BUFFER_FULL" : "BUFFERED";
            nodes.add(new Node(channel.id(), channel.mode().name(), state,
                    ItemStack.EMPTY, channel.x(), channel.z(), 1, 0, 1, channel.enabled(),
                    summaries(channel.buffer().snapshot()), Collections.emptyList(), Collections.emptyList()));
        }
        List<Link> links = new ArrayList<>();
        for (ProductionLineLink link : line.links()) {
            List<String> names = new ArrayList<>();
            for (QIOResourceDescriptor filter : link.resourceFilters()) names.add(CloudworksEditorData.resourceName(filter));
            links.add(new Link(link.linkId(), link.sourceNode(), link.sourcePort(), link.targetNode(), link.targetPort(),
                    link.enabled(), link.priority(), link.ratio(), names));
        }
        Graph graph = new Graph(line.lineId(), line.name(), line.enabled(), line.explicitRouting(),
                line.energy(), line.maxEnergy(), line.speedUpgrades(), line.energyUpgrades(), nodes, links);
        graph.configuration = CloudworksLineConfiguration.capture(line);
        graph.configuration.maxNodes = maxNodes;
        return graph;
    }

    private static List<ResourceSummary> summaries(List<CloudworksResourceStack> resources) {
        List<ResourceSummary> result = new ArrayList<>();
        for (CloudworksResourceStack resource : resources) {
            result.add(new ResourceSummary(CloudworksEditorData.resourceName(resource.descriptor()),
                    resource.amount(), resource.descriptor()));
        }
        return result;
    }

    private static List<ResourceSummary> summaries(Map<QIOResourceDescriptor, Long> resources) {
        List<ResourceSummary> result = new ArrayList<>();
        resources.forEach((resource, amount) -> result.add(new ResourceSummary(
                CloudworksEditorData.resourceName(resource), amount, resource)));
        return result;
    }

    private static List<QIOResourceEntry> resources(Map<QIOResourceDescriptor, Long> source) {
        List<QIOResourceEntry> result = new ArrayList<>();
        if (source != null) {
            for (Map.Entry<QIOResourceDescriptor, Long> entry : source.entrySet()) {
                UUID id = UUID.nameUUIDFromBytes(entry.getKey().toString().getBytes(StandardCharsets.UTF_8));
                result.add(QIOResourceEntry.of(id, entry.getKey(), QIOAmount.of(entry.getValue())));
            }
        }
        return result;
    }
}
