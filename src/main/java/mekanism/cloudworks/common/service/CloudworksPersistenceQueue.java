package mekanism.cloudworks.common.service;

import net.minecraft.nbt.NBTTagCompound;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Single-owner bounded persistence pipeline. The main thread hands durable
 * batches to one IO thread and never touches the disk itself: intent batches
 * keep the atomic cross-file replay semantics, plain batches are ordered file
 * writes. Capacity is bounded; when the queue is full a submitter is refused
 * immediately (never degraded to applying the batch on the caller thread — a
 * slow disk must not stall the server thread), and the Runtime pauses new
 * captures while batches are outstanding.
 *
 * <p>Failure model: an IO error keeps the durable intent file as evidence and
 * poisons the manager. {@link AbortPoint} fault injection stops the worker
 * between the real write steps, leaving exactly the file residue a killed
 * process would leave; the caller then discards the manager instance and
 * verifies recovery from a fresh one.</p>
 */
public final class CloudworksPersistenceQueue implements AutoCloseable {
    private static final long SHUTDOWN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(120);
    private final ArrayBlockingQueue<Batch> queue;
    private final Sink sink;
    private final Thread worker;
    /**
     * Serializes batch application between the worker thread and any external
     * applier (load-time replay); the submit path itself never applies.
     */
    private final java.util.concurrent.locks.ReentrantLock applyLock =
            new java.util.concurrent.locks.ReentrantLock(true);
    /**
     * Refusals because the queue stayed full; diagnostics only.
     */
    private final java.util.concurrent.atomic.AtomicLong busyRefusals =
            new java.util.concurrent.atomic.AtomicLong();
    private final Object idle = new Object();
    /**
     * Owners whose batches failed durably; their fault domain only. Lock-free: written on the IO thread, read anywhere.
     */
    private final java.util.Set<UUID> failedOwners = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Batches dropped because their owner was already poisoned; diagnostics only.
     */
    private final java.util.concurrent.atomic.AtomicLong droppedBatches =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean closed;
    private volatile boolean closing;
    private volatile boolean dead;
    private volatile Throwable lastIoFailure;
    private volatile AbortPoint armedAbort = AbortPoint.NONE;
    /**
     * Test injection: the next applied batch fails with a plain RuntimeException after the abort check.
     */
    private volatile boolean debugFailNextApply;
    /**
     * Test injection: one-shot pause of the worker's next poll loop (backlog tests).
     */
    private volatile long debugWorkerPauseMillis;
    /**
     * Batches removed from the queue and not yet fully applied; guarded by {@link #idle}.
     */
    private final java.util.concurrent.atomic.AtomicInteger activeBatches =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ConcurrentMap<UUID, java.util.concurrent.atomic.AtomicInteger> ownerBacklogs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger pendingSubmissions =
            new java.util.concurrent.atomic.AtomicInteger();
    CloudworksPersistenceQueue(int capacity, Sink sink, String threadName) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
        this.sink = sink;
        this.worker = new Thread(this::run, threadName);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * Enqueues one atomic intent batch without waiting on the server thread.
     * A full queue is reported as busy and the caller keeps its mutation
     * uncommitted so it can retry on a later tick.
     */
    void submitIntent(UUID owner, NBTTagCompound intent) {
        submit(new Batch(owner, intent, Collections.emptyList()));
    }

    /**
     * Enqueues ordered plain file writes; a full queue refuses IMMEDIATELY
     * (counted in busyRefusals) — main-thread callers degrade or retry, they
     * never stall waiting for capacity.
     */
    void submitFiles(FileWrite... writes) {
        submit(new Batch(null, null, Arrays.asList(writes)));
    }

    /**
     * Owner-attributed plain writes: a durable failure poisons ONLY this
     * owner's fault domain (GPT fail-stop review); null stays global.
     */
    void submitFiles(UUID owner, FileWrite... writes) {
        submit(new Batch(owner, null, Arrays.asList(writes)));
    }

    /**
     * Bounded-wait degrade form for interactive ordered writes: waits up to
     * {@code timeoutMillis} for capacity before refusing. The timed offer runs
     * OUTSIDE the idle monitor (the worker polls under it — holding the
     * monitor would starve the drain we are waiting for).
     */
    void submitFilesBounded(UUID owner, long timeoutMillis, FileWrite... writes) {
        Batch batch = new Batch(owner, null, Arrays.asList(writes));
        java.util.concurrent.atomic.AtomicInteger ownerCount;
        synchronized (idle) {
            if (closed || closing || dead) throw new IllegalStateException("Cloudworks persistence queue is closed");
            if (batch.owner != null && failedOwners.contains(batch.owner))
                throw new IllegalStateException("cloudworks.error.storage");
            ownerCount = batch.owner == null ? null : ownerBacklogs.computeIfAbsent(
                    batch.owner, ignored -> new java.util.concurrent.atomic.AtomicInteger());
            if (ownerCount != null) ownerCount.incrementAndGet();
            pendingSubmissions.incrementAndGet();
        }
        boolean offered = false;
        try {
            offered = queue.offer(batch, timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        synchronized (idle) {
            pendingSubmissions.decrementAndGet();
            if (!offered && ownerCount != null) ownerCount.decrementAndGet();
            idle.notifyAll();
        }
        if (!offered) {
            busyRefusals.incrementAndGet();
            throw new IllegalStateException("cloudworks.error.busy");
        }
    }

    private void submit(Batch batch) {
        if (closed || closing || dead) throw new IllegalStateException("Cloudworks persistence queue is closed");
        if (batch.owner != null && failedOwners.contains(batch.owner))
            // P1 semantics per fault domain: a failed owner's later submissions
            // are refused instead of silently queued and later dropped.
            throw new IllegalStateException("cloudworks.error.storage");
        synchronized (idle) {
            if (closed || closing || dead) throw new IllegalStateException("Cloudworks persistence queue is closed");
            if (!queue.offer(batch)) {
                busyRefusals.incrementAndGet();
                throw new IllegalStateException("cloudworks.error.busy");
            }
            if (batch.owner != null)
                ownerBacklogs.computeIfAbsent(batch.owner, ignored -> new java.util.concurrent.atomic.AtomicInteger())
                        .incrementAndGet();
            idle.notifyAll();
        }
    }

    /**
     * True while batches are queued or one is being applied.
     */
    boolean backlogged() {
        return !closed && (!queue.isEmpty() || activeBatches.get() > 0
                || pendingSubmissions.get() > 0);
    }

    /**
     * Outstanding batch count including the one being applied.
     */
    public int backlogDepth() {
        int queued = queue.size();
        return closed || dead ? 0 : queued + activeBatches.get() + pendingSubmissions.get();
    }

    int ownerBacklog(UUID owner) {
        java.util.concurrent.atomic.AtomicInteger count = owner == null ? null : ownerBacklogs.get(owner);
        return count == null ? 0 : Math.max(0, count.get());
    }

    /**
     * Diagnostic components of {@link #backlogged()}; tests only.
     */
    public String debugBacklogComponents() {
        return "closed=" + closed + " dead=" + dead + " queueSize=" + queue.size()
                + " activeBatches=" + activeBatches.get();
    }

    /**
     * Waits until every submitted batch is durable; bounded by the caller's
     * timeout. Returns false when the wait expired, the pipeline closed, or
     * the pipeline died with batches still QUEUED (they will never apply — a
     * poisoned queue must never report "everything drained" while unapplied
     * batches remain). The batch being applied when the poison hit still
     * settles through its finally, so it is waited for, not failed early.
     */
    boolean drain(long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (idle) {
            while (!queue.isEmpty() || activeBatches.get() > 0 || pendingSubmissions.get() > 0) {
                if (closed || dead && !queue.isEmpty()) return false;
                long left = deadline - System.nanoTime();
                if (left <= 0) return false;
                idle.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
            }
            return true;
        }
    }

    /**
     * Arms the next batch to die at the given step; tests only.
     */
    void debugArmAbort(AbortPoint point) {
        armedAbort = point;
    }

    /**
     * Arms the next applied batch to fail with a plain RuntimeException; tests only.
     */
    void debugFailNextApply() {
        debugFailNextApply = true;
    }

    /**
     * One-shot pause of the worker's next poll loop; backlog and busy-refusal tests only.
     */
    void debugPauseWorker(long millis) {
        debugWorkerPauseMillis = Math.max(0L, millis);
    }

    /**
     * Refusals because the queue stayed full; diagnostics only.
     */
    public long busyRefusals() {
        return busyRefusals.get();
    }

    /**
     * True when this owner's durable batches failed (fault domain poisoned).
     */
    public boolean ownerFailed(UUID owner) {
        return owner != null && failedOwners.contains(owner);
    }

    /**
     * Dropped batches of failed owners; diagnostics only.
     */
    public long droppedBatches() {
        return droppedBatches.get();
    }

    boolean dead() {
        return dead;
    }

    /**
     * The failure that poisoned this pipeline; kept as evidence, never null once dead.
     */
    public Throwable lastIoFailure() {
        return lastIoFailure;
    }

    private void run() {
        try {
            // A poisoned pipeline stops accepting work: later batches stay queued
            // and unapplied instead of being written after a known failure (P1).
            while (!closed && !dead) {
                long pause = debugWorkerPauseMillis;
                if (pause > 0) {
                    debugWorkerPauseMillis = 0;   // one-shot: never strand the worker
                    try {
                        Thread.sleep(pause);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                Batch batch;
                java.util.List<Batch> grouped = new java.util.ArrayList<>();
                // Poll under applyLock: the inline take-over path uses the same
                // lock, so FIFO order holds across both appliers.
                applyLock.lock();
                synchronized (idle) {
                    batch = queue.poll();
                    if (batch != null) {
                        grouped.add(batch);
                        activeBatches.incrementAndGet();
                    }
                }
                if (batch != null && batch.portIntent()) {
                    // A short group-commit window absorbs other ports of this
                    // owner; submitters never wait and settlement remains durable-first.
                    synchronized (idle) {
                            Batch next;
                            while ((next = queue.peek()) != null && next.portIntent()
                                    && batch.owner.equals(next.owner)) grouped.add(queue.poll());
                            if (grouped.size() > 1) {
                                NBTTagCompound merged = new NBTTagCompound();
                                merged.setInteger("schema", 2);
                                merged.setString("owner", batch.owner.toString());
                                net.minecraft.nbt.NBTTagList operations = new net.minecraft.nbt.NBTTagList();
                                for (Batch member : grouped) {
                                    net.minecraft.nbt.NBTTagList entries = member.intent.getTagList("operations", 10);
                                    for (int index = 0; index < entries.tagCount(); index++)
                                        operations.appendTag(entries.getCompoundTagAt(index));
                                }
                                merged.setTag("operations", operations);
                                batch = new Batch(batch.owner, merged, Collections.emptyList());
                            }
                        activeBatches.addAndGet(grouped.size() - 1);
                    }
                }
                if (batch == null) {
                    applyLock.unlock();
                    synchronized (idle) {
                        // Re-check under the monitor: a batch submitted between the
                        // poll and this wait must not cost a full wait interval.
                        if (!closed && queue.isEmpty()) idle.wait(10);
                    }
                    continue;
                }
                try {
                    runBatch(batch);
                } finally {
                    applyLock.unlock();
                    synchronized (idle) {
                        activeBatches.addAndGet(-grouped.size());
                        if (batch.owner != null) {
                            java.util.concurrent.atomic.AtomicInteger count = ownerBacklogs.get(batch.owner);
                            if (count != null) count.addAndGet(-grouped.size());
                        }
                        idle.notifyAll();
                    }
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (Throwable fatal) {
            dead = true;
            lastIoFailure = fatal;
            if (!(fatal instanceof SimulatedCrash)) {
                try { sink.noteIoFailure(null, fatal); }
                catch (Throwable reportingFailure) {
                    System.err.println("Cloudworks IO could not report fatal failure: " + reportingFailure);
                }
            }
            synchronized (idle) {
                idle.notifyAll();
            }
        }
    }

    private void runBatch(Batch batch) {
        if (batch.owner != null && failedOwners.contains(batch.owner)) {
            // Dropped, never applied: the owner is already poisoned and must
            // recover through the reload replay; applying later work after a
            // known failure would violate the P1 write guarantee.
            droppedBatches.incrementAndGet();
            return;
        }
        applyLock.lock();
        try {
            long batchStart = System.nanoTime();
            try {
                sink.beginBatch();
                if (debugFailNextApply) {
                    debugFailNextApply = false;
                    throw new RuntimeException("debug apply failure");
                }
                AbortPoint abort = armedAbort;
                armedAbort = AbortPoint.NONE;
                if (batch.intent != null) {
                    sink.writeIntentFile(batch.owner, batch.intent);
                    if (abort == AbortPoint.NONE) abort = consumeIfArmed(AbortPoint.AFTER_INTENT);
                    if (abort == AbortPoint.AFTER_INTENT) throw new SimulatedCrash(abort);
                    sink.applyIntentRelaxed(batch.owner, batch.intent, abort);
                    sink.commitBatch();
                    long elapsedMs = (System.nanoTime() - batchStart) / 1_000_000L;
                    if (elapsedMs > 100)
                        System.err.println("CLOUDWORKS-IO SLOW intent batch owner=" + batch.owner
                                + " ms=" + elapsedMs + " lineEntries=" + batch.intent.getTagList("lines", 10).tagCount());
                    return;
                }
                sink.writePlainFiles(batch.files);
                sink.commitBatch();
                if (consumeIfArmed(AbortPoint.AFTER_INTENT) != AbortPoint.NONE)
                    throw new SimulatedCrash(AbortPoint.AFTER_INTENT);
            } catch (SimulatedCrash crash) {
                // Simulated process death: freeze the pipeline globally, keep the residue.
                dead = true;
                lastIoFailure = crash;   // dead/recoveryRequired/lastIoFailure stay consistent
                synchronized (idle) {
                    idle.notifyAll();
                }
                throw crash;
            } catch (RuntimeException failure) {
                lastIoFailure = failure;
                if (batch.owner == null) {
                    // Unattributable batch: the conservative global poison stays.
                    dead = true;
                } else {
                    // Per-owner fault isolation (GPT review): only this owner's
                    // domain fails; the worker keeps serving every other owner
                    // and later batches of the failed owner are dropped.
                    failedOwners.add(batch.owner);
                }
                // Record the failure BEFORE waking drain waiters: a caller that
                // wakes up must already observe the poisoned domain, otherwise
                // "drained" reads as success for a batch that just failed.
                sink.noteIoFailure(batch.owner, failure);
                synchronized (idle) {
                    idle.notifyAll();
                }
            }
        } finally {
            sink.endBatch();
            applyLock.unlock();
        }
    }

    private AbortPoint consumeIfArmed(AbortPoint point) {
        return armedAbort == point ? point : AbortPoint.NONE;
    }

    @Override
    public void close() {
        close(System.nanoTime() + SHUTDOWN_TIMEOUT_NANOS);
    }

    void close(long deadlineNanos) {
        synchronized (idle) {
            closing = true;
            idle.notifyAll();
        }
        boolean interrupted = false;
        while (!dead) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                System.err.println("Cloudworks IO shutdown deadline exceeded; preserving pending durable batches: "
                        + backlogDepth());
                break;
            }
            try {
                long waitMillis = Math.max(1, Math.min(TimeUnit.SECONDS.toMillis(30),
                        TimeUnit.NANOSECONDS.toMillis(remaining)));
                if (drain(waitMillis)) break;
                System.err.println("Cloudworks IO shutdown waiting for " + backlogDepth() + " durable batches");
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        closed = true;
        worker.interrupt();
        while (worker.isAlive() && deadlineNanos > System.nanoTime()) {
            long remaining = deadlineNanos - System.nanoTime();
            long waitMillis = Math.max(1, Math.min(TimeUnit.SECONDS.toMillis(30),
                    TimeUnit.NANOSECONDS.toMillis(remaining)));
            try { worker.join(waitMillis); }
            catch (InterruptedException interruption) { interrupted = true; }
        }
        if (worker.isAlive())
            System.err.println("Cloudworks IO worker did not stop before shutdown deadline; durable residue was retained");
        synchronized (idle) {
            idle.notifyAll();
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /**
     * Write steps a simulated process death can be armed between.
     */
    public enum AbortPoint {NONE, AFTER_INTENT, AFTER_FIRST_FILE, AFTER_MACHINE_CACHE, AFTER_INVENTORIES,
        AFTER_CLOUD, AFTER_LINE_DELETE, AFTER_DELETE}

    /**
     * Manager-side durable steps; all run on the IO thread only.
     */
    interface Sink {
        default void beginBatch() {}
        default void commitBatch() {}
        default void endBatch() {}

        void writeIntentFile(UUID owner, NBTTagCompound intent);

        void applyIntentRelaxed(UUID owner, NBTTagCompound intent, AbortPoint abort);

        void writePlainFiles(List<FileWrite> files);

        /**
         * One owner's batch failed durably; null owner means unattributable (global).
         */
        void noteIoFailure(UUID owner, Throwable failure);
    }

    /**
     * A plain ordered file write (runtime overlays, periodic clouds).
     */
    public static final class FileWrite {
        final java.nio.file.Path path;
        final NBTTagCompound tag;
        final Object monitor;
        final java.util.function.Supplier<NBTTagCompound> snapshot;

        FileWrite(java.nio.file.Path path, NBTTagCompound tag) {
            this.path = path;
            this.tag = tag;
            this.monitor = null;
            this.snapshot = null;
        }

        FileWrite(java.nio.file.Path path, Object monitor,
                java.util.function.Supplier<NBTTagCompound> snapshot) {
            this.path = path;
            this.tag = null;
            this.monitor = java.util.Objects.requireNonNull(monitor);
            this.snapshot = java.util.Objects.requireNonNull(snapshot);
        }
    }

    /**
     * Raised by armed fault injection; leaves the residue untouched.
     */
    static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(AbortPoint point) {
            super("simulated process death at " + point);
        }
    }

    private static final class Batch {
        final UUID owner;
        final NBTTagCompound intent;          // null for plain file batches
        final List<FileWrite> files;

        Batch(UUID owner, NBTTagCompound intent, List<FileWrite> files) {
            this.owner = owner;
            this.intent = intent;
            this.files = files;
        }

        boolean portIntent() { return intent != null && intent.getInteger("schema") == 2; }
    }
}
