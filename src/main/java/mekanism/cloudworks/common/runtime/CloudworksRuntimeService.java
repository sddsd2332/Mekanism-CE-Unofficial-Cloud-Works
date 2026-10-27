package mekanism.cloudworks.common.runtime;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mekanism.cloudworks.common.service.CloudworksSessionPolicy;
import mekanism.cloudworks.common.service.CloudworksDetachedState;
import mekanism.cloudworks.common.service.CloudworksResourceSymbols;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.api.environment.CloudworksGenerationPlan;
import mekanism.cloudworks.common.data.CloudworksData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.service.CloudworksNativeContracts;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.cloudworks.common.service.CloudworksRuntimeSettings;
import mekanism.common.Mekanism;

/**
 * Lifecycle facade for the new player-owned Runtime architecture.
 *
 * <p>Persistence projection and event delivery are deliberately exposed here,
 * so the server bridge does not reach into the Runtime stores directly.</p>
 */
public final class CloudworksRuntimeService implements AutoCloseable {
    private static final long WORKER_SLICE_NANOS = 20_000_000L;
    private static final long SHUTDOWN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(120);
    private final CloudworksRuntimeExecutor executor;
    private final int checkpointIntervalTicks;
    private final boolean durableCheckpoints;
    private final int coldCompilationLimit;
    private final Map<UUID, Player> players = new LinkedHashMap<>();
    private final Stats counters = new Stats();
    private final CloudworksExternalEventBridge externalBridge = new CloudworksExternalEventBridge();
    private long tick;
    private boolean closed;

    public CloudworksRuntimeService(int workers) {
        this(workers, CloudworksRuntimeSettings.checkpointIntervalTicks());
    }

    public CloudworksRuntimeService(int workers, int checkpointIntervalTicks) {
        this(workers, checkpointIntervalTicks, CloudworksRuntimeSettings.durableCheckpoints());
    }

    public CloudworksRuntimeService(int workers, int checkpointIntervalTicks, boolean durableCheckpoints) {
        if (checkpointIntervalTicks < 1) throw new IllegalArgumentException("Invalid checkpoint interval");
        this.checkpointIntervalTicks = checkpointIntervalTicks;
        this.durableCheckpoints = durableCheckpoints;
        coldCompilationLimit = Math.max(1, Math.min(2, workers));
        executor = new CloudworksRuntimeExecutor(workers);
    }

    CloudworksPlayerRuntime runtime(UUID owner) {
        return executor.runtime(owner);
    }

    public void unload(UUID owner) {
        Player player = players.get(owner);
        if (player != null && (player.task != null || executor.scheduled(owner))) return;
        executor.remove(owner);
        players.remove(owner);
    }

    public int size() {
        return executor.size();
    }

    public void beginTick() {
        tick++;
        counters.serverTicks++;
    }

    public void serverTick(CloudworksPlayerDataManager manager,
                           CloudworksSessionPolicy sessions) {
        if (closed) return;
        CloudworksNativeContracts.refresh();
        for (Player player : players.values()) {
            FutureTask<Completion> task = player.task;
            if (task == null || !task.isDone() || executor.scheduled(player.owner)) continue;
            if (manager.persistenceBacklogDepth() >= Math.max(1, manager.persistenceIoCapacity() / 2)) {
                counters.persistenceWaits++;
                continue;
            }
            try {
                Completion completion = task.get();
                CloudworksRuntimeCheckpoint checkpoint = completion.checkpoint;
                CloudworksPlayerDataManager.CommitStatus status =
                        checkpoint == null ? manager.runtimeCurrent(player.origin, player.managerRevision)
                            ? CloudworksPlayerDataManager.CommitStatus.EMPTY
                            : CloudworksPlayerDataManager.CommitStatus.CONFLICT
                        : manager.commitRuntimeCheckpoint(player.origin, player.managerRevision, checkpoint,
                            durableCheckpoints && (player.checkpoint == null
                                    || checkpointDue(player.owner, tick, checkpointIntervalTicks)));
                if (status != CloudworksPlayerDataManager.CommitStatus.CONFLICT) {
                    player.managerRevision = manager.revision(player.owner);
                    if (checkpoint != null) player.checkpoint = checkpoint;
                    player.stateVersion = completion.stateVersion;
                    player.simulatedTick = completion.simulatedTick;
                    player.clock.consume(completion.ticks);
                    player.failures = 0;
                    if (checkpoint != null) {
                        counters.commits++;
                        counters.projectedLines += checkpoint.lineIds().size();
                        counters.projectedNodes += checkpoint.projectedNodes();
                    } else counters.unchangedTicks += completion.ticks;
                    counters.simulatedTicks += completion.ticks;
                    counters.workerNanos += completion.nanos;
                } else {
                    player.origin = null;
                    player.checkpoint = null;
                    player.coldData = null;
                    counters.conflicts++;
                }
            } catch (ExecutionException failure) {
                player.failure = failure.getCause();
                player.failures++;
                player.retryTick = tick + 100;
                player.origin = null;
                player.checkpoint = null;
                Mekanism.logger.error("Cloudworks Runtime failed for owner {}", player.owner,
                        player.failure);
                counters.workerFailures++;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                player.failure = failure;
                player.failures++;
                player.retryTick = tick + 100;
                player.origin = null;
                player.checkpoint = null;
                counters.workerFailures++;
            } finally {
                player.task = null;
            }
        }
        List<CloudworksData> clouds = manager.cloudSnapshot();
        clouds.sort(Comparator.comparingLong(
                (CloudworksData cloud) -> {
                    Player player = players.get(cloud.owner());
                    return player == null ? 0 : player.lastSubmittedTick;
                }));
        for (CloudworksData cloud : clouds) {
            UUID owner = cloud.owner();
            Player player = players.get(owner);
            if (!cloud.enabled() || manager.recoveryRequired(owner)) {
                evictIdleOwner(owner, player, manager, manager.recoveryRequired(owner));
                continue;
            }
            List<UUID> eligible = manager.runtimeLineIds(cloud, sessions);
            if (eligible.isEmpty() && !relayEnergyNeedsRuntime(cloud)) {
                evictIdleOwner(owner, player, manager, false);
                continue;
            }
            if (player == null) player = players.computeIfAbsent(owner, Player::new);
            if (durableCheckpoints && checkpointDue(owner, tick, checkpointIntervalTicks)
                    && manager.persistenceBacklogDepth() < Math.max(1, manager.persistenceIoCapacity() / 2)) {
                try {
                    manager.flushRuntimePending(owner);
                } catch (RuntimeException ioBusy) {
                    counters.persistenceWaits++;
                }
            }
            if (player.failures >= 3 && tick < player.retryTick) continue;
            player.requestedTick++;
            player.clock.record(Math.max(1, manager.simulatedTick()));
            if (player.requestedTick - player.simulatedTick >= 20 && tick - player.warningTick >= 200) {
                player.warningTick = tick;
                Mekanism.logger.warn("Cloudworks Runtime backlog owner={} tickDebt={} pending={}",
                        owner, player.requestedTick - player.simulatedTick, executor.pendingCount());
            }
            if (player.task != null || executor.scheduled(owner)) continue;
            CloudworksPlayerRuntime world = executor.runtime(owner);
            List<CloudworksExternalEvent> ingress = manager.runtimeIngressAfter(owner, world.externalSequence());
            boolean unknownResource = ingress != null && ingress.stream().anyMatch(event -> !world.knowsResource(event.resource()));
            boolean rebuild = player.origin == null || !manager.onlyRuntimeIngressSince(owner, player.managerRevision)
                    || !eligible.equals(player.lineIds) || ingress == null || unknownResource;
            if (rebuild || player.checkpoint == null) {
                // Bound simultaneously retained cold snapshots, not normal owner ticks.
                int coldCompilations = 0;
                for (Player candidate : players.values())
                    if (candidate.task != null && candidate.checkpoint == null) coldCompilations++;
                if (coldCompilations >= coldCompilationLimit) continue;
            }
            if (rebuild) {
                try {
                    player.origin = manager.captureRuntimeSnapshot(owner, eligible);
                    player.capture = null;
                    player.environment = manager.generationContext(owner);
                    player.managerRevision = manager.revision(owner);
                    player.lineIds = eligible;
                    player.checkpoint = null;
                    player.compiledSequence = manager.runtimeExternalSequence(owner);
                    counters.compilations++;
                } catch (RuntimeException failure) {
                    player.origin = null;
                    player.failure = failure;
                    player.failures++;
                    player.retryTick = tick + 100;
                    counters.workerFailures++;
                    continue;
                }
            }
            if (player.checkpoint != null && ingress != null)
                for (CloudworksExternalEvent event : ingress) externalBridge.submit(owner, this, event);
            final Player selected = player;
            final CloudworksRuntimeCheckpoint previous = player.checkpoint;
            final CloudworksResourceSymbols symbols = manager.symbols(owner);
            final long startTick = player.simulatedTick;
            final long targetTick = player.requestedTick;
            final CloudworksRuntimeClock.Cursor clock = player.clock.snapshot();
            final long previousVersion = player.stateVersion;
            final List<ProductionLineData> decodedLines = previous == null ? player.origin.lines() : null;
            if (previous == null) CloudworksNativeContracts.prime(symbols, player.origin, decodedLines);
            final CloudworksRuntimeHeatFuel preFrozenHeat = previous == null && player.origin.hasHeatFuel()
                    ? CloudworksRuntimeHeatFuel.freeze(symbols, player.environment) : null;
            final Map<UUID, CloudworksGenerationPlan> preFrozenGeneration =
                    previous == null ? CloudworksNativeContracts.generationPlans(
                            player.origin, player.environment, decodedLines) : Collections.emptyMap();
            if (previous == null) CloudworksNativeContracts.primeGeneration(symbols, preFrozenGeneration);
            FutureTask<Completion> task = new FutureTask<>(() ->
                    CloudworksNativeContracts.worker(() -> {
                long started = System.nanoTime();
                if (previous == null) {
                    // Compile against a stable symbol generation. Native
                    // recipe freezing runs outside the monitor; if a port
                    // registers a new descriptor concurrently, rebuild once
                    // with the expanded append-only dictionary.
                    int compileAttempts = 0;
                    while (true) {
                        selected.capture = CloudworksDetachedState.captureRuntime(selected.origin, symbols,
                                selected.environment, decodedLines, preFrozenGeneration);
                        CloudworksRuntimeCatalog catalog = CloudworksRuntimeCatalog.prepare(selected.capture, symbols,
                                decodedLines, preFrozenHeat, preFrozenGeneration);
                        long catalogGeneration = symbols.generation();
                        CloudworksRuntimeProjection.load(world, selected.capture, catalog, symbols);
                        if (symbols.generation() == catalogGeneration || ++compileAttempts >= 3) break;
                    }
                    world.resetMailbox(selected.compiledSequence);
                    selected.coldData = new CloudworksRuntimeColdData(selected.origin, decodedLines);
                }
                long completed = startTick;
                do {
                    world.tick(clock.next());
                    completed++;
                } while (completed < targetTick && System.nanoTime() - started < WORKER_SLICE_NANOS);
                long version = world.stateVersion();
                if (previous != null && version == previousVersion)
                    return new Completion(null, completed, completed - startTick, System.nanoTime() - started, version);
                CloudworksRuntimeCheckpoint checkpoint;
                checkpoint = CloudworksRuntimeCheckpoint.capture(world, selected.coldData, selected.capture, previous);
                return new Completion(checkpoint, completed, completed - startTick, System.nanoTime() - started, version);
            }));
            if (executor.submitWork(owner, task)) {
                player.task = task;
                player.lastSubmittedTick = tick;
                counters.submissions++;
            } else counters.submitRefusals++;
        }
    }

    private void evictIdleOwner(UUID owner, Player player,
            CloudworksPlayerDataManager manager, boolean poisoned) {
        if (player != null && (player.task != null || executor.scheduled(owner))) return;
        try {
            if (!poisoned) manager.flushRuntimePending(owner);
            if (player != null || executor.hasRuntime(owner)) unload(owner);
        } catch (RuntimeException busy) {
            counters.persistenceWaits++;
        }
    }

    private static boolean relayEnergyNeedsRuntime(CloudworksData cloud) {
        return cloud.receivingEnergy() > 0 && cloud.energyFlowReceivingToCloud()
                || cloud.energy() > 0 && cloud.energyFlowCloudToSending();
    }

    static boolean checkpointDue(UUID owner, long tick, int interval) {
        return Math.floorMod(tick, interval) == Math.floorMod(owner.hashCode(), interval);
    }

    public Phase phase(UUID owner) {
        Player player = players.get(owner);
        if (player == null) return Phase.IDLE;
        if (player.task != null) return player.task.isDone()
                ? Phase.READY : Phase.QUEUED;
        return player.failures >= 3 ? Phase.FAILED : Phase.IDLE;
    }

    public Stats statsSnapshot() {
        long debt = 0;
        for (Player player : players.values()) debt += Math.max(0, player.requestedTick - player.simulatedTick);
        return counters.snapshot(debt, executor.pendingCount());
    }

    public void clear() {
        if (executor.pendingCount() != 0) throw new IllegalStateException("Runtime is still running");
        players.clear();
        executor.clear();
    }

    @Override
    public void close() {
        executor.close(System.nanoTime() + SHUTDOWN_TIMEOUT_NANOS);
        closed = true;
        clear();
    }

    public void close(CloudworksPlayerDataManager manager) {
        long deadlineNanos = System.nanoTime() + SHUTDOWN_TIMEOUT_NANOS;
        closed = true;
        executor.close(deadlineNanos);
        boolean interrupted = false;
        for (Player player : players.values()) {
            try {
                if (player.task != null) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) throw new TimeoutException("Cloudworks Runtime shutdown deadline exceeded");
                    Completion completed = player.task.get(remaining, TimeUnit.NANOSECONDS);
                    if (completed.checkpoint != null) {
                        while (true) {
                            try {
                                manager.commitRuntimeCheckpoint(player.origin, player.managerRevision, completed.checkpoint);
                                break;
                            } catch (RuntimeException failure) {
                                if (!persistenceBusy(failure) || !awaitShutdownPersistence(manager, deadlineNanos)) throw failure;
                            }
                        }
                    }
                }
                while (true) {
                    try {
                        manager.flushRuntimePending(player.owner);
                        break;
                    } catch (RuntimeException failure) {
                        if (!persistenceBusy(failure) || !awaitShutdownPersistence(manager, deadlineNanos)) throw failure;
                    }
                }
            } catch (InterruptedException interruption) {
                interrupted = true;
            } catch (TimeoutException timeout) {
                Mekanism.logger.warn(
                        "Cloudworks Runtime shutdown skipped a late checkpoint for owner={}", player.owner);
            } catch (ExecutionException | RuntimeException failure) {
                Mekanism.logger.warn("Cloudworks Runtime shutdown failed for owner {}",
                        player.owner, failure);
            }
        }
        clear();
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static boolean awaitShutdownPersistence(
            CloudworksPlayerDataManager manager, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) return false;
        try {
            manager.awaitPersistence(Math.max(1, Math.min(1_000, TimeUnit.NANOSECONDS.toMillis(remaining))));
            return deadlineNanos > System.nanoTime();
        } catch (RuntimeException failure) {
            throw failure;
        }
    }

    private static boolean persistenceBusy(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null && message.contains("cloudworks.error.busy");
    }

    public int pendingCount() { return executor.pendingCount(); }

    public boolean compiled(UUID owner) {
        Player player = players.get(owner);
        return player != null && player.origin != null && player.checkpoint != null;
    }

    public long tickDebt(UUID owner) {
        Player player = players.get(owner);
        return player == null ? 0 : Math.max(0, player.requestedTick - player.simulatedTick);
    }

    public enum Phase {IDLE, QUEUED, READY, FAILED}

    public static final class Stats {
        private long serverTicks, compilations, submissions, commits, conflicts, workerFailures,
                submitRefusals, simulatedTicks, workerNanos, tickDebt, persistenceWaits, unchangedTicks,
                projectedLines, projectedNodes;
        private int pending;

        public long serverTicks() { return serverTicks; }
        public long captures() { return compilations; }
        public long commits() { return commits; }
        public long conflicts() { return conflicts; }
        public long workerFailures() { return workerFailures; }
        public long submitRefusals() { return submitRefusals; }
        public long simulatedTicks() { return simulatedTicks; }
        public long workerNanos() { return workerNanos; }
        public long tickDebt() { return tickDebt; }
        public int pending() { return pending; }
        public long persistenceWaits() { return persistenceWaits; }
        public long unchangedTicks() { return unchangedTicks; }
        public long projectedLines() { return projectedLines; }
        public long projectedNodes() { return projectedNodes; }

        private Stats snapshot(long debt, int queued) {
            Stats copy = new Stats();
            copy.serverTicks = serverTicks;
            copy.compilations = compilations;
            copy.submissions = submissions;
            copy.commits = commits;
            copy.conflicts = conflicts;
            copy.workerFailures = workerFailures;
            copy.submitRefusals = submitRefusals;
            copy.simulatedTicks = simulatedTicks;
            copy.workerNanos = workerNanos;
            copy.tickDebt = debt;
            copy.pending = queued;
            copy.persistenceWaits = persistenceWaits;
            copy.unchangedTicks = unchangedTicks;
            copy.projectedLines = projectedLines;
            copy.projectedNodes = projectedNodes;
            return copy;
        }
    }

    private static final class Completion {
        final CloudworksRuntimeCheckpoint checkpoint;
        final long simulatedTick, ticks, nanos, stateVersion;

        Completion(CloudworksRuntimeCheckpoint checkpoint, long simulatedTick, long ticks, long nanos, long stateVersion) {
            this.checkpoint = checkpoint;
            this.simulatedTick = simulatedTick;
            this.ticks = ticks;
            this.nanos = nanos;
            this.stateVersion = stateVersion;
        }
    }

    private static final class Player {
        final UUID owner;
        final CloudworksRuntimeClock clock = new CloudworksRuntimeClock();
        long managerRevision, simulatedTick, requestedTick, retryTick, warningTick, compiledSequence,
                stateVersion, lastSubmittedTick;
        int failures;
        Throwable failure;
        CloudworksPlayerDataManager.VirtualSnapshot origin;
        CloudworksDetachedState.Capture capture;
        CloudworksGenerationContext environment;
        CloudworksRuntimeCheckpoint checkpoint;
        CloudworksRuntimeColdData coldData;
        List<UUID> lineIds;
        FutureTask<Completion> task;

        Player(UUID owner) { this.owner = owner; }
    }
}
