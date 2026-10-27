package mekanism.cloudworks.common.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

import mekanism.cloudworks.common.data.CloudworksData;
import mekanism.cloudworks.common.data.CloudworksInventory;
import mekanism.cloudworks.common.data.CloudworksWarehouseDelta;
import mekanism.cloudworks.common.data.CloudworksOperation;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.data.ProductionLineLink;
import mekanism.cloudworks.common.service.CloudworksDetachedState;
import net.minecraft.nbt.NBTTagCompound;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.transfer.CloudworksWarehouseCaps;
import mekanism.cloudworks.common.data.CloudworksAuxiliaryNodeData;
import mekanism.cloudworks.common.data.CloudworksChannelNode;
import mekanism.cloudworks.common.data.CloudworksExecutionState;
import mekanism.cloudworks.common.data.CloudworksProcessingBatches;
import mekanism.cloudworks.common.service.CloudworksModuleEvents;
import mekanism.cloudworks.common.service.CloudworksResourceSymbols;

/**
 * Cold boundary projection prepared by the worker, never by the server tick.
 */
public final class CloudworksRuntimeCheckpoint {
    private final CloudworksData cloud;
    private final CloudworksData runtimeCloud;
    private final List<UUID> lineIds;
    private final CloudworksWarehouseDelta delta;
    private final List<CloudworksRuntimeLineSnapshot> lineSnapshots;
    private final long runtimeRevision;
    private final long appliedSequence;
    private long mergedSequence;
    private final CloudworksRuntimeVersionSnapshot machineVersions, inputVersions, outputVersions,
            templateVersions, operationVersions, channelVersions, thermalVersions;
    private final double[] lineEnergy, lineCapacity, linkRemainders;
    private final long receivingVersion, transferVersion, sendingVersion;
    private final int projectedNodes;
    private final AtomicBoolean accepted = new AtomicBoolean();
    private final List<CloudworksModuleEvents.Event> moduleEvents;

    private CloudworksRuntimeCheckpoint(CloudworksData cloud, List<UUID> lineIds,
                                        List<CloudworksRuntimeLineSnapshot> lineSnapshots, CloudworksPlayerRuntime runtime,
                                        int projectedNodes, CloudworksWarehouseDelta delta,
                                        CloudworksRuntimeCheckpoint previous) {
        CloudworksRuntimeProgram program = runtime.program();
        moduleEvents = Collections.unmodifiableList(runtime.drainModuleEvents());
        receivingVersion = runtime.resources().version(program.receiving);
        transferVersion = runtime.resources().version(program.transfer);
        sendingVersion = runtime.resources().version(program.sending);
        this.cloud = cloud;
        this.delta = delta;
        // Private worker baseline excludes physical ingress merged at admission.
        runtimeCloud = cloud.copyForRuntime();
        this.projectedNodes = projectedNodes;
        this.lineIds = Collections.unmodifiableList(new ArrayList<>(lineIds));
        this.lineSnapshots = Collections.unmodifiableList(new ArrayList<>(lineSnapshots));
        this.runtimeRevision = runtime.revision();
        this.appliedSequence = runtime.appliedSequence();
        mergedSequence = appliedSequence;
        int machines = runtime.machines().size();
        machineVersions = CloudworksRuntimeVersionSnapshot.capture(machines, runtime.machines()::version,
                previous == null ? null : previous.machineVersions);
        thermalVersions = CloudworksRuntimeVersionSnapshot.capture(machines, runtime.thermal()::version,
                previous == null ? null : previous.thermalVersions);
        inputVersions = CloudworksRuntimeVersionSnapshot.capture(machines,
                node -> runtime.resources().version(runtime.machines().input(node)), previous == null ? null : previous.inputVersions);
        outputVersions = CloudworksRuntimeVersionSnapshot.capture(machines,
                node -> runtime.resources().version(runtime.machines().output(node)), previous == null ? null : previous.outputVersions);
        templateVersions = CloudworksRuntimeVersionSnapshot.capture(machines,
                node -> runtime.machines().configuration(node) < 0 ? 0
                        : runtime.resources().version(runtime.machines().configuration(node)), previous == null ? null : previous.templateVersions);
        operationVersions = CloudworksRuntimeVersionSnapshot.capture(machines, runtime.operations()::version,
                previous == null ? null : previous.operationVersions);
        channelVersions = CloudworksRuntimeVersionSnapshot.capture(runtime.channels().size(),
                channel -> runtime.resources().version(runtime.channels().inventory(channel)), previous == null ? null : previous.channelVersions);
        lineEnergy = new double[runtime.energy().lineCount()];
        lineCapacity = new double[lineEnergy.length];
        for (int line = 0; line < lineEnergy.length; line++) {
            lineEnergy[line] = runtime.energy().lineEnergy(line);
            lineCapacity[line] = runtime.energy().lineCapacity(line);
        }
        linkRemainders = new double[runtime.links().size()];
        for (int link = 0; link < linkRemainders.length; link++)
            linkRemainders[link] = runtime.links().remainder(link);
    }

    public CloudworksData cloud() {
        return cloud;
    }

    public List<UUID> lineIds() {
        return lineIds;
    }

    public NBTTagCompound coreTag() {
        return cloud.writeCore();
    }

    public CloudworksWarehouseDelta changes() {
        return delta;
    }

    public List<NBTTagCompound> lineTags() {
        List<NBTTagCompound> tags = new ArrayList<>(lineSnapshots.size());
        for (CloudworksRuntimeLineSnapshot line : lineSnapshots) tags.add(line.write());
        return Collections.unmodifiableList(tags);
    }

    public List<CloudworksRuntimeLineSnapshot> lineSnapshots() { return lineSnapshots; }

    public long runtimeRevision() {
        return runtimeRevision;
    }

    public boolean acceptOnce() {
        return accepted.compareAndSet(false, true);
    }

    public boolean accepted() { return accepted.get(); }

    public void publishModuleEvents() {
        if (!accepted.get()) throw new IllegalStateException("Cannot dispatch an unaccepted checkpoint");
        CloudworksModuleEvents.enqueue(moduleEvents);
    }

    public long appliedSequence() {
        return appliedSequence;
    }

    public int projectedNodes() {
        return projectedNodes;
    }

    /**
     * Absolute warehouse changes for the Runtime persistence intent. Values
     * are target amounts, so replay is idempotent even when the process died
     * after only some split warehouse files were replaced.
     */
    public NBTTagCompound warehouseDelta() {
        return delta.write();
    }

    /**
     * Replays only physical boundary mutations newer than the worker's mailbox watermark.
     */
    public void mergeIngress(List<CloudworksExternalEvent> events,
                             CloudworksResourceSymbols dictionary) {
        if (accepted.get()) throw new IllegalStateException("Runtime checkpoint was already published");
        long expected = appliedSequence;
        for (CloudworksExternalEvent event : events) {
            if (event.sequence() != ++expected) throw new IllegalArgumentException("Runtime ingress checkpoint gap");
            if (event.sequence() <= mergedSequence) continue;
            switch (event.kind()) {
                case RECEIVING_RESOURCE:
                    if (cloud.receiving().insert(dictionary.descriptor(event.resource()), event.amount()) != event.amount())
                        throw new IllegalStateException("Runtime receiving ingress does not fit");
                    delta.set("receiving", dictionary.descriptor(event.resource()),
                            cloud.receiving().amount(dictionary.descriptor(event.resource())));
                    break;
                case SENDING_RESOURCE:
                    if (cloud.sending().extract(dictionary.descriptor(event.resource()), event.amount()) != event.amount())
                        throw new IllegalStateException("Runtime sending ingress underflow");
                    delta.set("sending", dictionary.descriptor(event.resource()),
                            cloud.sending().amount(dictionary.descriptor(event.resource())));
                    break;
                case RECEIVING_ENERGY:
                    double receive = cloud.receivingEnergy() + event.energy();
                    if (!Double.isFinite(receive) || receive > cloud.receivingEnergyCapacity())
                        throw new IllegalStateException("Runtime receiving energy ingress does not fit");
                    cloud.setWarehouseEnergy(receive, cloud.sendingEnergy());
                    break;
                case SENDING_ENERGY:
                    double send = cloud.sendingEnergy() - event.energy();
                    if (send < 0) throw new IllegalStateException("Runtime sending energy ingress underflow");
                    cloud.setWarehouseEnergy(cloud.receivingEnergy(), send);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown Runtime ingress kind");
            }
            mergedSequence = event.sequence();
        }
    }

    static CloudworksRuntimeCheckpoint capture(CloudworksPlayerRuntime runtime,
                                               CloudworksRuntimeColdData templates, CloudworksDetachedState.Capture dictionary,
                                               CloudworksRuntimeCheckpoint previous) {
        CloudworksData cloud = previous == null ? templates.cloud() : previous.runtimeCloud.copyForRuntime();
        List<ProductionLineData> lines = new ArrayList<>();
        List<CloudworksRuntimeLineSnapshot> lineSnapshots = new ArrayList<>();
        CloudworksWarehouseDelta delta = new CloudworksWarehouseDelta();
        CloudworksRuntimeProgram program = runtime.program();
        CloudworksRuntimeResourceStore stock = runtime.resources();
        CloudworksRuntimeEnergyStore energy = runtime.energy();
        Map<UUID, Map<String, Double>> moduleCostsByNode = new HashMap<>();
        for (Map.Entry<String, Double> entry : runtime.costRemainders().entrySet()) {
            int separator = entry.getKey().indexOf('|');
            if (separator <= 0) continue;
            try {
                UUID node = UUID.fromString(entry.getKey().substring(0, separator));
                moduleCostsByNode.computeIfAbsent(node, ignored -> new LinkedHashMap<>())
                        .put(entry.getKey(), entry.getValue());
            } catch (IllegalArgumentException ignored) {
                // A stale key is ignored; the runtime will rebuild it on the
                // next module binding pass.
            }
        }
        if (previous == null || previous.receivingVersion != stock.version(program.receiving))
            reconcile(cloud.receiving(), stock, program.receiving, dictionary, previous, delta, "receiving");
        if (previous == null || previous.transferVersion != stock.version(program.transfer))
            reconcile(cloud.transfer(), stock, program.transfer, dictionary, previous, delta, "transfer");
        if (previous == null || previous.sendingVersion != stock.version(program.sending))
            reconcile(cloud.sending(), stock, program.sending, dictionary, previous, delta, "sending");
        cloud.setEnergy(energy.cloudEnergy(), energy.cloudCapacity(), energy.unlimited());
        cloud.setWarehouseEnergy(energy.receivingEnergy(), energy.sendingEnergy());
        cloud.setReceivingCursor(runtime.intakeCursor());
        cloud.setTransferCursor(runtime.intakeCursor());
        if (previous == null || previous.runtimeCloud.receivingCursor() != cloud.receivingCursor())
            delta.cursor("receiving", cloud.receivingCursor());
        if (previous == null || previous.runtimeCloud.transferCursor() != cloud.transferCursor())
            delta.cursor("transfer", cloud.transferCursor());
        CloudworksRuntimeMachineStore machines = runtime.machines();
        CloudworksRuntimeOperationStore operations = runtime.operations();
        int projectedNodes = 0;
        boolean[] changedLines = new boolean[program.machineStart.length];
        for (int lineIndex = 0; lineIndex < program.machineStart.length; lineIndex++) {
            changedLines[lineIndex] = previous == null || previous.lineChanged(runtime, lineIndex);
            if (!changedLines[lineIndex]) continue;
            ProductionLineData line = templates.cachedLine(lineIndex);
            line.setEnergy(energy.lineEnergy(lineIndex), energy.lineCapacity(lineIndex));
            int nodeIndex = program.machineStart[lineIndex];
            int localNode = 0;
            for (MachineNodeData node : line.machines()) {
                if (previous != null && !previous.physicalNodeChanged(runtime, nodeIndex)) {
                    nodeIndex = program.physicalEnd[nodeIndex];
                    localNode++;
                    continue;
                }
                boolean inputChanged = previous == null || previous.inputVersions.get(nodeIndex)
                        != stock.version(machines.input(nodeIndex));
                boolean outputChanged = previous == null || previous.outputVersions.get(nodeIndex)
                        != stock.version(machines.output(nodeIndex));
                boolean templateChanged = machines.configuration(nodeIndex) >= 0 && (previous == null
                        || previous.templateVersions.get(nodeIndex) != stock.version(machines.configuration(nodeIndex)));
                boolean operationChanged = previous == null || previous.operationVersions.get(nodeIndex)
                        != operations.version(nodeIndex);
                operations.resetSelection(nodeIndex);
                CloudworksOperation promise = operationChanged ? null : node.operation();
                if (operationChanged && operations.present(nodeIndex))
                    promise = operation(operations, nodeIndex, dictionary);
                Map<String, CloudworksExecutionState> branches = new LinkedHashMap<>();
                for (int branch = nodeIndex + 1; branch < program.physicalEnd[nodeIndex]; branch++) {
                    operations.resetSelection(branch);
                    CloudworksOperation branchPromise = operations.present(branch) ? operation(operations, branch, dictionary) : null;
                    branches.put(program.branchNames[branch], new CloudworksExecutionState(
                            machines.progress(branch), machines.duration(branch), CloudworksRuntimeProgram.state(machines.state(branch)),
                            machines.outputSequence(branch), branchPromise, runtime.thermal().snapshot(branch),
                            remainders(runtime, branch, dictionary)));
                }
                Map<String, CloudworksExecutionState> processing = new LinkedHashMap<>();
                for (int execution = nodeIndex; execution < program.physicalEnd[nodeIndex]; execution++) {
                    List<CloudworksRuntimeBatch> batches = operations.batches(execution);
                    for (int index = 1; index < batches.size(); index++) {
                        CloudworksRuntimeBatch batch = batches.get(index);
                        operations.select(execution, batch);
                        processing.put(CloudworksProcessingBatches.key(program.branchNames[execution], batch.sequence),
                                new CloudworksExecutionState(batch.progress, batch.duration,
                                        CloudworksRuntimeProgram.state(batch.state), batch.sequence,
                                        operation(operations, execution, dictionary), null, Collections.emptyMap()));
                    }
                    operations.resetSelection(execution);
                }
                node.setGenerationRemainders(remainders(runtime, nodeIndex, dictionary));
                Map<String, mekanism.cloudworks.common.data.CloudworksResourceCredit> credits = new LinkedHashMap<>();
                for (int execution = nodeIndex; execution < program.physicalEnd[nodeIndex]; execution++)
                    if (program.creditProcesses[execution] != null && machines.processCredit(execution) > 0)
                        credits.put(program.creditKeys[execution], new mekanism.cloudworks.common.data.CloudworksResourceCredit(
                                program.resourceDescriptors.get(program.creditProcesses[execution].creditResource), machines.processCredit(execution)));
                node.setProcessCredits(credits);
                node.setModuleCostRemainders(moduleCostsByNode.getOrDefault(node.nodeId(),
                        Collections.emptyMap()));
                node.applyRuntimeDelta(inputChanged ? stacks(stock, machines.input(nodeIndex), dictionary) : null,
                        outputChanged ? stacks(stock, machines.output(nodeIndex), dictionary) : null,
                        templateChanged ? stacks(stock, machines.configuration(nodeIndex), dictionary) : null, promise,
                        branches, processing,
                        machines.progress(nodeIndex), machines.duration(nodeIndex),
                        CloudworksRuntimeProgram.state(machines.state(nodeIndex)));
                node.setThermalState(runtime.thermal().snapshot(nodeIndex));
                node.setOutputSequence(machines.outputSequence(nodeIndex));
                templates.cacheMachine(lineIndex, localNode++, node, inputChanged, outputChanged, operationChanged, templateChanged);
                projectedNodes++;
                nodeIndex = program.physicalEnd[nodeIndex];
            }
            int channel = program.channelStart[lineIndex];
            int localChannel = 0;
            for (CloudworksChannelNode node : line.channels()) {
                if (previous == null || previous.channelVersions.get(channel)
                        != stock.version(runtime.channels().inventory(channel))) {
                    reconcile(node.buffer(), stock, runtime.channels().inventory(channel), dictionary, previous,
                            null, null);
                    templates.cacheChannel(lineIndex, localChannel, node);
                }
                channel++;
                localChannel++;
            }
            for (CloudworksAuxiliaryNodeData node : line.auxiliaries()) {
                Integer inventory = program.auxiliaryInventories.get(node.nodeId());
                if (inventory != null)
                    reconcile(node.input(), stock, inventory, dictionary, previous, null, null);
                node.setCostRemainders(moduleCostsByNode.getOrDefault(node.nodeId(),
                        Collections.emptyMap()));
            }
            lines.add(line);
        }
        Map<UUID, ProductionLineLink> byId = new HashMap<>();
        for (ProductionLineData line : lines)
            for (ProductionLineLink link : line.links()) byId.put(link.linkId(), link);
        UUID[] linkIds = runtime.linkIds();
        for (int link = 0; link < runtime.links().size(); link++) {
            ProductionLineLink target = byId.get(linkIds[link]);
            if (target != null && (previous == null
                    || previous.linkRemainders[link] != runtime.links().remainder(link))) {
                target.setRatioRemainder(runtime.links().remainder(link));
            }
        }
        List<UUID> changedLineIds = new ArrayList<>(lines.size());
        for (int line = 0; line < program.machineStart.length; line++)
            if (changedLines[line]) {
                ProductionLineData projection = templates.cachedLine(line);
                changedLineIds.add(projection.lineId());
                lineSnapshots.add(templates.snapshot(line, projection));
            }
        return new CloudworksRuntimeCheckpoint(cloud, changedLineIds, lineSnapshots, runtime, projectedNodes,
                delta, previous);
    }

    private static CloudworksOperation operation(CloudworksRuntimeOperationStore operations, int node,
            CloudworksDetachedState.Capture dictionary) {
        CloudworksRuntimeBatch batch = operations.current(node);
        return new CloudworksOperation(batch.id, operations.operations(node), operations.duration(node),
                stacks(operations.inputs(node), dictionary), stacks(operations.outputs(node), dictionary),
                stacks(operations.chanceOutputs(node), dictionary), operations.extraEnergyPerTick(node),
                operations.completionPrepared(node), operations.recipeId(node))
                .withModulePower(operations.modulePower(node)).withLifecycleId(operations.lifecycleId(node))
                .withParallelism(batch.parallelUnits, batch.sequence).withEnvironmentAxis(batch.requiredEnvironmentAxis);
    }

    private boolean nodeChanged(CloudworksPlayerRuntime runtime, int node) {
        CloudworksRuntimeMachineStore machines = runtime.machines();
        return machineVersions.get(node) != machines.version(node)
                || thermalVersions.get(node) != runtime.thermal().version(node)
                || inputVersions.get(node) != runtime.resources().version(machines.input(node))
                || outputVersions.get(node) != runtime.resources().version(machines.output(node))
                || machines.configuration(node) >= 0
                && templateVersions.get(node) != runtime.resources().version(machines.configuration(node))
                || operationVersions.get(node) != runtime.operations().version(node);
    }

    private boolean physicalNodeChanged(CloudworksPlayerRuntime runtime, int node) {
        for (int branch = node; branch < runtime.program().physicalEnd[node]; branch++)
            if (nodeChanged(runtime, branch)) return true;
        return false;
    }

    private static Map<QIOResourceDescriptor, Double> remainders(
            CloudworksPlayerRuntime runtime, int node, CloudworksDetachedState.Capture dictionary) {
        Map<QIOResourceDescriptor, Double> result = new LinkedHashMap<>();
        int[] resources = runtime.program().generationResources[node];
        for (int output = 0; output < resources.length; output++) {
            double value = runtime.machines().generationRemainder(node, output);
            if (value > 0) result.put(dictionary.descriptor(resources[output]), value);
        }
        return result;
    }

    private boolean lineChanged(CloudworksPlayerRuntime runtime, int line) {
        if (lineEnergy[line] != runtime.energy().lineEnergy(line)
                || lineCapacity[line] != runtime.energy().lineCapacity(line)) return true;
        CloudworksRuntimeProgram program = runtime.program();
        for (int node = program.machineStart[line]; node < program.machineEnd[line]; node++)
            if (nodeChanged(runtime, node)) return true;
        for (int channel = program.channelStart[line]; channel < program.channelEnd[line]; channel++)
            if (channelVersions.get(channel) != runtime.resources().version(runtime.channels().inventory(channel)))
                return true;
        for (int link = program.linkStart[line]; link < program.linkEnd[line]; link++)
            if (linkRemainders[link] != runtime.links().remainder(link)) return true;
        return false;
    }

    /**
     * Warehouse/channel projection step. With no baseline (epoch start) the
     * destination is rebuilt in full; afterwards only the store's dirty entries
     * are extracted and re-inserted,
     * so a busy warehouse costs O(changed entries) per tick instead of
     * O(total entries) of object churn. Server-side ingress stays separate from
     * the worker baseline and lands through its mailbox before the next capture.
     */
    private static void reconcile(CloudworksInventory destination, CloudworksRuntimeResourceStore stock,
                                  int inventory, CloudworksDetachedState.Capture dictionary, CloudworksRuntimeCheckpoint previous,
                                  CloudworksWarehouseDelta delta, String warehouse) {
        Set<QIOResourceDescriptor> known = previous == null
                ? new HashSet<>() : null;
        int[] changed = stock.drainChanges(inventory);
        for (int entry : changed) {
            int resource = stock.resource(entry);
            long amount = stock.amountAt(entry);
            QIOResourceDescriptor descriptor = dictionary.descriptor(resource);
            if (known != null) known.add(descriptor);
            long old = destination.amount(descriptor);
            if (old == amount) continue;
            // Only the three cloud warehouses have a per-resource-family cap.
            // Channel buffers (including renewable special sources) are bounded
            // by their own inventory capacity and must not inherit warehouse policy.
            if (amount > old && warehouse != null
                    && amount > CloudworksWarehouseCaps.capOf(
                    descriptor.getFamily(), dictionary.view().warehouseItemTypeCap))
                throw new IllegalStateException("Runtime warehouse projection overflow");
            if (old > 0 && destination.extract(descriptor, old) != old)
                throw new IllegalStateException("Runtime warehouse projection underflow");
            if (amount > 0) {
                if (stock.reservedAt(entry) > 0)
                    throw new IllegalStateException("Reserved Runtime warehouse resource");
                if (destination.insertIgnoringTypeCap(descriptor, amount) != amount)
                    throw new IllegalStateException("Runtime warehouse projection capacity exceeded");
            }
            if (delta != null && amount != old) delta.set(warehouse, descriptor, amount);
        }
        if (known != null) {
            // Epoch start: entries present only in the destination (baseline
            // residue) must not survive into the projection.
            for (QIOResourceDescriptor leftover : destination.snapshot().keySet())
                if (!known.contains(leftover)) {
                    long amount = destination.amount(leftover);
                    if (destination.extract(leftover, amount) != amount)
                        throw new IllegalStateException("Runtime warehouse baseline residue");
                    if (delta != null) delta.set(warehouse, leftover, 0);
                }
        }
    }

    private static List<CloudworksResourceStack> stacks(CloudworksRuntimeResourceStore stock, int inventory,
                                                        CloudworksDetachedState.Capture dictionary) {
        List<CloudworksResourceStack> result = new ArrayList<>();
        for (int entry = stock.first(inventory); entry >= 0; entry = stock.next(entry)) {
            if (stock.amountAt(entry) == 0) continue;
            CloudworksResourceStack stack = new CloudworksResourceStack(dictionary.descriptor(stock.resource(entry)),
                    stock.amountAt(entry));
            if (stock.reservedAt(entry) > 0) stack.reserve(stock.reservedAt(entry));
            result.add(stack);
        }
        return result;
    }

    private static List<CloudworksResourceStack> stacks(CloudworksRuntimeRecipe.Allocation allocation,
                                                        CloudworksDetachedState.Capture dictionary) {
        List<CloudworksResourceStack> result = new ArrayList<>();
        for (int index = 0; index < allocation.ids.length; index++)
            if (allocation.amounts[index] > 0)
                result.add(new CloudworksResourceStack(dictionary.descriptor(allocation.ids[index]), allocation.amounts[index]));
        return result;
    }
}
