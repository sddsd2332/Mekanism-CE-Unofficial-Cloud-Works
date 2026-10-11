package mekanism.cloudworks.common.service;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded per-owner executor for structural edit commands (Runtime
 * architecture §3.3, GLM handoff §3.3).
 *
 * <p>The server thread validates sessions, permissions and physical inputs,
 * then captures an immutable {@link CloudworksStructuralCommand}; this worker
 * applies it under the manager lock (topology validation, upgrade clamping and
 * the O(nodes) line serialization never touch the server tick), and the result
 * hops back to the main thread for the GUI response. Commands for one owner are
 * FIFO, while different owners can be scheduled independently. A saturated
 * queue refuses rather than stalls; the client reports busy and retries.</p>
 */
public final class CloudworksCommandService implements AutoCloseable {
    private static final int CAPACITY = 256;

    private static final UUID LEGACY_OWNER = new UUID(0L, 0L);
    private final ThreadPoolExecutor workers;
    private final Object queueLock = new Object();
    private final Map<UUID, ArrayDeque<Runnable>> ownerQueues = new HashMap<>();
    private final java.util.Set<UUID> scheduledOwners = new java.util.HashSet<>();
    private final AtomicInteger queuedCommands = new AtomicInteger();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private volatile boolean closed;

    public CloudworksCommandService() {
        this(Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2)));
    }

    public CloudworksCommandService(int workerCount) {
        if (workerCount < 1) throw new IllegalArgumentException("Invalid command worker count");
        AtomicInteger ids = new AtomicInteger();
        workers = new ThreadPoolExecutor(workerCount, workerCount, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CAPACITY), task -> {
            Thread thread = new Thread(task, "Cloudworks-Command-" + ids.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Queues one command. False means the bounded queue is full (or the service
     * is closing); the caller must degrade to a busy response, never block.
     */
    public boolean submit(Runnable command) {
        return submit(LEGACY_OWNER, command);
    }

    public boolean submit(UUID owner, Runnable command) {
        if (owner == null || command == null) return false;
        synchronized (queueLock) {
            if (closed || queuedCommands.get() >= CAPACITY) {
                refused.incrementAndGet();
                return false;
            }
            ownerQueues.computeIfAbsent(owner, ignored -> new ArrayDeque<>()).addLast(command);
            queuedCommands.incrementAndGet();
            submitted.incrementAndGet();
            if (!scheduledOwners.add(owner)) return true;
            try {
                // Keep admission and scheduling under one lock. A rejection
                // cannot then remove a later command from the same owner.
                workers.execute(() -> drainOwner(owner));
                return true;
            } catch (RejectedExecutionException fullOrClosed) {
                ArrayDeque<Runnable> queue = ownerQueues.get(owner);
                if (queue != null) {
                    if (queue.peekLast() == command) queue.pollLast();
                    queuedCommands.decrementAndGet();
                    if (queue.isEmpty()) ownerQueues.remove(owner);
                }
                scheduledOwners.remove(owner);
                refused.incrementAndGet();
                return false;
            }
        }
    }

    private void drainOwner(UUID owner) {
        while (true) {
            Runnable command;
            synchronized (queueLock) {
                ArrayDeque<Runnable> queue = ownerQueues.get(owner);
                if (queue == null || queue.isEmpty()) {
                    ownerQueues.remove(owner);
                    scheduledOwners.remove(owner);
                    return;
                }
                command = queue.removeFirst();
                queuedCommands.decrementAndGet();
            }
            try {
                command.run();
            } catch (RuntimeException failure) {
                System.err.println("Cloudworks command failed: " + failure);
            } catch (Throwable fatal) {
                System.err.println("Cloudworks command worker failed: " + fatal);
                synchronized (queueLock) {
                    ArrayDeque<Runnable> remaining = ownerQueues.get(owner);
                    if (remaining == null || remaining.isEmpty()) {
                        ownerQueues.remove(owner);
                        scheduledOwners.remove(owner);
                    } else {
                        try {
                            workers.execute(() -> drainOwner(owner));
                        } catch (RejectedExecutionException rejected) {
                            scheduledOwners.remove(owner);
                        }
                    }
                }
                return;
            } finally {
                applied.incrementAndGet();
            }
        }
    }

    public long submitted() { return submitted.get(); }
    public long applied() { return applied.get(); }
    public long refused() { return refused.get(); }

    @Override
    public void close() {
        synchronized (queueLock) {
            closed = true;
        }
        workers.shutdown();
        boolean interrupted = false;
        while (!workers.isTerminated()) {
            try { workers.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException interruptedWait) { interrupted = true; }
        }
        synchronized (queueLock) {
            ownerQueues.clear();
            scheduledOwners.clear();
            queuedCommands.set(0);
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
