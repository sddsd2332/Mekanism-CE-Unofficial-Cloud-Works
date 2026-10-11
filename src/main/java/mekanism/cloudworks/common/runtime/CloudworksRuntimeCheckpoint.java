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
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import net.minecraft.nbt.NBTTagCompound;

/** Cold boundary projection prepared by the worker, never by the server tick. */
public final class CloudworksRuntimeCheckpoint {
    private final CloudworksData cloud;
    private final CloudworksData runtimeCloud;
    private final List<UUID> lineIds;
    private NBTTagCompound cloudTag;
    private final CloudworksWarehouseDelta delta;
    private final List<NBTTagCompound> lineTags;
    private final long runtimeRevision;
    private final long appliedSequence;
    private final long[] machineVersions, inputVersions, outputVersions, templateVersions, operationVersions, channelVersions;
    private final long[] thermalVersions;
    private final double[] lineEnergy, lineCapacity, linkRemainders;
    private final long receivingVersion, transferVersion, sendingVersion;
    private final int projectedNodes;
    private final java.util.concurrent.atomic.AtomicBoolean accepted = new java.util.concurrent.atomic.AtomicBoolean();

    private CloudworksRuntimeCheckpoint(CloudworksData cloud, List<UUID> lineIds,
            List<NBTTagCompound> lineTags, CloudworksPlayerRuntime runtime,
            int projectedNodes, CloudworksWarehouseDelta delta) {
        CloudworksRuntimeProgram program = runtime.program();
        receivingVersion = runtime.resources().version(program.receiving);
        transferVersion = runtime.resources().version(program.transfer);
        sendingVersion = runtime.resources().version(program.sending);
        this.cloudTag = null;
        this.cloud = cloud;
        this.delta = delta;
        // Private worker baseline excludes physical ingress merged at admission.
        runtimeCloud = cloud.copyForRuntime();
        this.projectedNodes = projectedNodes;
        this.lineIds = Collections.unmodifiableList(new ArrayList<>(lineIds));
        this.lineTags = Collections.unmodifiableList(new ArrayList<>(lineTags));
        this.runtimeRevision = runtime.revision();
        this.appliedSequence = runtime.appliedSequence();
        int machines = runtime.machines().size();
        machineVersions = new long[machines];
        inputVersions = new long[machines];
        outputVersions = new long[machines];
        templateVersions = new long[machines];
        operationVersions = new long[machines];
        thermalVersions = new long[machines];
        for (int node = 0; node < machines; node++) {
            machineVersions[node] = runtime.machines().version(node);
            thermalVersions[node] = runtime.thermal().version(node);
            inputVersions[node] = runtime.resources().version(runtime.machines().input(node));
            outputVersions[node] = runtime.resources().version(runtime.machines().output(node));
            templateVersions[node] = runtime.machines().configuration(node) < 0 ? 0
                    : runtime.resources().version(runtime.machines().configuration(node));
            operationVersions[node] = runtime.operations().version(node);
        }
        channelVersions = new long[runtime.channels().size()];
        for (int channel = 0; channel < channelVersions.length; channel++)
            channelVersions[channel] = runtime.resources().version(runtime.channels().inventory(channel));
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

    public CloudworksData cloud() { return cloud; }
    public List<UUID> lineIds() { return lineIds; }
    public synchronized NBTTagCompound cloudTag() {
        if (cloudTag == null) cloudTag = cloud.write();
        return cloudTag;
    }
    public NBTTagCompound coreTag() { return cloud.writeCore(); }
    public CloudworksWarehouseDelta changes() { return delta; }
    public List<NBTTagCompound> lineTags() { return lineTags; }
    public long runtimeRevision() { return runtimeRevision; }
    public boolean acceptOnce() { return accepted.compareAndSet(false, true); }
    public long appliedSequence() { return appliedSequence; }
    public int projectedNodes() { return projectedNodes; }

    /**
     * Absolute warehouse changes for the Runtime persistence intent. Values
     * are target amounts, so replay is idempotent even when the process died
     * after only some split warehouse files were replaced.
     */
    public NBTTagCompound warehouseDelta() {
        return delta.write();
    }

    /** Replays only physical boundary mutations newer than the worker's mailbox watermark. */
    public void mergeIngress(List<CloudworksExternalEvent> events,
            mekanism.cloudworks.common.service.CloudworksResourceSymbols dictionary) {
        if (accepted.get()) throw new IllegalStateException("Runtime checkpoint was already published");
        long expected = appliedSequence;
        for (CloudworksExternalEvent event : events) {
            if (event.sequence() != ++expected) throw new IllegalArgumentException("Runtime ingress checkpoint gap");
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
        }
        if (!events.isEmpty()) cloudTag = null;
    }

    static CloudworksRuntimeCheckpoint capture(CloudworksPlayerRuntime runtime,
            CloudworksRuntimeColdData templates, CloudworksDetachedState.Capture dictionary,
            CloudworksRuntimeCheckpoint previous) {
        CloudworksData cloud = previous == null ? templates.cloud() : previous.runtimeCloud.copyForRuntime();
        List<ProductionLineData> lines = new ArrayList<>();
        List<NBTTagCompound> lineTags = new ArrayList<>();
        CloudworksWarehouseDelta delta = new CloudworksWarehouseDelta();
        CloudworksRuntimeProgram program = runtime.program();
        CloudworksRuntimeResourceStore stock = runtime.resources();
        CloudworksRuntimeEnergyStore energy = runtime.energy();
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
                if (previous != null && !previous.nodeChanged(runtime, nodeIndex)) {
                    nodeIndex++;
                    localNode++;
                    continue;
                }
                boolean inputChanged = previous == null || previous.inputVersions[nodeIndex]
                        != stock.version(machines.input(nodeIndex));
                boolean outputChanged = previous == null || previous.outputVersions[nodeIndex]
                        != stock.version(machines.output(nodeIndex));
                boolean templateChanged = machines.configuration(nodeIndex) >= 0 && (previous == null
                        || previous.templateVersions[nodeIndex] != stock.version(machines.configuration(nodeIndex)));
                boolean operationChanged = previous == null || previous.operationVersions[nodeIndex]
                        != operations.version(nodeIndex);
                CloudworksOperation promise = operationChanged ? null : node.operation();
                if (operationChanged && operations.present(nodeIndex))
                    promise = new CloudworksOperation(operations.operations(nodeIndex), operations.duration(nodeIndex),
                            stacks(operations.inputs(nodeIndex), dictionary), stacks(operations.outputs(nodeIndex), dictionary),
                            operations.extraEnergyPerTick(nodeIndex));
                node.applyRuntimeDelta(inputChanged ? stacks(stock, machines.input(nodeIndex), dictionary) : null,
                        outputChanged ? stacks(stock, machines.output(nodeIndex), dictionary) : null,
                        templateChanged ? stacks(stock, machines.configuration(nodeIndex), dictionary) : null, promise,
                        machines.progress(nodeIndex), machines.duration(nodeIndex),
                        CloudworksRuntimeProgram.state(machines.state(nodeIndex)));
                node.setThermalState(runtime.thermal().snapshot(nodeIndex));
                templates.cacheMachine(lineIndex, localNode++, node, inputChanged, outputChanged, operationChanged, templateChanged);
                projectedNodes++;
                nodeIndex++;
            }
            int channel = program.channelStart[lineIndex];
            int localChannel = 0;
            for (mekanism.cloudworks.common.data.CloudworksChannelNode node : line.channels()) {
                if (previous == null || previous.channelVersions[channel]
                        != stock.version(runtime.channels().inventory(channel))) {
                    reconcile(node.buffer(), stock, runtime.channels().inventory(channel), dictionary, previous,
                            null, null);
                    templates.cacheChannel(lineIndex, localChannel, node);
                }
                channel++;
                localChannel++;
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
                templates.cacheLink(target);
            }
        }
        List<UUID> changedLineIds = new ArrayList<>(lines.size());
        for (int line = 0; line < program.machineStart.length; line++)
            if (changedLines[line]) {
                ProductionLineData projection = templates.cachedLine(line);
                changedLineIds.add(projection.lineId());
                lineTags.add(templates.tag(line, projection));
            }
        return new CloudworksRuntimeCheckpoint(cloud, changedLineIds, lineTags, runtime, projectedNodes,
                delta);
    }

    private boolean nodeChanged(CloudworksPlayerRuntime runtime, int node) {
        CloudworksRuntimeMachineStore machines = runtime.machines();
        return machineVersions[node] != machines.version(node)
                || thermalVersions[node] != runtime.thermal().version(node)
                || inputVersions[node] != runtime.resources().version(machines.input(node))
                || outputVersions[node] != runtime.resources().version(machines.output(node))
                || machines.configuration(node) >= 0
                    && templateVersions[node] != runtime.resources().version(machines.configuration(node))
                || operationVersions[node] != runtime.operations().version(node);
    }

    private boolean lineChanged(CloudworksPlayerRuntime runtime, int line) {
        if (lineEnergy[line] != runtime.energy().lineEnergy(line)
                || lineCapacity[line] != runtime.energy().lineCapacity(line)) return true;
        CloudworksRuntimeProgram program = runtime.program();
        for (int node = program.machineStart[line]; node < program.machineEnd[line]; node++)
            if (nodeChanged(runtime, node)) return true;
        for (int channel = program.channelStart[line]; channel < program.channelEnd[line]; channel++)
            if (channelVersions[channel] != runtime.resources().version(runtime.channels().inventory(channel))) return true;
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
        java.util.Set<mekanism.api.qio.resource.QIOResourceDescriptor> known = previous == null
                ? new java.util.HashSet<>() : null;
        int[] changed = stock.drainChanges(inventory);
        for (int entry : changed) {
            int resource = stock.resource(entry);
            long amount = stock.amountAt(entry);
            mekanism.api.qio.resource.QIOResourceDescriptor descriptor = dictionary.descriptor(resource);
            if (known != null) known.add(descriptor);
            long old = destination.amount(descriptor);
            if (old == amount) continue;
            if (old > 0 && destination.extract(descriptor, old) != old)
                throw new IllegalStateException("Runtime warehouse projection underflow");
            if (amount > 0) {
                if (stock.reservedAt(entry) > 0)
                    throw new IllegalStateException("Reserved Runtime warehouse resource");
                if (amount > old && destination.insertable(descriptor, amount) < amount)
                    throw new IllegalStateException("Runtime warehouse projection overflow");
                if (destination.insertIgnoringTypeCap(descriptor, amount) != amount)
                    throw new IllegalStateException("Runtime warehouse projection capacity exceeded");
            }
            if (delta != null && amount != old) delta.set(warehouse, descriptor, amount);
        }
        if (known != null) {
            // Epoch start: entries present only in the destination (baseline
            // residue) must not survive into the projection.
            for (mekanism.api.qio.resource.QIOResourceDescriptor leftover : destination.snapshot().keySet())
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
