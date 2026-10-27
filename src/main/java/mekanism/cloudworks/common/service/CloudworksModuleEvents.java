package mekanism.cloudworks.common.service;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import mekanism.cloudworks.api.*;
import mekanism.cloudworks.api.card.*;
import mekanism.cloudworks.api.environment.*;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.api.node.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.cloudworks.api.transfer.*;
import mekanism.cloudworks.api.work.*;
import mekanism.cloudworks.common.data.CloudworksExecutionState;
import mekanism.cloudworks.common.data.CloudworksOperation;
import mekanism.cloudworks.common.data.CloudworksProcessingBatches;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.common.upgrade.CloudworksMachineModuleRegistry;
import mekanism.common.Mekanism;
import net.minecraft.util.ResourceLocation;

/** Commit-bound lifecycle outbox. Callbacks run outside owner locks on the event thread. */
public final class CloudworksModuleEvents {
    private static final ConcurrentLinkedQueue<Event> QUEUE = new ConcurrentLinkedQueue<>();
    private CloudworksModuleEvents() {}
    public static final class Event {
        private final CloudworksModuleBinding binding;
        private final Object context;
        private final AtomicBoolean dispatched = new AtomicBoolean();
        public Event(CloudworksModuleBinding binding, Object context) { this.binding = binding; this.context = context; }
        private void dispatch() {
            if (!dispatched.compareAndSet(false, true)) return;
            try {
                if (context instanceof WorkStartedContext) binding.started((WorkStartedContext) context);
                else if (context instanceof WorkCommittedContext) binding.committed((WorkCommittedContext) context);
                else if (context instanceof WorkCancelledContext) binding.cancelled((WorkCancelledContext) context);
            } catch (RuntimeException | LinkageError failure) {
                Mekanism.logger.error("Cloudworks module event failed for " + binding.handle.getData().id(), failure);
            }
        }
    }
    public static void enqueue(Collection<Event> events) { QUEUE.addAll(events); }
    public static void flush() {
        Event event;
        while ((event = QUEUE.poll()) != null) event.dispatch();
    }
    public static void clear() { QUEUE.clear(); }

    /** Pure candidate installation callbacks and post-commit cancellation notifications. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List<Event> candidateChanges(ProductionLineData before,
            ProductionLineData after, CloudworksGenerationContext clock) {
        Map<UUID, MachineNodeData> previous = new LinkedHashMap<>(), next = new LinkedHashMap<>();
        if (before != null) for (MachineNodeData node : before.machines()) previous.put(node.nodeId(), node);
        if (after != null) for (MachineNodeData node : after.machines()) next.put(node.nodeId(), node);
        Set<UUID> nodes = new LinkedHashSet<>(previous.keySet()); nodes.addAll(next.keySet());
        List<Event> events = new ArrayList<>();
        CloudworksModuleContexts beforeContexts = CloudworksModuleContexts.capture(before);
        CloudworksModuleContexts afterContexts = CloudworksModuleContexts.capture(after);
        Map<String, CloudworksModuleBinding> oldSources = CloudworksModuleBinding.sourceBindings(before, clock, beforeContexts);
        Map<String, CloudworksModuleBinding> newSources = CloudworksModuleBinding.sourceBindings(after, clock, afterContexts);
        for (Map.Entry<String, CloudworksModuleBinding> entry : oldSources.entrySet())
            if (!newSources.containsKey(entry.getKey())) entry.getValue().removed(
                    new ModuleChangeContext(1, 0, ModuleChangeContext.Reason.SOURCE_CHANGED));
        for (Map.Entry<String, CloudworksModuleBinding> entry : newSources.entrySet())
            if (!oldSources.containsKey(entry.getKey())) entry.getValue().added(
                    new ModuleChangeContext(0, 1, ModuleChangeContext.Reason.SOURCE_CHANGED));
        for (UUID id : nodes) {
            MachineNodeData old = previous.get(id), replacement = next.get(id);
            MachineNodeData target = replacement == null ? old : replacement;
            Map<ResourceLocation, Long> oldCounts = counts(old), counts = counts(replacement);
            Set<ResourceLocation> types = new LinkedHashSet<>(oldCounts.keySet()); types.addAll(counts.keySet());
            for (ResourceLocation typeId : types) {
                long oldCount = oldCounts.getOrDefault(typeId, 0L), count = counts.getOrDefault(typeId, 0L);
                if (oldCount == count) continue;
                MachineModuleType type = CloudworksMachineModuleRegistry.type(typeId);
                if (type == null) continue;
                RecipeContribution definition = CloudworksNodeMechanisms.definition(typeId);
                long units = definition != null && definition.installation() == RecipeContribution.Installation.PER_NODE
                        ? 1 : Math.max(1L, target.machineCount());
                MachineModuleHandle handle = new MachineModuleHandle(type, (int) Math.min(Integer.MAX_VALUE, count),
                        (int) Math.min(type.maxPerMachine(), count / units), MachineModuleSource.UPGRADE,
                        id, id, target.machine(), target.machineCount(), "", counts, clock)
                        .withMachineContext((after == null ? beforeContexts : afterContexts).forMachine(target,
                                after == null ? before == null ? clock : CloudworksNodeEnvironment.generation(before, target, clock)
                                        : CloudworksNodeEnvironment.generation(after, target, clock)))
                        .forExecution(after == null ? before.lineId() : after.lineId(), "");
                CloudworksModuleBinding binding = new CloudworksModuleBinding(handle, null, 0);
                ModuleChangeContext change = new ModuleChangeContext(oldCount, count,
                        count > oldCount ? ModuleChangeContext.Reason.UPLOAD : ModuleChangeContext.Reason.UNLOAD);
                if (count > oldCount) binding.added(change); else binding.removed(change);
            }
            if (replacement != null) replacement.capabilities();
            if (old == null) continue;
            Set<UUID> retained = new HashSet<>();
            if (replacement != null) for (CloudworksOperation operation : replacement.allOperations())
                retained.add(operation.id());
            Map<ResourceLocation, Long> installed = counts(old);
            CloudworksGenerationContext environment = before == null ? clock : CloudworksNodeEnvironment.generation(before, old, clock);
            List<CloudworksModuleBinding> bindings = new ArrayList<>(CloudworksModuleBinding.upgrades(id, old.machine(), old.machineCount(), installed,
                    old.capabilities().profile() == null ? MachineUpgradePolicy.NONE : old.capabilities().profile().upgradePolicy(),
                    environment, beforeContexts.forMachine(old, environment)));
            for (CloudworksModuleBinding binding : oldSources.values())
                if (id.equals(binding.handle.getMachineNodeId())) bindings.add(binding);
            for (CloudworksOperation operation : old.allOperations()) {
                if (retained.contains(operation.id())) continue;
                String branch = "";
                for (Map.Entry<String, CloudworksExecutionState> state : old.executionBranches().entrySet())
                    if (state.getValue().operation != null && state.getValue().operation.id().equals(operation.id())) branch = state.getKey();
                for (Map.Entry<String, CloudworksExecutionState> batch : old.processingBatches().entrySet())
                    if (batch.getValue().operation.id().equals(operation.id()))
                        for (String execution : old.executionBranches().keySet())
                            if (batch.getKey().startsWith(CloudworksProcessingBatches.prefix(execution))) branch = execution;
                for (CloudworksModuleBinding binding : bindings)
                    events.add(new Event(binding.forExecution(before.lineId(), branch), new WorkCancelledContext(operation.recipeId(),
                            operation.lifecycleId(), operation.operations(),
                            after == null ? WorkCancelledContext.Reason.LINE_REMOVED : replacement == null
                                    ? WorkCancelledContext.Reason.NODE_REMOVED
                                    : !old.capabilities().keyString().equals(replacement.capabilities().keyString())
                                            || old.machineCount() != replacement.machineCount()
                                    ? WorkCancelledContext.Reason.CONTRACT_REPLACED : WorkCancelledContext.Reason.EXPLICIT_CANCEL)));
            }
        }
        return events;
    }
    private static Map<ResourceLocation, Long> counts(MachineNodeData node) {
        Map<ResourceLocation, Long> result = new LinkedHashMap<>();
        if (node != null) for (ResourceLocation id : node.customUpgrades().ids())
            result.put(id, node.customUpgrades().count(id));
        return result;
    }
}
