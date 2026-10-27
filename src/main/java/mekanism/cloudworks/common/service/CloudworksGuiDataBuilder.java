package mekanism.cloudworks.common.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.cloudworks.api.recipe.RecipeContribution;
import mekanism.cloudworks.common.data.CloudworksData;
import mekanism.cloudworks.common.data.CloudworksChannelNode;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.data.ProductionLineLink;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.*;
import mekanism.cloudworks.common.network.data.node.AuxiliaryNode;
import mekanism.cloudworks.common.network.data.node.ChannelNode;
import mekanism.cloudworks.common.network.data.node.MachineNode;
import mekanism.cloudworks.common.network.data.node.Node;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.common.upgrade.CloudworksMachineModuleRegistry;
import mekanism.common.content.qio.QIOAmount;
import mekanism.common.content.qio.QIOResourceEntry;
import net.minecraft.item.ItemStack;
import java.util.LinkedHashSet;
import java.util.Set;
import mekanism.cloudworks.api.CloudworksAreaGeometry;
import mekanism.cloudworks.api.environment.DimensionAxes;
import mekanism.cloudworks.api.environment.BiomeAxes;
import mekanism.cloudworks.api.environment.WeatherAxes;
import mekanism.cloudworks.api.node.CloudworksAuxiliaryNodeType;
import mekanism.cloudworks.api.node.CloudworksPortDefinition;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.cloudworks.common.config.CloudworksConfig;
import mekanism.cloudworks.common.data.CloudworksAuxiliaryNodeData;
import mekanism.cloudworks.common.data.CloudworksExecutionState;
import mekanism.cloudworks.common.data.CloudworksThermalState;
import mekanism.cloudworks.common.data.CloudworksUpgradeSet;
import mekanism.cloudworks.common.registries.CloudworksItems;
import mekanism.cloudworks.common.registry.CloudworksAuxiliaryNodeRegistry;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetype;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetypes;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeStore;
import net.minecraft.util.ResourceLocation;

/** Sanitized GUI projection. Inventories, journals and endpoint credentials stay on the server. */
public final class CloudworksGuiDataBuilder {
    private CloudworksGuiDataBuilder() {
    }

    /** Pure cold projection; the builder never consults live manager state. */
    /** Compatibility overload: full catalogue view (tests and legacy callers). */
    public static CloudworksGuiData build(CloudworksGuiSource source, int offset,
            UUID graphLine, int selectedType, boolean warehouses, boolean recycle,
            List<QIOResourceDescriptor> choices, int choiceOffset, int choiceTotal, UUID choiceContext) {
        return build(source, offset, graphLine, selectedType, warehouses, recycle, choices,
                choiceOffset, choiceTotal, choiceContext, true);
    }

    public static CloudworksGuiData build(CloudworksGuiSource source, int offset,
            UUID graphLine, int selectedType, boolean warehouses, boolean recycle,
            List<QIOResourceDescriptor> choices, int choiceOffset, int choiceTotal, UUID choiceContext,
            boolean catalog) {
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
            CloudworksConfig.Settings settings =
                    CloudworksConfig.settings();
            Overview overview = new Overview(pageOffset, total, Math.min(cloud.unlockedLines(), settings.maxLines), settings.maxLines,
                    Math.min(cloud.workRate(), settings.maxWorkRate), source.revision(), cloud.energy(), cloud.maxEnergy(),
                    cloud.unlimitedEnergy(), cloud.receivingEnergy(), cloud.sendingEnergy(),
                    cloud.energyFlowReceivingToCloud(), cloud.energyFlowCloudToSending(),
                    cloud.upgradePool(true), cloud.upgradePool(false), source.clockTick(), lines,
                    Math.min(cloud.maxMachineNodes(), settings.maxMachineNodes),
                    cloud.offlineProcessingUnlocked(),
                    cloud.receivingEnergyCapacity(), cloud.sendingEnergyCapacity(), settings.offlineProcessingEnabled,
                    settings.maxMachineNodes, settings.maxMachinesPerNode, cloud.recycleToTransfer(),
                    specialResources(source, graphLine),
                    Math.min(cloud.outputMultiplierUnits(), settings.maxOutputMultiplierUnits),
                    source.team, catalog ? customPoolView(cloud) : null, effectLevelView(cloud))
                    .withNetherSourceUsage(source.netherSourceUsage(graphLine));
            List<String> names = new ArrayList<>();
            for (QIOResourceDescriptor resource : choices) names.add(CloudworksEditorData.resourceName(resource));
            MachineCache machines = selectedType >= -1
                    ? CloudworksMachineCacheProjection.build(source.machines(), source.lines(), selectedType)
                    : MachineCache.EMPTY;
            Warehouses warehouseData = warehouses ? new Warehouses(resources(cloud.receiving().snapshot()),
                    resources(cloud.transfer().snapshot()), resources(cloud.sending().snapshot())) : Warehouses.EMPTY;
            Graph graph = graph(graphLine == null ? null : source.line(graphLine),
                    Math.min(cloud.maxMachineNodes(), settings.maxMachineNodes), source.specialNodes());
            if (graph.configuration != null && graph.configuration.machines.stream().anyMatch(machine -> machine.waiting != null))
                CloudworksBlueprintShortages.apply(graph.configuration, source.machines(), source.lines());
            return new CloudworksGuiData(overview, graph,
                    machines, new Choices(choiceOffset, choiceTotal, names, choices, choiceContext),
                    warehouseData, recycle ? resources(source.recycle().entries()) : Collections.emptyList());
    }

    /** Generic cloud-effect levels in storage order; ids stay opaque strings on the client (8.6). */
    private static List<CloudworksGuiData.EffectLevel> effectLevelView(CloudworksData cloud) {
        List<CloudworksGuiData.EffectLevel> entries = new ArrayList<>();
        for (ResourceLocation id : cloud.cloudEffects().ids())
            entries.add(new CloudworksGuiData.EffectLevel(id.toString(), cloud.cloudEffects().level(id)));
        return entries;
    }

    /** Cloud custom pool joined with the frozen registry; unknown ids keep counts with no declaration (8.5-B2). */
    private static List<CloudworksGuiData.CustomUpgrade> customPoolView(CloudworksData cloud) {
        Set<ResourceLocation> catalogue = new LinkedHashSet<>();
        for (MachineModuleType type :
                CloudworksMachineModuleRegistry.snapshot().values())
            if (type.slot() == MachineModuleType.Slot.CUSTOM && !type.item().isEmpty())
                catalogue.add(type.id());
        return joinPool(cloud.customUpgradePool(), catalogue);
    }

    private static List<CloudworksGuiData.CustomUpgrade> joinPool(
            CloudworksUpgradeSet pool) {
        return joinPool(pool, Collections.emptySet());
    }

    private static List<CloudworksGuiData.CustomUpgrade> joinPool(
            CloudworksUpgradeSet pool, Set<ResourceLocation> previewTypes) {
        List<CloudworksGuiData.CustomUpgrade> entries = new ArrayList<>();
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        pool.ids().forEach(ids::add);
        ids.addAll(previewTypes);
        for (ResourceLocation id : ids)
            entries.add(new CloudworksGuiData.CustomUpgrade(id.toString(), pool.count(id)));
        return entries;
    }

    private static Graph graph(ProductionLineData line, int maxNodes,
            CloudworksSpecialNodeStore.Snapshot specialNodes) {
        if (line == null) return Graph.EMPTY;
        List<Node> nodes = new ArrayList<>();
        for (MachineNodeData node : line.machines()) {
            List<Port> ports = new ArrayList<>();
            MachineSlotLayout layout = node.capabilities().slots();
            if (layout != null) {
                for (MachineSlotLayout.Slot slot : layout.slots()) {
                    ports.add(new Port(slot.id(), slot.codecId().toString(), slot.role() == MachineSlotLayout.PortRole.OUTPUT));
                }
            }
            String state = node.frozenByMissingMod() ? "FROZEN_MISSING_MOD"
                    : node.waitingBlueprint() != null ? "WAITING_BLUEPRINT"
                    : !node.active() ? "OFF" : !line.enabled() ? "LINE_STOPPED" : node.state();
            if (!CloudworksRegistries.acceptsRecipeType(node.machine(), node.recipeType()))
                state = "RECIPE_TYPE_UNAVAILABLE";
            List<ExecutionBranch> branches = new ArrayList<>();
            CloudworksThermalState thermal = node.thermalState();
            for (CloudworksNodeMechanisms.Branch branch : node.capabilities().branches()) {
                CloudworksExecutionState value = node.executionBranches().get(branch.id);
                String branchState = !node.active() ? "OFF" : !line.enabled() ? "LINE_STOPPED"
                        : value == null ? "READY" : value.state;
                branches.add(new ExecutionBranch(branch.id, branchState, value == null ? 0 : value.progress,
                        value == null ? 1 : value.duration));
                if (thermal == null && value != null) thermal = value.thermal;
                if (!"RUNNING".equals(state) && !"GENERATING".equals(state)
                        && ("RUNNING".equals(branchState) || "GENERATING".equals(branchState))) state = branchState;
            }
            nodes.add(new MachineNode(node.nodeId(), state, machineIdentity(node.machine()),
                    node.x(), node.z(), (int) Math.min(Integer.MAX_VALUE, node.machineCount()),
                    node.progress(), node.totalTicks(), node.active(),
                    summaries(node.inputs()), summaries(node.outputs()), summaries(node.templates()),
                    ports, thermal, branches));
        }
        for (CloudworksAuxiliaryNodeData auxiliary : line.auxiliaries()) {
            CloudworksAuxiliaryNodeType type =
                    CloudworksAuxiliaryNodeRegistry.byItem(auxiliary.type());
            List<Port> ports = new ArrayList<>();
            if (type != null) for (CloudworksPortDefinition port : type.ports())
                ports.add(new Port(port.id(), port.codec().toString(), port.outputAnchor()));
            List<ResourceSummary> input = new ArrayList<>();
            for (Map.Entry<QIOResourceDescriptor, Long> entry : auxiliary.input().snapshot().entrySet())
                input.add(new ResourceSummary(CloudworksEditorData.resourceName(entry.getKey()),
                        entry.getValue(), entry.getKey()));
            nodes.add(new AuxiliaryNode(auxiliary.nodeId(),
                    !auxiliary.active() ? "OFF" : !line.enabled() ? "LINE_STOPPED" : "READY",
                    type == null ? "" : type.id().toString(),
                    auxiliary.x(), auxiliary.z(), auxiliary.active(), input, ports));
        }
        // Channel manifests remain in configuration; observation uses the actual buffered resources.
        List<CloudworksAreaGeometry.Target> thermalTargets = CloudworksNodeEnvironment.targets(line);
        List<CloudworksAreaGeometry.Target> environmentTargets =
                CloudworksNodeEnvironment.environmentTargets(line);
        for (CloudworksChannelNode channel : line.channels()) {
            String state = channel.waitingBlueprint() != null ? "WAITING_BLUEPRINT"
                    : !channel.enabled() ? "OFF" : !line.enabled() ? "LINE_STOPPED"
                    : channel.buffer().total() == 0 ? "BUFFER_EMPTY"
                    : channel.buffer().total() >= channel.buffer().capacity() ? "BUFFER_FULL" : "BUFFERED";
            CloudworksSpecialNodeArchetype archetype =
                    channel.archetypeId().isEmpty() ? null
                            : CloudworksSpecialNodeArchetypes
                            .get(channel.archetypeId());
            QIOResourceDescriptor selected = channel.resources().keySet().stream().findFirst().orElse(null);
            if (CloudworksNodeEnvironment.source(channel.archetypeId())) {
                state = channel.waitingBlueprint() != null ? "WAITING_BLUEPRINT" : !channel.enabled() ? "OFF"
                        : !line.enabled() ? "LINE_STOPPED" : "READY";
            } else if (archetype != null && selected == null) state = "SPECIAL_RESOURCE_REQUIRED";
            else if (archetype != null && !specialNodes.resources.containsKey(selected))
                state = "SPECIAL_RESOURCE_UNAVAILABLE";
            List<ResourceSummary> channelContents = channel.special()
                    ? specialContents(selected, specialNodes)
                    : summaries(channel.buffer().snapshot());
            int areaHits = CloudworksNodeEnvironment.source(channel.archetypeId()) && channel.enabled()
                    && channel.waitingBlueprint() == null
                    ? CloudworksNodeEnvironment.hits(CloudworksNodeEnvironment.bounds(channel.x(), channel.z(),
                    CloudworksDraftGeometry.ENVIRONMENT_SOURCE_HEIGHT),
                    CloudworksNodeEnvironment.netherSource(channel.archetypeId()) ? thermalTargets : environmentTargets) : 0;
            nodes.add(new ChannelNode(channel.id(), state, channel.archetypeId(),
                    channel.x(), channel.z(), channel.enabled(), channelContents, areaHits));
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
        Set<ResourceLocation> previews = new LinkedHashSet<>();
        for (MachineNodeData node : line.machines())
            for (String id : CloudworksNodeMechanisms.split(node.waitingMechanism()))
                previews.add(new ResourceLocation(id));
        graph.customUpgrades = joinPool(line.customUpgradePool(), previews);
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

    /**
     * Identity marker for the wire projection: registry name and metadata of the
     * identity policy's display stack. Icons and display names are client facts
     * resolved from the client's own draft copy, never from this projection.
     */
    private static String machineIdentity(ItemStack machine) {
        ItemStack display = CloudworksRegistries.identityKey(machine).displayStack();
        ResourceLocation name = display.getItem().getRegistryName();
        return (name == null ? "unknown" : name.toString()) + "@" + display.getMetadata();
    }

    private static List<SpecialResource> specialResources(CloudworksGuiSource source, UUID graphLine) {
        Map<QIOResourceDescriptor, Long> used = source.specialResourceUsage(graphLine);
        List<SpecialResource> result = new ArrayList<>();
        for (CloudworksSpecialNodeStore.Entry entry :
                source.specialNodes().resources.values())
            result.add(new SpecialResource(entry.descriptor, entry.rules.unlimitedPlacement, entry.rules.nodeLimit,
                    used.getOrDefault(entry.descriptor, 0L), entry.rules.outputAmount));
        return result;
    }

    static List<ResourceSummary> specialContents(QIOResourceDescriptor selected,
            CloudworksSpecialNodeStore.Snapshot specialNodes) {
        if (selected == null) return Collections.emptyList();
        CloudworksSpecialNodeStore.Entry entry =
                specialNodes.resources.get(selected);
        if (entry == null) return Collections.emptyList();
        return Collections.singletonList(new ResourceSummary(
                CloudworksEditorData.resourceName(selected), entry.rules.outputAmount, selected));
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
