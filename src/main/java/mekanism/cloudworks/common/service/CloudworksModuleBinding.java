package mekanism.cloudworks.common.service;

import java.util.*;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.*;
import mekanism.cloudworks.api.card.*;
import mekanism.cloudworks.api.environment.*;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.api.node.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.cloudworks.api.transfer.*;
import mekanism.cloudworks.api.work.*;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.common.upgrade.CloudworksMachineModuleRegistry;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeLimits;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import mekanism.cloudworks.common.data.CloudworksAuxiliaryNodeData;
import mekanism.cloudworks.common.data.CloudworksChannelNode;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.data.ProductionLineLink;
import mekanism.cloudworks.common.registry.CloudworksAuxiliaryNodeRegistry;

/**
 * Immutable source/target binding. The raw generic dispatch is confined to this boundary.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class CloudworksModuleBinding {
    public final MachineModuleHandle handle;
    public final QIOResourceDescriptor costResource;
    public final double basePower;
    public final boolean hasPostWork;
    public final CloudworksModuleCallbacks callbacks;

    public CloudworksModuleBinding(MachineModuleHandle handle, QIOResourceDescriptor costResource, double basePower) {
        this.handle = Objects.requireNonNull(handle);
        this.costResource = costResource;
        this.basePower = basePower;
        this.callbacks = CloudworksModuleCallbacks.of(handle.getCustomInstance());
        this.hasPostWork = callbacks.postWork;
    }

    public static List<CloudworksModuleBinding> upgrades(UUID node, ItemStack machine, long machines,
                                                         Map<ResourceLocation, Long> installed, MachineUpgradePolicy policy, CloudworksGenerationContext environment) {
        return upgrades(node, machine, machines, installed, policy, environment,
                MachineContextSnapshot.empty(environment));
    }

    public static List<CloudworksModuleBinding> upgrades(UUID node, ItemStack machine, long machines,
                                                         Map<ResourceLocation, Long> installed, MachineUpgradePolicy policy,
                                                         CloudworksGenerationContext environment, MachineContextSnapshot machineContext) {
        List<CloudworksModuleBinding> result = new ArrayList<>();
        List<ResourceLocation> ids = new ArrayList<>(installed.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        for (ResourceLocation id : ids) {
            MachineModuleType type = CloudworksMachineModuleRegistry.type(id);
            int limit = CloudworksUpgradeLimits.moduleLimit(policy, id, CloudworksUpgradeEffects.settings());
            if (type == null || limit <= 0 || machines <= 0 || !type.acceptsSource(MachineModuleSource.UPGRADE))
                continue;
            RecipeContribution definition = CloudworksNodeMechanisms.definition(id);
            long total = installed.get(id);
            if (definition != null && !definition.active(total, machines)) continue;
            long units = definition != null && definition.installation() == RecipeContribution.Installation.PER_NODE ? 1 : machines;
            int effective = (int) Math.min(limit, total / units);
            if (effective == 0) continue;
            MachineModuleHandle handle = new MachineModuleHandle(type,
                    (int) Math.min(Integer.MAX_VALUE, total), effective, MachineModuleSource.UPGRADE,
                    node, node, machine, machines, "", installed, environment)
                    .withMachineContext(machineContext);
            result.add(new CloudworksModuleBinding(handle, null, 0D));
        }
        return Collections.unmodifiableList(result);
    }

    public static List<CloudworksModuleBinding> environmentSources(
            ProductionLineData line,
            MachineNodeData machine, CloudworksGenerationContext environment) {
        return environmentSources(line, machine, environment, context(line, machine, environment));
    }

    public static List<CloudworksModuleBinding> environmentSources(
            ProductionLineData line,
            MachineNodeData machine,
            CloudworksGenerationContext environment, MachineContextSnapshot machineContext) {
        List<CloudworksModuleBinding> result = new ArrayList<>();
        Set<ResourceLocation> flags = new HashSet<>();
        List<CloudworksChannelNode> channels = new ArrayList<>(line.channels());
        channels.sort(Comparator.comparing(CloudworksChannelNode::id));
        for (CloudworksChannelNode channel : channels) {
            MachineModuleType<?> type = CloudworksNodeEnvironment.environmentModule(channel.archetypeId());
            if (type == null || !channel.enabled() || channel.waitingBlueprint() != null
                    || !machine.active() || machine.waitingBlueprint() != null || machine.machineCount() <= 0
                    || !CloudworksAreaGeometry.exceeds(
                    type.area() == null ? CloudworksNodeEnvironment.bounds(channel.x(), channel.z(),
                            CloudworksDraftGeometry.ENVIRONMENT_SOURCE_HEIGHT)
                            : type.area().bounds(channel.x(), channel.z(), CloudworksDraftGeometry.ENVIRONMENT_SOURCE_HEIGHT, 0),
                    new CloudworksAreaGeometry.Rect(machine.x(), machine.z(),
                            machine.x() + CloudworksAreaGeometry.NODE_WIDTH, machine.z()
                            + CloudworksAreaGeometry.nodeHeight(machine.capabilities().slots().slots().size())),
                    type.area() == null ? .5D : type.area().threshold())) continue;
            if (!flags.add(type.id())) continue; // Environment nodes do not stack.
            Map<ResourceLocation, Long> installed = new LinkedHashMap<>();
            for (ResourceLocation id : machine.customUpgrades().ids())
                installed.put(id, machine.customUpgrades().count(id));
            result.add(new CloudworksModuleBinding(new MachineModuleHandle(type, 1, 1,
                    MachineModuleSource.ENVIRONMENT_NODE, channel.id(), machine.nodeId(), machine.machine(),
                    machine.machineCount(), "", installed, environment)
                    .withMachineContext(machineContext).forExecution(line.lineId(), ""), null, 0D));
        }
        return result;
    }

    public static List<CloudworksModuleBinding> machineEnvironmentBehavior(
            MachineNodeData machine,
            CloudworksGenerationContext environment, MachineContextSnapshot machineContext) {
        MachineModuleType<?> type = CloudworksAPI.machineEnvironmentBehavior(machine.machine());
        if (type == null || machine.machineCount() <= 0 || !machine.active()
                || machine.waitingBlueprint() != null)
            return Collections.emptyList();
        Map<ResourceLocation, Long> installed = new LinkedHashMap<>();
        for (ResourceLocation id : machine.customUpgrades().ids())
            installed.put(id, machine.customUpgrades().count(id));
        MachineModuleHandle<?> handle = new MachineModuleHandle(type, 1, 1,
                MachineModuleSource.MACHINE_ENVIRONMENT, machine.nodeId(), machine.nodeId(),
                machine.machine(), machine.machineCount(), "", installed, environment)
                .withMachineContext(machineContext);
        return Collections.singletonList(new CloudworksModuleBinding(handle, null, 0D));
    }

    public static Map<String, CloudworksModuleBinding> sourceBindings(
            ProductionLineData line, CloudworksGenerationContext clock) {
        return sourceBindings(line, clock, CloudworksModuleContexts.capture(line));
    }

    static Map<String, CloudworksModuleBinding> sourceBindings(
            ProductionLineData line, CloudworksGenerationContext clock, CloudworksModuleContexts contexts) {
        Map<String, CloudworksModuleBinding> result = new LinkedHashMap<>();
        if (line == null) return result;
        List<MachineNodeData> machines = new ArrayList<>(line.machines());
        machines.sort(Comparator.comparing(MachineNodeData::nodeId));
        Map<UUID, CloudworksGenerationContext> machineEnvironments = new HashMap<>();
        Map<UUID, MachineContextSnapshot> machineContexts = new HashMap<>();
        for (MachineNodeData machine : machines) {
            CloudworksGenerationContext environment = CloudworksNodeEnvironment.generation(line, machine, clock);
            MachineContextSnapshot machineContext = contexts.forMachine(machine, environment);
            machineEnvironments.put(machine.nodeId(), environment);
            machineContexts.put(machine.nodeId(), machineContext);
            for (CloudworksModuleBinding binding : environmentSources(line, machine, environment,
                    machineContext))
                result.put(binding.key(), binding);
            for (CloudworksModuleBinding binding : machineEnvironmentBehavior(machine, environment,
                    machineContext))
                result.put(binding.key(), binding);
        }
        for (CloudworksAuxiliaryNodeData auxiliary : line.auxiliaries()) {
            CloudworksAuxiliaryNodeType type = CloudworksAuxiliaryNodeRegistry.byItem(auxiliary.type());
            if (type == null || !auxiliary.active()) continue;
            int count = 0;
            for (MachineNodeData machine : machines) {
                if (!machine.active() || machine.waitingBlueprint() != null || machine.machineCount() <= 0
                        || !type.accepts(machine.machine())) continue;
                if (type.targeting() == CloudworksAuxiliaryNodeType.Targeting.LINKED
                        && !auxiliary.linkedTargets().contains(machine.nodeId())) continue;
                ModuleArea area = type.module().area();
                if (type.targeting() == CloudworksAuxiliaryNodeType.Targeting.AREA) {
                    int radius = auxiliary.range() > 0 ? auxiliary.range() : type.range();
                    CloudworksAreaGeometry.Rect bounds = area == null
                            ? CloudworksAreaGeometry.square(auxiliary.x() + CloudworksAreaGeometry.NODE_WIDTH / 2D,
                            auxiliary.z() + CloudworksAreaGeometry.nodeHeight(type.ports().size()) / 2D, radius * 2D)
                            : area.bounds(auxiliary.x(), auxiliary.z(), CloudworksAreaGeometry.nodeHeight(type.ports().size()), radius);
                    if (!CloudworksAreaGeometry.exceeds(bounds,
                            new CloudworksAreaGeometry.Rect(machine.x(), machine.z(),
                                    machine.x() + CloudworksAreaGeometry.NODE_WIDTH, machine.z()
                                    + CloudworksAreaGeometry.nodeHeight(machine.capabilities().slots().slots().size())),
                            area == null ? .5D : area.threshold())) continue;
                }
                if (type.maxTargets() > 0 && count++ >= type.maxTargets()) break;
                Map<ResourceLocation, Long> installed = new LinkedHashMap<>();
                for (ResourceLocation id : machine.customUpgrades().ids())
                    installed.put(id, machine.customUpgrades().count(id));
                CloudworksGenerationContext environment = machineEnvironments.get(machine.nodeId());
                MachineContextSnapshot machineContext = machineContexts.get(machine.nodeId());
                CloudworksModuleBinding binding = new CloudworksModuleBinding(new MachineModuleHandle(type.module(), 1, 1,
                        MachineModuleSource.AUXILIARY_NODE, auxiliary.nodeId(), machine.nodeId(), machine.machine(),
                        machine.machineCount(), "", installed, environment)
                        .withMachineContext(machineContext),
                        null, machine.capabilities().profile() == null ? 0 : machine.capabilities().profile().energyPerTick());
                result.put(binding.key(), binding.forExecution(line.lineId(), ""));
            }
        }
        return result;
    }

    public String key() {
        return handle.getSource() + "|" + handle.getSourceNodeId() + "|" + handle.getMachineNodeId()
                + "|" + handle.getData().id();
    }

    public CloudworksModuleBinding forExecution(UUID line, String branch) {
        return new CloudworksModuleBinding(handle.forExecution(line, branch), costResource, basePower);
    }

    /**
     * Capacity is a configuration property. Empty recipe id and zero operations denote this projection.
     */
    public static double capacityMultiplier(MachineNodeData node) {
        if (node.machineCount() <= 0 || node.capabilities().profile() == null) return 1D;
        Map<ResourceLocation, Long> installed = new LinkedHashMap<>();
        for (ResourceLocation id : node.customUpgrades().ids()) installed.put(id, node.customUpgrades().count(id));
        double result = 1D;
        for (CloudworksModuleBinding binding : upgrades(node.nodeId(), node.machine(), node.machineCount(), installed,
                node.capabilities().profile().upgradePolicy(), new CloudworksGenerationContext(0, 1, 0, false))) {
            if (!binding.handle.getCustomInstance().affectsCapacity()) continue;
            WorkContribution value = binding.pre("", 0, node.capabilities().profile().totalTicks(),
                    node.capabilities().profile().energyPerTick());
            if (value.capacityMultiplier() != 1D && !value.costs().isEmpty())
                throw new IllegalArgumentException("Conditional resource costs cannot change configured energy capacity");
            result *= value.capacityMultiplier();
            if (!Double.isFinite(result)) throw new IllegalArgumentException("Module energy capacity overflow");
        }
        return result;
    }

    /** Absolute per-base-machine additions compose after multiplicative capacity effects. */
    public static double capacityAddition(MachineNodeData node) {
        if (node.machineCount() <= 0 || node.capabilities().profile() == null) return 0D;
        Map<ResourceLocation, Long> installed = new LinkedHashMap<>();
        for (ResourceLocation id : node.customUpgrades().ids()) installed.put(id, node.customUpgrades().count(id));
        double result = 0D;
        for (CloudworksModuleBinding binding : upgrades(node.nodeId(), node.machine(), node.machineCount(), installed,
                node.capabilities().profile().upgradePolicy(), new CloudworksGenerationContext(0, 1, 0, false))) {
            if (!binding.handle.getCustomInstance().affectsCapacity()) continue;
            WorkContribution value = binding.pre("", 0, node.capabilities().profile().totalTicks(), node.capabilities().profile().energyPerTick());
            if (value.capacityAddition() > 0 && !value.costs().isEmpty()) throw new IllegalArgumentException("Conditional storage addition");
            result += value.capacityAddition();
        }
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Energy capacity addition overflow");
        return result;
    }

    /**
     * Builds one immutable view of all related nodes and links for a machine.
     */
    public static MachineContextSnapshot context(
            ProductionLineData line,
            MachineNodeData machine,
            CloudworksGenerationContext environment) {
        return CloudworksModuleContexts.capture(line).forMachine(machine, environment);
    }

    static MachineContextSnapshot context(ProductionLineData line, MachineNodeData machine,
            CloudworksGenerationContext environment, MachineContextSnapshot.MachineNodes machines,
            List<MachineContextSnapshot.Link> incidentLinks) {
        if (line == null || machine == null) return MachineContextSnapshot.empty(environment);
        Set<UUID> connected = new HashSet<>();
        List<MachineContextSnapshot.Link> links = new ArrayList<>();
        for (MachineContextSnapshot.Link link : incidentLinks) {
            connected.add(link.sourceNode());
            connected.add(link.targetNode());
            links.add(link);
        }
        List<MachineContextSnapshot.InputOutputNode> channels = new ArrayList<>();
        for (CloudworksChannelNode channel : line.channels()) {
            if (!connected.contains(channel.id())) continue;
            Set<ResourceLocation> codecs = new LinkedHashSet<>();
            for (QIOResourceDescriptor resource : channel.resources().keySet())
                codecs.add(resource.getCodecId());
            channels.add(new MachineContextSnapshot.InputOutputNode(channel.id(), channel.mode().name(),
                    channel.archetypeId(), channel.enabled(), codecs));
        }
        List<MachineContextSnapshot.AuxiliaryNode> auxiliaries = new ArrayList<>();
        for (CloudworksAuxiliaryNodeData auxiliary : line.auxiliaries()) {
            CloudworksAuxiliaryNodeType type =
                    CloudworksAuxiliaryNodeRegistry.byItem(auxiliary.type());
            if (type == null || !auxiliary.active() || !type.accepts(machine.machine())) continue;
            boolean applies = type.targeting() != CloudworksAuxiliaryNodeType.Targeting.LINKED
                    || auxiliary.linkedTargets().contains(machine.nodeId());
            if (!applies) continue;
            if (type.targeting() == CloudworksAuxiliaryNodeType.Targeting.AREA) {
                ModuleArea area = type.module().area();
                int radius = auxiliary.range() > 0 ? auxiliary.range() : type.range();
                CloudworksAreaGeometry.Rect bounds = area == null
                        ? CloudworksAreaGeometry.square(auxiliary.x()
                                + CloudworksAreaGeometry.NODE_WIDTH / 2D,
                        auxiliary.z() + CloudworksAreaGeometry.nodeHeight(type.ports().size()) / 2D,
                        radius * 2D)
                        : area.bounds(auxiliary.x(), auxiliary.z(),
                        CloudworksAreaGeometry.nodeHeight(type.ports().size()), radius);
                if (!CloudworksAreaGeometry.exceeds(bounds,
                        new CloudworksAreaGeometry.Rect(machine.x(), machine.z(),
                                machine.x() + CloudworksAreaGeometry.NODE_WIDTH,
                                machine.z() + CloudworksAreaGeometry.nodeHeight(
                                        machine.capabilities().slots().slots().size())),
                        area == null ? .5D : area.threshold())) continue;
            }
            if (type.maxTargets() > 0) {
                int ordinal = 0;
                boolean selected = false;
                for (MachineNodeData candidate : line.machines()) {
                    if (!candidate.active() || candidate.waitingBlueprint() != null
                            || candidate.machineCount() <= 0 || !type.accepts(candidate.machine()))
                        continue;
                    if (type.targeting() == CloudworksAuxiliaryNodeType.Targeting.LINKED
                            && !auxiliary.linkedTargets().contains(candidate.nodeId()))
                        continue;
                    if (type.targeting() == CloudworksAuxiliaryNodeType.Targeting.AREA) {
                        ModuleArea candidateArea = type.module().area();
                        int candidateRadius = auxiliary.range() > 0 ? auxiliary.range() : type.range();
                        CloudworksAreaGeometry.Rect candidateBounds = candidateArea == null
                                ? CloudworksAreaGeometry.square(auxiliary.x()
                                        + CloudworksAreaGeometry.NODE_WIDTH / 2D,
                                auxiliary.z() + CloudworksAreaGeometry.nodeHeight(type.ports().size()) / 2D,
                                candidateRadius * 2D)
                                : candidateArea.bounds(auxiliary.x(), auxiliary.z(),
                                CloudworksAreaGeometry.nodeHeight(type.ports().size()), candidateRadius);
                        if (!CloudworksAreaGeometry.exceeds(candidateBounds,
                                new CloudworksAreaGeometry.Rect(candidate.x(), candidate.z(),
                                        candidate.x() + CloudworksAreaGeometry.NODE_WIDTH,
                                        candidate.z() + CloudworksAreaGeometry.nodeHeight(
                                                candidate.capabilities().slots().slots().size())),
                                candidateArea == null ? .5D : candidateArea.threshold()))
                            continue;
                    }
                    if (candidate.nodeId().equals(machine.nodeId())) {
                        selected = ordinal < type.maxTargets();
                        break;
                    }
                    ordinal++;
                }
                if (!selected) continue;
            }
            auxiliaries.add(new MachineContextSnapshot.AuxiliaryNode(auxiliary.nodeId(), type.id(),
                    true, !auxiliary.input().snapshot().isEmpty(), 1));
        }
        return MachineContextSnapshot.withSharedMachines(environment, auxiliaries, channels,
                machines, machine.nodeId(), links);
    }

    public WorkContribution pre(String recipe, int count, int duration, double power) {
        return pre(recipe, count, duration, power, costResource);
    }

    public WorkContribution pre(String recipe, int count, int duration, double power, QIOResourceDescriptor costResource) {
        return pre(recipe, count, duration, power, costResource, handle.getEnvironment().clockTick());
    }

    public WorkContribution pre(String recipe, int count, int duration, double power, QIOResourceDescriptor costResource,
                                int clock) {
        if (!callbacks.preWork) return WorkContribution.neutral();
        CloudworksGenerationContext base = handle.getEnvironment();
        CloudworksGenerationContext environment = new CloudworksGenerationContext(base.simulatedHeight(), clock,
                base.lavaHeatCards(), base.environmentTypes());
        return Objects.requireNonNull(handle.getCustomInstance().onPreWork(handle.forContext(recipe, environment),
                        new PreWorkContext(recipe, count, duration, power, environment,
                                costResource == null ? null : costResource.getCodecId(), costResource)),
                "Module returned null work contribution");
    }

    public RecipeCandidateDecision candidate(RecipeCandidateContext context) {
        if (!callbacks.candidate) return RecipeCandidateDecision.accept();
        return Objects.requireNonNull(handle.getCustomInstance().onRecipeCandidate(
                handle.forContext(context.recipe().id(), context.environment()), context), "Module returned null candidate decision");
    }

    public List<MachineOutput> post(PostWorkContext context, List<MachineOutput> outputs) {
        return Objects.requireNonNull(handle.getCustomInstance().onPostWork(
                        handle.forContext(context.recipeId(), context.environment()), context, Collections.unmodifiableList(outputs)),
                "Module returned null outputs");
    }

    public boolean discardsChance() {
        return handle.getCustomInstance().discardsChanceOnOverflow();
    }

    public void added(ModuleChangeContext change) {
        handle.getCustomInstance().onAdded(handle, change);
    }

    public void removed(ModuleChangeContext change) {
        MachineModuleHandle view = handle.getSource() == MachineModuleSource.UPGRADE ? handle : handle.withCounts(0, 0);
        handle.getCustomInstance().onRemoved(view, change);
    }

    public void started(WorkStartedContext context) {
        if (!callbacks.started) return;
        handle.getCustomInstance().onWorkStarted(handle.forContext(context.recipeId(), context.environment()), context);
    }

    public void committed(WorkCommittedContext context) {
        if (!callbacks.committed) return;
        handle.getCustomInstance().onWorkCommitted(handle.forRecipe(context.recipeId()), context);
    }

    public void cancelled(WorkCancelledContext context) {
        if (!callbacks.cancelled) return;
        handle.getCustomInstance().onWorkCancelled(handle.forRecipe(context.recipeId()), context);
    }
}
