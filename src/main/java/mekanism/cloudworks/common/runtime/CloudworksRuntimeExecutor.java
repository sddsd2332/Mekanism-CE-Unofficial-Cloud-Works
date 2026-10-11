package mekanism.cloudworks.common.runtime;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/** Parallel executor for player-owned Cloudworks Runtime worlds. */
public final class CloudworksRuntimeExecutor implements AutoCloseable {
    private static final long SHUTDOWN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(120);
    private final ThreadPoolExecutor workers;
    private final Map<UUID, CloudworksPlayerRuntime> runtimes = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> queued = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicInteger workerIds = new java.util.concurrent.atomic.AtomicInteger();

    public CloudworksRuntimeExecutor(int workerCount) {
        if (workerCount < 1) throw new IllegalArgumentException("Invalid Runtime worker count");
        workers = new ThreadPoolExecutor(workerCount, workerCount, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(128, workerCount * 8)), task -> {
            Thread thread = new Thread(task, "Cloudworks-Runtime-" + workerIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    CloudworksPlayerRuntime runtime(UUID owner) {
        return runtimes.computeIfAbsent(owner, CloudworksPlayerRuntime::new);
    }

    boolean submitWork(UUID owner, Runnable work) {
        java.util.Objects.requireNonNull(work);
        if (!queued.add(owner)) return false;
        try {
            workers.execute(() -> {
                try {
                    work.run();
                } finally {
                    queued.remove(owner);
                }
            });
        } catch (RejectedExecutionException rejected) {
            queued.remove(owner);
            return false;
        }
        return true;
    }

    public void remove(UUID owner) {
        if (queued.contains(owner)) throw new IllegalStateException("Cannot unload queued Runtime");
        runtimes.remove(owner);
    }

    boolean hasRuntime(UUID owner) { return runtimes.containsKey(owner); }

    public int size() {
        return runtimes.size();
    }

    public int pendingCount() { return queued.size(); }
    public boolean scheduled(UUID owner) { return queued.contains(owner); }

    public void clear() {
        if (!queued.isEmpty()) throw new IllegalStateException("Cannot clear running Runtime executor");
        runtimes.clear();
    }

    @Override
    public void close() {
        close(System.nanoTime() + SHUTDOWN_TIMEOUT_NANOS);
    }

    void close(long deadlineNanos) {
        workers.shutdown();
        boolean interrupted = false;
        while (!workers.isTerminated() && deadlineNanos > System.nanoTime()) {
            try {
                long remaining = deadlineNanos - System.nanoTime();
                if (workers.awaitTermination(Math.max(1, Math.min(TimeUnit.SECONDS.toNanos(1), remaining)),
                        TimeUnit.NANOSECONDS)) break;
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (!workers.isTerminated()) {
            System.err.println("Cloudworks Runtime workers exceeded shutdown deadline; interrupting outstanding tasks");
            workers.shutdownNow();
        }
        runtimes.clear();
        queued.clear();
        if (interrupted) Thread.currentThread().interrupt();
    }
}
