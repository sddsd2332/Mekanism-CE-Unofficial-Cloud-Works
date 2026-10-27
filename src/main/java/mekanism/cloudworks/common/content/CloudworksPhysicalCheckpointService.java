package mekanism.cloudworks.common.content;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import mekanism.common.tile.prefab.TileEntityContainerBlock;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;

/** Main-thread captures, one physical chunk write per tick, detached disk verification. */
public final class CloudworksPhysicalCheckpointService {
    private static final Map<Key, List<Request>> pending = new LinkedHashMap<>();
    private static boolean stopping;

    private CloudworksPhysicalCheckpointService() {}

    public static CompletableFuture<Void> checkpoint(TileEntityContainerBlock tile) {
        if (!(tile.getWorld() instanceof WorldServer world)) throw new IllegalStateException("Not a server port");
        CompletableFuture<Void> completion = checkpoint(world, tile.getPos().getX() >> 4,
                tile.getPos().getZ() >> 4, tile.writeToNBT(new NBTTagCompound()));
        tile.markDirty();
        return completion;
    }

    static CompletableFuture<Void> checkpoint(WorldServer world, int x, int z, NBTTagCompound snapshot) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Key key = new Key(world, x, z);
        synchronized (pending) {
            if (stopping) {
                completion.completeExceptionally(new IllegalStateException("Cloudworks checkpoints are stopping"));
                return completion;
            }
            pending.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new Request(snapshot.copy(), completion));
        }
        return completion;
    }

    public static void flush() {
        flush(CloudworksPhysicalCheckpointService::saveAndConfirm);
    }

    static void flush(Backend backend) {
        Map<Key, List<Request>> requests;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            requests = new LinkedHashMap<>();
            Iterator<Map.Entry<Key, List<Request>>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext() && requests.size() < 8) {
                Map.Entry<Key, List<Request>> entry = iterator.next();
                requests.put(entry.getKey(), entry.getValue());
                iterator.remove();
            }
        }
        for (Map.Entry<Key, List<Request>> group : requests.entrySet()) {
            Key key = group.getKey();
            try {
                Map<BlockPos, NBTTagCompound> latest = new LinkedHashMap<>();
                for (Request request : group.getValue()) {
                    NBTTagCompound tile = request.tile;
                    latest.put(new BlockPos(tile.getInteger("x"),
                            tile.getInteger("y"), tile.getInteger("z")), tile);
                }
                List<NBTTagCompound> expected = new ArrayList<>(latest.values());
                backend.save(key.world, key.x, key.z, expected)
                        .whenComplete((ignored, failure) -> {
                            for (Request request : group.getValue()) {
                                if (failure == null) request.completion.complete(null);
                                else request.completion.completeExceptionally(failure);
                            }
                        });
            } catch (Exception failure) {
                for (Request request : group.getValue()) request.completion.completeExceptionally(failure);
            }
        }
    }

    private static CompletableFuture<Void> saveAndConfirm(WorldServer world, int x, int z,
            List<NBTTagCompound> expected) throws Exception {
        if (!(world.getChunkProvider().chunkLoader instanceof AnvilChunkLoader loader))
            throw new IllegalStateException("Cloudworks requires verifiable Anvil saves");
        Chunk loaded = world.getChunkProvider().getLoadedChunk(x, z);
        if (loaded != null) loader.saveChunk(world, loaded);
        return CloudworksTerminalPersistence.confirmChunk(loader.chunkSaveLocation, x, z, expected);
    }

    interface Backend {
        CompletableFuture<Void> save(WorldServer world, int x, int z, List<NBTTagCompound> expected) throws Exception;
    }

    public static void start() {
        synchronized (pending) {
            for (List<Request> requests : pending.values())
                for (Request request : requests)
                    request.completion.completeExceptionally(new IllegalStateException("Previous world ended"));
            pending.clear();
            stopping = false;
        }
    }

    public static void shutdown() {
        synchronized (pending) { stopping = true; }
        while (true) {
            synchronized (pending) { if (pending.isEmpty()) return; }
            flush();
        }
    }

    private static final class Request {
        final NBTTagCompound tile;
        final CompletableFuture<Void> completion;
        Request(NBTTagCompound tile, CompletableFuture<Void> completion) {
            this.tile = tile;
            this.completion = completion;
        }
    }

    private static final class Key {
        final WorldServer world;
        final int x, z;
        Key(WorldServer world, int x, int z) { this.world = world; this.x = x; this.z = z; }
        @Override public int hashCode() { return Objects.hash(System.identityHashCode(world), x, z); }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && world == key.world && x == key.x && z == key.z;
        }
    }
}
