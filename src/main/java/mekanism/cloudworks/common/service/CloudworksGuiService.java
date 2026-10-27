package mekanism.cloudworks.common.service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mekanism.cloudworks.common.network.CloudworksGuiCodec;
import mekanism.cloudworks.common.network.PacketCloudworksData;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Callable;

/** Bounded cold projection workers, separate from simulation workers. */
public final class CloudworksGuiService implements AutoCloseable {
    private final ThreadPoolExecutor workers;
    private final Set<FutureTask<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public CloudworksGuiService(int threads) {
        if (threads < 1) throw new IllegalArgumentException("Invalid GUI worker count");
        AtomicInteger ids = new AtomicInteger();
        workers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(128), task -> {
                    Thread worker = new Thread(task, "Cloudworks-Gui-" + ids.incrementAndGet());
                    worker.setDaemon(true);
                    return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Null leaves the query with its container for a later admission attempt. */
    public FutureTask<Result> submit(CloudworksGuiRequest request, long generation, byte[][] previous) {
        return submit(() -> build(request, generation, previous));
    }

    public FutureTask<CloudworksResourceQuery.Result> submit(CloudworksResourceQuery request) {
        return submit(request::execute);
    }

    private <T> FutureTask<T> submit(Callable<T> computation) {
        if (closed) return null;
        FutureTask<T> task = new FutureTask<T>(computation) {
            @Override protected void done() { pending.remove(this); }
        };
        pending.add(task);
        try {
            workers.execute(task);
            return task;
        } catch (RejectedExecutionException fullOrClosed) {
            task.cancel(false);
            return null;
        }
    }

    private static Result build(CloudworksGuiRequest request, long generation, byte[][] previous) {
        CloudworksGuiData data;
        byte[][] encoded;
        String status = request.status;
        try {
            if (request.source == null) throw new IllegalStateException("GUI source unavailable");
            data = CloudworksGuiDataBuilder.build(request.source, request.offset, request.graphLine,
                    request.cacheType, request.warehouses, request.recycle, request.choices,
                    request.resourceOffset, request.resourceTotal, request.resourceContext, request.catalog);
            encoded = CloudworksGuiCodec.encode(data);
        } catch (RuntimeException failed) {
            data = CloudworksGuiData.EMPTY;
            encoded = CloudworksGuiCodec.encode(data);
            status = "cloudworks.error.storage";
        }
        List<PacketCloudworksData.Message> packets = PacketCloudworksData.Message.create(request.window,
                request.session, request.sequence, generation, encoded, previous, status);
        return new Result(data, encoded, packets);
    }

    @Override
    public void close() {
        closed = true;
        for (FutureTask<?> task : pending) task.cancel(true);
        workers.shutdownNow();
        boolean interrupted = false;
        while (!workers.isTerminated()) {
            try {
                if (workers.awaitTermination(1, TimeUnit.SECONDS)) break;
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public static final class Result {
        public final CloudworksGuiData data;
        public final byte[][] encoded;
        public final List<PacketCloudworksData.Message> packets;

        private Result(CloudworksGuiData data, byte[][] encoded, List<PacketCloudworksData.Message> packets) {
            this.data = data;
            this.encoded = encoded;
            this.packets = Collections.unmodifiableList(new ArrayList<>(packets));
        }
    }
}
