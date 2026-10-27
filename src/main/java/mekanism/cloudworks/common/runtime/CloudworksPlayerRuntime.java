package mekanism.cloudworks.common.runtime;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekanism.cloudworks.common.service.CloudworksModuleEvents;

/**
 * Single mutable owner of one player's cloud execution state.
 *
 * <p>The worker is the sole writer. Mailbox sequence admission and application
 * are separate: accepting an event never claims it was already executed.</p>
 */
public final class CloudworksPlayerRuntime {
    private final UUID owner;
    private final CloudworksRuntimeMachineStore machines = new CloudworksRuntimeMachineStore();
    private final CloudworksRuntimeResourceStore resources = new CloudworksRuntimeResourceStore();
    private final CloudworksRuntimeEnergyStore energy = new CloudworksRuntimeEnergyStore();
    private final CloudworksRuntimeChannelStore channels = new CloudworksRuntimeChannelStore();
    private final CloudworksRuntimeLinkStore links = new CloudworksRuntimeLinkStore();
    private final CloudworksRuntimeOperationStore operations = new CloudworksRuntimeOperationStore();
    private final CloudworksRuntimeThermalStore thermal = new CloudworksRuntimeThermalStore();
    private final ConcurrentLinkedQueue<CloudworksExternalEvent> externalEvents = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<String, Double> costRemainders = new LinkedHashMap<>();
    Map<String, Double> costRemainders() { return costRemainders; }
    private CloudworksRuntimeProgram program;
    private CloudworksRuntimeSimulation simulation;
    private UUID[] linkIds = new UUID[0];
    private long acceptedSequence;
    private volatile long appliedSequence;
    private volatile long revision;
    private final List<CloudworksModuleEvents.Event> moduleEvents =
            new ArrayList<>();
    void moduleEvent(CloudworksModuleEvents.Event event) { moduleEvents.add(event); }
    List<CloudworksModuleEvents.Event> drainModuleEvents() {
        List<CloudworksModuleEvents.Event> result = new ArrayList<>(moduleEvents);
        moduleEvents.clear();
        return result;
    }

    CloudworksPlayerRuntime(UUID owner) {
        this.owner = Objects.requireNonNull(owner);
    }

    public UUID owner() { return owner; }
    CloudworksRuntimeMachineStore machines() { return machines; }
    CloudworksRuntimeResourceStore resources() { return resources; }
    CloudworksRuntimeEnergyStore energy() { return energy; }
    CloudworksRuntimeChannelStore channels() { return channels; }
    CloudworksRuntimeLinkStore links() { return links; }
    CloudworksRuntimeOperationStore operations() { return operations; }
    CloudworksRuntimeThermalStore thermal() { return thermal; }
    public long revision() { return revision; }
    long stateVersion() {
        return resources.version() + machines.version() + energy.version() + links.version() + operations.version() + thermal.version();
    }
    public synchronized long externalSequence() { return acceptedSequence; }
    public long appliedSequence() { return appliedSequence; }
    public boolean isRunning() { return running.get(); }

    public synchronized boolean enqueue(CloudworksExternalEvent event) {
        Objects.requireNonNull(event);
        long sequence = event.sequence();
        if (sequence <= acceptedSequence) return false;
        if (sequence != acceptedSequence + 1) throw new IllegalArgumentException("Runtime mailbox sequence gap");
        externalEvents.add(event);
        acceptedSequence = sequence;
        return true;
    }

    synchronized void resetMailbox(long sequence) {
        if (running.get() || sequence < 0) throw new IllegalStateException("Cannot reset active Runtime mailbox");
        externalEvents.clear();
        acceptedSequence = appliedSequence = sequence;
    }

    void applyIngress(CloudworksExternalEvent event) {
        CloudworksRuntimeProgram current = program();
        switch (event.kind()) {
            case RECEIVING_RESOURCE:
                if (event.resource() >= current.resourceCodec.length)
                    throw new IllegalArgumentException("Uncompiled Runtime ingress resource");
                if (event.amount() > current.receivingCapacity - resources.total(current.receiving)
                        || event.amount() > current.resourceCap[event.resource()]
                            - resources.amount(current.receiving, event.resource()))
                    throw new IllegalStateException("Runtime ingress capacity exceeded");
                resources.add(current.receiving, event.resource(), event.amount());
                break;
            case SENDING_RESOURCE:
                resources.take(current.sending, event.resource(), event.amount());
                break;
            case RECEIVING_ENERGY:
                double receive = energy.receivingEnergy() + event.energy();
                if (!Double.isFinite(receive) || receive > energy.receivingCapacity())
                    throw new IllegalStateException("Runtime ingress energy capacity exceeded");
                energy.setRelays(receive, energy.sendingEnergy(), energy.receivingCapacity(), energy.sendingCapacity());
                break;
            case SENDING_ENERGY:
                double send = energy.sendingEnergy() - event.energy();
                if (send < 0) throw new IllegalStateException("Runtime egress energy underflow");
                energy.setRelays(energy.receivingEnergy(), send, energy.receivingCapacity(), energy.sendingCapacity());
                break;
            default:
                throw new IllegalArgumentException("Unknown Runtime ingress kind");
        }
    }

    boolean knowsResource(int resource) {
        return resource < 0 || program != null && resource < program.resourceCodec.length;
    }

    void install(CloudworksRuntimeProgram next) {
        if (running.get()) throw new IllegalStateException("Cannot install running Runtime");
        moduleEvents.clear();
        program = Objects.requireNonNull(next);
        simulation = new CloudworksRuntimeSimulation(this, next);
    }

    CloudworksRuntimeProgram program() {
        if (program == null) throw new IllegalStateException("Runtime is not installed");
        return program;
    }

    void setLinkIds(UUID[] ids) { linkIds = ids.clone(); }
    UUID[] linkIds() { return linkIds; }
    int intakeCursor() { return simulation == null ? 0 : simulation.cursor(); }

    void tick() {
        tickInternal(0);
    }

    void tick(int clockTick) {
        if (clockTick < 1 || clockTick > 24000) throw new IllegalArgumentException("Invalid cloud clock");
        tickInternal(clockTick);
    }

    private void tickInternal(int clockTick) {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("Concurrent Runtime tick");
        try {
            CloudworksExternalEvent event;
            while ((event = externalEvents.peek()) != null) {
                if (event.sequence() != appliedSequence + 1)
                    throw new IllegalStateException("Runtime applied sequence gap");
                event.apply(this);
                externalEvents.poll();
                appliedSequence = event.sequence();
            }
            if (simulation != null) {
                if (clockTick != 0) simulation.syncClock(clockTick);
                simulation.tick();
            }
            revision++;
        } finally {
            running.set(false);
        }
    }
}
