package mekanism.cloudworks.common.content;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.storage.ThreadedFileIOBase;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/** Physical terminal durability fences. Workers receive only detached NBT and file coordinates. */
public final class CloudworksTerminalPersistence {
    private static final Logger LOGGER =
            LogManager.getLogger("Cloudworks-Terminal-Persistence");
    private static ExecutorService confirmations = newExecutor();
    private static ExecutorService playerSaves;
    private static final Map<UUID, ArrayDeque<PlayerWrite>> playerWrites = new LinkedHashMap<>();
    private static final Set<UUID> loggedOutPlayers = new HashSet<>();
    private static final long LOGOUT_WAIT_MILLIS = 200;
    private static final int PLAYER_PUBLICATIONS_PER_TICK = 16;
    private static int publicationCursor;
    private static boolean shuttingDown;

    private CloudworksTerminalPersistence() { }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Cloudworks-Terminal-Confirmation");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static synchronized ExecutorService executor() {
        if (shuttingDown) throw new IllegalStateException("Cloudworks confirmations are stopping");
        if (confirmations == null || confirmations.isShutdown())
            confirmations = newExecutor();
        return confirmations;
    }

    private static synchronized ExecutorService playerExecutor() {
        if (shuttingDown) throw new IllegalStateException("Cloudworks player saves are stopping");
        if (playerSaves == null || playerSaves.isShutdown()) {
            AtomicInteger ids = new AtomicInteger();
            playerSaves = Executors.newFixedThreadPool(2, task -> {
                Thread thread = new Thread(task, "Cloudworks-Player-Save-" + ids.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }
        return playerSaves;
    }

    /** Stops world-bound confirmation work before a new server session starts. */
    public static void shutdown() {
        ExecutorService closing;
        ExecutorService closingPlayers;
        synchronized (CloudworksTerminalPersistence.class) {
            closing = confirmations;
            closingPlayers = playerSaves;
            if (closing == null && closingPlayers == null) {
                playerWrites.clear();
                loggedOutPlayers.clear();
                publicationCursor = 0;
                return;
            }
            shuttingDown = true;
            if (closing != null) closing.shutdown();
        }
        boolean interrupted = false;
        if (closingPlayers != null) closingPlayers.shutdown();
        for (ExecutorService service : new ExecutorService[]{closing, closingPlayers}) {
            if (service == null) continue;
            while (!service.isTerminated()) {
                try { service.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException interruption) { interrupted = true; }
            }
        }
        // Preparation is drained before publishing; no worker needs a callback onto the stopping server.
        for (UUID player : new ArrayList<>(playerWrites.keySet()))
            while (publishReady(player)) {}
        synchronized (CloudworksTerminalPersistence.class) {
            if (confirmations == closing) confirmations = null;
            if (playerSaves == closingPlayers) playerSaves = null;
            playerWrites.clear();
            loggedOutPlayers.clear();
            publicationCursor = 0;
            shuttingDown = false;
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** Captures player NBT on the server thread and writes it on the durability worker. */
    static CompletableFuture<Void> savePlayer(EntityPlayerMP player) {
        if (player == null) return failedPlayerSave("Missing player", null);
        if (loggedOutPlayers.contains(player.getUniqueID())) return revokedSave();
        MinecraftServer server = player.getServer();
        if (server == null || server.getPlayerList() == null)
            return failedPlayerSave("Missing Minecraft server or player list", player.getUniqueID());
        if (server.getPlayerList().getPlayerByUUID(player.getUniqueID()) != player) return revokedSave();
        World overworld = server.getWorld(0);
        if (overworld == null) return failedPlayerSave("Overworld is unavailable", player.getUniqueID());
        ISaveHandler saveHandler = overworld.getSaveHandler();
        if (saveHandler == null) return failedPlayerSave("World save handler is unavailable", player.getUniqueID());
        File directory;
        try {
            directory = playerDataDirectory(saveHandler.getWorldDirectory());
        } catch (IllegalArgumentException invalidDirectory) {
            return failedPlayerSave(invalidDirectory.getMessage(), player.getUniqueID());
        }
        NBTTagCompound snapshot = player.writeToNBT(new NBTTagCompound());
        String playerId = player.getCachedUniqueIdString();
        return savePlayerSnapshot(player.getUniqueID(), directory, playerId, snapshot);
    }

    static File playerDataDirectory(File worldDirectory) {
        if (worldDirectory == null)
            throw new IllegalArgumentException("World save directory is unavailable");
        File absoluteWorldDirectory = worldDirectory.getAbsoluteFile();
        File directory = new File(absoluteWorldDirectory, "playerdata");
        if (!directory.isAbsolute())
            throw new IllegalArgumentException("Player data directory must be absolute");
        return directory;
    }

    static CompletableFuture<Void> savePlayerSnapshot(UUID owner, File directory,
            String playerId, NBTTagCompound snapshot) {
        if (loggedOutPlayers.contains(owner)) return revokedSave();
        if (directory == null || !directory.isAbsolute())
            return failedPlayerSave("Player data directory must be absolute", owner);
        if (owner == null || playerId == null || playerId.isEmpty() || snapshot == null)
            return failedPlayerSave("Invalid player snapshot", owner);
        ExecutorService worker = playerExecutor();
        NBTTagCompound detached = snapshot.copy();
        PlayerWrite write = new PlayerWrite(directory.toPath().resolve(playerId + ".dat"), worker);
        write.prepared = CompletableFuture.supplyAsync(
                () -> preparePlayerSnapshot(directory, detached, write), worker);
        write.prepared.whenComplete((temporary, failure) -> {
            if (write.cancelled && temporary != null) deleteTemporary(temporary);
        });
        playerWrites.computeIfAbsent(owner, ignored -> new ArrayDeque<>()).addLast(write);
        return write.completion;
    }

    /** Called on the server thread, which also owns vanilla player-file replacement. */
    public static void completePlayerWrites() {
        if (playerWrites.isEmpty()) return;
        ArrayList<UUID> players = new ArrayList<>(playerWrites.keySet());
        int start = Math.floorMod(publicationCursor, players.size()), published = 0;
        for (int index = 0; index < players.size(); index++) {
            int selected = (start + index) % players.size();
            if (publishReady(players.get(selected)) && ++published >= PLAYER_PUBLICATIONS_PER_TICK) {
                publicationCursor = selected + 1;
                return;
            }
        }
        publicationCursor = start + 1;
    }

    public static void playerLoggedIn(UUID player) { loggedOutPlayers.remove(player); }

    /** Vanilla has already saved the live player; older detached snapshots must not supersede it. */
    public static void playerSaved(UUID player) { cancelPlayerWrites(player); }

    public static void flushPlayer(UUID player) { flushPlayer(player, LOGOUT_WAIT_MILLIS); }

    static boolean flushPlayer(UUID player, long timeoutMillis) {
        loggedOutPlayers.add(player);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        while (playerWrites.containsKey(player)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                cancelPlayerWrites(player);
                LOGGER.warn(
                        "Cloudworks player save exceeded logout budget for {}; deferring to vanilla save", player);
                return false;
            }
            if (publishReady(player)) continue;
            PlayerWrite write = playerWrites.get(player).peekFirst();
            try {
                write.prepared.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                cancelPlayerWrites(player);
                LOGGER.warn(
                        "Cloudworks player save exceeded logout budget for {}; deferring to vanilla save", player);
                return false;
            } catch (InterruptedException interruption) {
                cancelPlayerWrites(player);
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException preparationFailed) {
                // publishReady reports the failure and releases this entry on the next iteration.
            }
        }
        return true;
    }

    private static CompletableFuture<Void> revokedSave() {
        CompletableFuture<Void> refused = new CompletableFuture<>();
        refused.completeExceptionally(new CancellationException("Player save requested after logout"));
        return refused;
    }

    private static CompletableFuture<Void> failedPlayerSave(String reason, UUID player) {
        IllegalStateException failure = new IllegalStateException(
                "Refusing Cloudworks player save for " + player + ": " + reason);
        LOGGER.error(failure.getMessage(), failure);
        CompletableFuture<Void> refused = new CompletableFuture<>();
        refused.completeExceptionally(failure);
        return refused;
    }

    private static boolean publishReady(UUID player) {
        ArrayDeque<PlayerWrite> pending = playerWrites.get(player);
        if (pending == null || pending.isEmpty()) return false;
        PlayerWrite write = pending.peekFirst();
        if (!write.prepared.isDone()) return false;
        pending.removeFirst();
        if (pending.isEmpty()) playerWrites.remove(player);
        Path temporary = null;
        try {
            temporary = write.prepared.join();
            if (write.cancelled || temporary == null) throw new CancellationException("Player save revoked");
            Files.move(temporary, write.target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            write.completion.complete(null);
        } catch (IOException | RuntimeException failure) {
            write.completion.completeExceptionally(failure);
            LOGGER.error("Cloudworks terminal player save failed for {}", player, failure);
        } finally {
            if (temporary != null) {
                final Path obsolete = temporary;
                if (!write.worker.isShutdown()) write.worker.execute(() -> deleteTemporary(obsolete));
                else deleteTemporary(obsolete);
            }
        }
        return true;
    }

    private static void cancelPlayerWrites(UUID player) {
        ArrayDeque<PlayerWrite> pending = playerWrites.remove(player);
        if (pending == null) return;
        for (PlayerWrite write : pending) {
            write.cancelled = true;
            write.completion.completeExceptionally(new CancellationException("Player save revoked at logout"));
            // A completed preparation may have observed cancelled=false. Its temporary file still needs cleanup.
            if (write.prepared.isDone() && !write.prepared.isCompletedExceptionally()) {
                Path temporary = write.prepared.getNow(null);
                if (temporary != null) write.worker.execute(() -> deleteTemporary(temporary));
            }
        }
    }

    private static Path preparePlayerSnapshot(File directory, NBTTagCompound snapshot, PlayerWrite write) {
        Path temporary = null;
        try {
            if (write.cancelled) return null;
            Files.createDirectories(directory.toPath());
            temporary = Files.createTempFile(directory.toPath(), "cloudworks-player-", ".tmp");
            // Close the compressor before forcing the complete compressed payload.
            try (OutputStream stream = Files.newOutputStream(temporary)) {
                CompressedStreamTools.writeCompressed(snapshot, stream);
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (write.cancelled) return null;
            Path prepared = temporary;
            temporary = null;
            return prepared;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot persist terminal player inventory", failure);
        } finally {
            if (temporary != null) {
                deleteTemporary(temporary);
            }
        }
    }

    private static void deleteTemporary(Path temporary) {
        try { Files.deleteIfExists(temporary); }
        catch (IOException failure) {
            LOGGER.warn("Could not remove obsolete Cloudworks player snapshot {}", temporary, failure);
        }
    }

    private static final class PlayerWrite {
        final Path target;
        final ExecutorService worker;
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        CompletableFuture<Path> prepared;
        volatile boolean cancelled;

        PlayerWrite(Path target, ExecutorService worker) {
            this.target = target;
            this.worker = worker;
        }
    }

    /**
     * Verify the region file itself, not AnvilChunkLoader's in-memory pending map.
     * Its writer catches IO failures, so merely waiting for the queue would be insufficient.
     */
    public static CompletableFuture<Void> confirmChunk(File directory, int chunkX, int chunkZ, NBTTagCompound tile) {
        return confirmChunk(directory, chunkX, chunkZ, Collections.singletonList(tile));
    }

    public static CompletableFuture<Void> confirmChunk(File directory, int chunkX, int chunkZ,
            List<NBTTagCompound> expectedTiles) {
        List<NBTTagCompound> expected = new ArrayList<>();
        for (NBTTagCompound tile : expectedTiles) expected.add(tile.copy());
        return CompletableFuture.runAsync(() -> {
            try {
                ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
                try (DataInputStream stream = RegionFileCache.getChunkInputStream(directory, chunkX, chunkZ)) {
                    if (stream == null) throw new IOException("Missing terminal chunk");
                    NBTTagList tiles = CompressedStreamTools.read(stream)
                            .getCompoundTag("Level").getTagList("TileEntities", 10);
                    Map<String, NBTTagCompound> byPosition = new HashMap<>();
                    for (int i = 0; i < tiles.tagCount(); i++) {
                        NBTTagCompound actual = tiles.getCompoundTagAt(i);
                        byPosition.put(position(actual), actual);
                    }
                    for (NBTTagCompound tile : expected)
                        if (!tile.equals(byPosition.get(position(tile))))
                            throw new IOException("Cloudworks physical checkpoint did not reach disk");
                    Path region = directory.toPath().resolve("region").resolve(
                            "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
                    try (FileChannel channel = FileChannel.open(region, StandardOpenOption.WRITE)) {
                        channel.force(true);
                    }
                    return;
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot confirm terminal chunk persistence", failure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Terminal checkpoint interrupted", interrupted);
            }
        }, executor());
    }

    private static String position(NBTTagCompound tile) {
        return tile.getInteger("x") + "," + tile.getInteger("y") + "," + tile.getInteger("z");
    }
}
