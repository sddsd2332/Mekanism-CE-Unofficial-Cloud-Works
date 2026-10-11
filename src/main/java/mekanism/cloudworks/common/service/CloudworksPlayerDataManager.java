package mekanism.cloudworks.common.service;

import mekanism.cloudworks.common.blueprint.CloudworksBlueprint;
import mekanism.cloudworks.common.blueprint.CloudworksPendingBlueprint;
import mekanism.cloudworks.common.network.CloudworksLineDraftCodec;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;

import mekanism.cloudworks.api.CloudworksUploadReservation;
import mekanism.cloudworks.api.CloudworksUploadResult;
import mekanism.cloudworks.api.ICloudworksUploadSource;
import mekanism.cloudworks.common.data.*;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.api.energy.IStrictEnergyHandler;
import mekanism.common.integration.energy.EnergyCompatUtils;
import mekanism.common.item.ItemBlockEnergyCube;
import mekanism.common.recipe.RecipeHandler;
import mekanism.common.tier.BaseTier;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import mekanism.cloudworks.api.CloudworksGenerationContext;

/**
 * World-scoped records. Unreadable records are never replaced with empty data.
 */
public final class CloudworksPlayerDataManager {
    public static final CloudworksPlayerDataManager INSTANCE = new CloudworksPlayerDataManager();
    public static final int PAGE_SIZE = 32;
    /**
     * Legacy batch bound retained for wire/test compatibility. Virtual execution
     * now captures the complete active line set of one owner in one transaction.
     */
    @Deprecated
    public static final int TRANSFER_LINES_PER_COMMIT = Integer.MAX_VALUE;
    /**
     * Base default capacity; the configuration supplies the actual new-player defaults.
     * Initial stored energy is zero. Persisted records keep their own values.
     */
    /**
     * Ceiling of one machine-cache withdrawal: the withdraw path materialises one
     * ItemStack per max-stack-size batch, so an unbounded request would allocate
     * millions of objects on the server thread. 4096 covers two full inventories.
     */
    public static final int MAX_WITHDRAW_PER_CALL = 4096;
    /**
     * Diagnostic counters for the IO sink; tests only.
     */
    public static final java.util.concurrent.atomic.AtomicLong DEBUG_LINE_READS
            = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DEBUG_INTENT_APPLIES
            = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DEBUG_FILE_WRITES =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DEBUG_BYTES_WRITTEN =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DEBUG_FILE_READS =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DEBUG_PROJECTION_BUILDS =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DEBUG_PROJECTION_HASHES =
            new java.util.concurrent.atomic.AtomicLong();
    private final CloudworksPlayerDataManager parent;
    private final Map<UUID, CloudworksPlayerDataManager> domains = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, CloudworksPlayerDataManager> lineDomains = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, CloudworksPlayerDataManager> terminalDomains = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, UUID> terminalOwners = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, CloudworksData> cloudViews = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean closing;
    private final Map<UUID, TerminalSession> terminalSessions = new HashMap<>();
    private final Map<UUID, CloudworksLineEditSession> activeEdits = new HashMap<>();
    private final Map<UUID, CloudworksPendingBlueprint> pendingBlueprints = new HashMap<>();
    private volatile int globalSimulatedTick;
    private final Map<UUID, CloudworksData> clouds = new LinkedHashMap<UUID, CloudworksData>() {
        @Override public CloudworksData put(UUID owner, CloudworksData cloud) {
            if (parent != null) parent.cloudViews.put(owner, cloud);
            return super.put(owner, cloud);
        }
    };
    private final Map<UUID, NBTTagCompound> guiLines = new HashMap<>(),
            guiMachines = new HashMap<>(), guiRecycle = new HashMap<>();
    private final Set<UUID> runtimeUnmaterialized = new HashSet<>();
    private final Map<UUID, ProductionLineData> lines = new LinkedHashMap<UUID, ProductionLineData>() {
        @Override public ProductionLineData put(UUID id, ProductionLineData line) {
            if (parent != null) parent.lineDomains.put(id, CloudworksPlayerDataManager.this);
            return super.put(id, line);
        }
    };
    /**
     * Owner-level machine caches; the single physical home of every machine.
     */
    private final Map<UUID, mekanism.cloudworks.common.data.CloudworksMachineCache> machineCaches = new LinkedHashMap<>();
    /**
     * Owner-level recycle bins; receiver for deleted node/line resources.
     */
    private final Map<UUID, mekanism.cloudworks.common.data.CloudworksRecycleBin> recycleBins = new LinkedHashMap<>();
    /**
     * Owners with recycle overflow events waiting for a chat+toast notice on the main thread.
     */
    private java.util.Set<UUID> pendingRecycleOverflow = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> revisions = new HashMap<>();
    private final Map<UUID, Long> commandRevisions = new HashMap<>();
    private final Map<UUID, Set<UUID>> runtimeDirtyLines = new LinkedHashMap<>();
    private final Map<UUID, CloudworksWarehouseDelta> runtimeWarehouseDeltas = new LinkedHashMap<>();
    private final Map<UUID, CloudworksData> durableCloudBaselines = new HashMap<>();
    private final Map<UUID, mekanism.cloudworks.common.runtime.CloudworksRuntimeIngressJournal> runtimeIngress =
            new HashMap<>();
    private final Map<UUID, Long> checkpointGenerations = new HashMap<>();
    /** Transfer ids whose intent was fully applied by the IO worker this session. */
    private final java.util.Set<UUID> durableUploadTransfers =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, CloudworksResourceSymbols> symbolTables = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> resourceIdCaches = new HashMap<>();
    private final Map<UUID, Long> sessionGenerations = new HashMap<>();
    /**
     * Identity of this manager instance; snapshots refuse to commit into another instance.
     */
    private final UUID instanceId = UUID.randomUUID();
    /**
     * Capacity of the bounded IO pipeline; tests shrink it to force backpressure.
     */
    private final int ioCapacity;
    /**
     * Upload acknowledgements parked while the IO backlog persists; transferId keyed.
     */
    private final Map<UUID, PendingUploadAck> pendingUploadAcks = new LinkedHashMap<>();
    /**
     * Durable physical-debit ledger keyed by the DEBIT PLAYER (GPT review 5):
     * the player who owes the inventory deduction, NOT the cloud owner — a
     * source can upload player A's items into player B's cloud, and A's login
     * must find the entry. Entries are appended by the IO thread when the
     * intent applies (atomic with the cloud commit) and removed once fully
     * settled; a shortfall REDUCES the entry and keeps it alive for retry at
     * the next login and for audit — never silently dropped. Lock-free map:
     * appended on the IO thread without the manager lock.
     */
    private java.util.concurrent.ConcurrentMap<UUID, List<NBTTagCompound>> sourceDebts =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, List<NBTTagCompound>> pendingSourceDebtWrites = new LinkedHashMap<>();
    /**
     * Owners whose durable batches failed; their operations refuse until reload (isolation). Lock-free set: the IO thread writes without the manager lock.
     */
    private java.util.Set<UUID> failedOwners = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private java.util.concurrent.ConcurrentMap<UUID, Throwable> ownerFailureCauses = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * IO-thread steps of the durable pipeline; no manager lock is taken here.
     */
    private final CloudworksPersistenceQueue.Sink ioSink = new CloudworksPersistenceQueue.Sink() {
        @Override public void beginBatch() { IO_READS.set(new IoReads()); }
        @Override public void commitBatch() {
            forcePendingProjections();
            IoReads cache = IO_READS.get();
            if (cache == null) return;
            for (Runnable acknowledgement : cache.acknowledgements) acknowledgement.run();
            cache.acknowledgements.clear();
        }
        @Override public void endBatch() { IO_READS.remove(); }
        @Override
        public void writeIntentFile(UUID owner, NBTTagCompound intent) {
            if (parent == null) {
                requiredDomain(owner).ioSink.writeIntentFile(owner, intent);
                return;
            }
            if (intent.getInteger("schema") == 3) {
                portJournal.compact(owner);
                portMaintenancePending = false;
                return;
            }
            if (intent.getInteger("schema") == 2) {
                portJournal.prepare(owner, intent);
                portJournal.append(owner, intent);
                intent.setBoolean("portJournalDurable", true);
                return;
            }
            else {
                portJournal.compact(owner);
                prepareProjectionFingerprints(owner, intent);
                packProjectionPatches(owner, intent);
            }
            write(pendingIntentFile(owner), intent);
        }

        @Override
        public void applyIntentRelaxed(UUID owner, NBTTagCompound intent,
                                       CloudworksPersistenceQueue.AbortPoint abort) {
            if (parent == null) {
                requiredDomain(owner).ioSink.applyIntentRelaxed(owner, intent, abort);
                return;
            }
            applyTransferIntent(owner, intent, false, abort);
            if (intent.hasKey("transfer", 10)) {
                UUID transferId = UUID.fromString(intent.getCompoundTag("transfer").getString("transferId"));
                IoReads cache = IO_READS.get();
                if (cache == null) durableUploadTransfers.add(transferId);
                else cache.acknowledgements.add(() -> durableUploadTransfers.add(transferId));
            }
        }

        @Override
        public void writePlainFiles(List<CloudworksPersistenceQueue.FileWrite> files) {
            for (CloudworksPersistenceQueue.FileWrite fileWrite : files) {
                if (fileWrite.monitor == null) write(fileWrite.path, preservePortWatermark(fileWrite.path, fileWrite.tag));
                else synchronized (fileWrite.monitor) {
                    write(fileWrite.path, fileWrite.snapshot.get());
                }
            }
        }

        @Override
        public void noteIoFailure(UUID owner, Throwable failure) {
            // Lock-free: this runs on the IO thread BEFORE the queue wakes any
            // drain waiter, so a caller resuming after drain() already sees the
            // poisoned domain — taking the manager lock here could deadlock
            // against a waiter holding it inside drain().
            if (owner == null) {
                recoveryRequired = true;
                recoveryCause = failure;
                if (parent == null) for (CloudworksPlayerDataManager domain : domains.values()) {
                    domain.recoveryRequired = true;
                    domain.recoveryCause = failure;
                }
            } else {
                // Per-owner fault domain (GPT review): one player's durable
                // failure stops only that player; everyone else keeps ticking.
                failedOwners.add(owner);
                ownerFailureCauses.put(owner, failure);
            }
        }
    };
    /**
     * Cloud→port resume notifications (协议储存箱): a paused upload port registers a
     * watcher for its blocked resource type; the server tick fires it once the
     * receiving warehouse has room for that type again. The manager only invokes
     * registered callbacks — it never touches the world itself.
     */
    private final Map<UUID, Map<mekanism.api.qio.resource.QIOResourceDescriptor, List<Runnable>>> typeUnblockListeners
            = new LinkedHashMap<>();
    /**
     * Debit-recovery shortfalls observed; audit counter, diagnostics only.
     */
    private java.util.concurrent.atomic.AtomicLong debugDebitRecoveryShortfalls =
            new java.util.concurrent.atomic.AtomicLong();
    /**
     * Bounded server-thread drain budget for interactive terminal/source
     * admission. Structural commands run off-thread; physical boundaries may
     * wait briefly for their preceding durable batch.
     */
    private volatile long interactionDrainMillis = 500L;
    /**
     * Increases whenever the world session changes; old-session results are rejected.
     */
    private long worldEpoch;
    private volatile Path root;
    private volatile boolean recoveryRequired;
    /**
     * Root cause of the poison; attached to every subsequent storage error.
     */
    private volatile Throwable recoveryCause;
    /**
     * Single-owner durable pipeline; null while no world session is open.
     */
    private CloudworksPersistenceQueue persistence;
    private CloudworksPortJournal portJournal;
    private UUID storageId;
    private final Map<UUID, CloudworksPortRecord> ports = new HashMap<>();
    private final Map<UUID, Object> portMounts = new HashMap<>();
    private final Set<UUID> dirtyPorts = new HashSet<>();
    private volatile boolean portMaintenancePending;

    public CloudworksPlayerDataManager() {
        this(64);
    }

    /**
     * Reduced IO capacity for backpressure tests; production uses the default.
     */
    public CloudworksPlayerDataManager(int ioCapacity) {
        this.ioCapacity = ioCapacity;
        this.parent = null;
    }

    private CloudworksPlayerDataManager(CloudworksPlayerDataManager parent) {
        this.ioCapacity = parent.ioCapacity;
        this.parent = parent;
        root = parent.root;
        worldEpoch = parent.worldEpoch;
        persistence = parent.persistence;
        portJournal = parent.portJournal;
        storageId = parent.storageId;
        interactionDrainMillis = parent.interactionDrainMillis;
        sourceDebts = parent.sourceDebts;
        failedOwners = parent.failedOwners;
        ownerFailureCauses = parent.ownerFailureCauses;
        pendingRecycleOverflow = parent.pendingRecycleOverflow;
        debugDebitRecoveryShortfalls = parent.debugDebitRecoveryShortfalls;
    }

    private CloudworksPlayerDataManager domain(UUID owner) {
        if (parent != null) return this;
        if (root == null || closing) throw new IllegalStateException("Cloudworks world is not accepting work");
        UUID key = owner == null ? new UUID(0, 0) : owner;
        synchronized (domains) {
            if (root == null || closing) throw new IllegalStateException("Cloudworks world is not accepting work");
            return domains.computeIfAbsent(key, ignored -> new CloudworksPlayerDataManager(this));
        }
    }

    private CloudworksPlayerDataManager requiredDomain(UUID owner) {
        CloudworksPlayerDataManager domain = domains.get(owner);
        if (domain == null) throw new IllegalStateException("Missing Cloudworks owner domain");
        return domain;
    }

    /** Lock only this owner; different owners never share a mutable state monitor. */
    public Object ownerLock(UUID owner) { return domain(owner); }

    /**
     * A line is deletable whenever it is stopped; deletion releases everything.
     */
    public static boolean canDelete(ProductionLineData line) {
        return !line.enabled();
    }

    public static String checkedName(String name) {
        if (name == null || name.trim().isEmpty() || name.length() > 64 || name.chars().anyMatch(c -> c < 32 || c == 127 || c == 167)) {
            throw error("name");
        }
        return name.trim();
    }

    /**
     * The reservation must mirror the requested transaction exactly (§4.2).
     */
    private static boolean reservationCovers(CloudworksUploadReservation reservation, UUID sourceId,
                                             UUID owner, UUID lineId, UUID transferId, ItemStack requested, int count) {
        if (reservation == null || !transferId.equals(reservation.transferId())
                || !owner.equals(reservation.owner()) || !lineId.equals(reservation.lineId())
                || !sourceId.equals(reservation.sourceId()) || reservation.count() != count) return false;
        ItemStack machineStack = reservation.machineStack();
        return machineStack != null && !machineStack.isEmpty()
                && ItemStack.areItemsEqual(machineStack, requested)
                && stripCaps(machineStack).equals(stripCaps(requested));
    }

    /**
     * Acknowledge wrapper (§5.3): returns the outstanding shortfall the source
     * reported (0 = fully settled), or -1 when the acknowledge threw — the
     * durable debit entry must survive both cases (GPT review 5).
     */
    private static long tryAcknowledge(ICloudworksUploadSource source, UUID transferId) {
        try {
            return source.acknowledge(transferId);
        } catch (RuntimeException acknowledgeFailure) {
            // The source keeps its pending reservation; recoverUpload resolves it.
            return -1L;
        }
    }

    private static void tryRollback(ICloudworksUploadSource source, UUID transferId) {
        try {
            source.rollback(transferId);
        } catch (RuntimeException rollbackFailure) {
            // The source keeps its pending reservation; recoverUpload resolves it.
        }
    }

    /**
     * Identity helper for the ledger application; ForgeCaps excluded like everywhere else.
     */
    private static NBTTagCompound stripCapsCopy(NBTTagCompound tag) {
        NBTTagCompound copy = tag.copy();
        copy.removeTag("ForgeCaps");
        return copy;
    }

    private static NBTTagCompound stripCaps(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) return new NBTTagCompound();
        NBTTagCompound copy = tag.copy();
        copy.removeTag("ForgeCaps");
        return copy;
    }

    /**
     * Deposits cached resources into the bin and reports the voided (overflow) amount.
     */
    @SafeVarargs
    private static long recycleIntoBin(mekanism.cloudworks.common.data.CloudworksRecycleBin bin,
                                       List<mekanism.cloudworks.common.data.CloudworksResourceStack>... inventories) {
        long discarded = 0;
        for (List<mekanism.cloudworks.common.data.CloudworksResourceStack> inventory : inventories)
            for (mekanism.cloudworks.common.data.CloudworksResourceStack stack : inventory)
                discarded += bin.deposit(stack.descriptor(), stack.amount());
        return discarded;
    }

    // GLM handoff §4.7: validatedCloud / writeValidatedLines /
    // sameVirtualCloudConfiguration / sameVirtualLineConfiguration /
    // validatedLine deleted — they served only the retired virtual-snapshot
    // commit path.

    private static NBTTagCompound structuralLine(NBTTagCompound source) {
        NBTTagCompound tag = source.copy();
        for (String key : Arrays.asList("generation", "energy", "journals"))
            tag.removeTag(key);
        stripRuntime(tag.getTagList("machines", 10), "active", "progress", "totalTicks",
                "state", "inputs", "outputs", "templates", "operation");
        // Channel buffers are runtime state, but their capacity ceiling is structural.
        NBTTagList channels = tag.getTagList("channels", 10);
        for (int i = 0; i < channels.tagCount(); i++) {
            NBTTagCompound channel = channels.getCompoundTagAt(i);
            NBTTagCompound buffer = channel.getCompoundTag("buffer");
            NBTTagCompound capacityOnly = new NBTTagCompound();
            capacityOnly.setLong("capacity", buffer.getLong("capacity"));
            channel.setTag("buffer", capacityOnly);
        }
        stripRuntime(tag.getTagList("links", 10), "ratioRemainder");
        return tag;
    }

    private static void stripRuntime(NBTTagList entries, String... keys) {
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound entry = entries.getCompoundTagAt(i);
            for (String key : keys) entry.removeTag(key);
        }
    }

    /**
     * Structural pairing rules of an upload intent: exactly one line
     * projection, a parseable machine cache, and the transfer segment must
     * match the node that the line projection adds (same transferId, source
     * and count). This keeps a malformed upload intent from ever becoming
     * durable.
     */
    private static void validateUploadSegments(NBTTagCompound machineCacheTag,
                                               NBTTagCompound transferTag, List<NBTTagCompound> lineTags) {
        if (transferTag == null || lineTags.size() != 1) throw dataError(null);
        mekanism.cloudworks.common.data.CloudworksMachineCache.read(machineCacheTag.copy());
        if (transferTag.getInteger("schema") != 1 || transferTag.getInteger("count") <= 0)
            throw dataError(null);
        UUID transferId = UUID.fromString(transferTag.getString("transferId"));
        UUID sourceId = UUID.fromString(transferTag.getString("sourceId"));
        UUID lineId = UUID.fromString(transferTag.getString("lineId"));
        UUID nodeId = UUID.fromString(transferTag.getString("nodeId"));
        if (new ItemStack(transferTag.getCompoundTag("machineStack")).isEmpty()) throw dataError(null);
        long machineTotal = transferTag.getLong("machineTotal");
        ProductionLineData line = ProductionLineData.read(lineTags.get(0).copy());
        if (!line.lineId().equals(lineId)) throw dataError(null);
        MachineNodeData claimed = null;
        for (MachineNodeData node : line.machines()) if (node.nodeId().equals(nodeId)) claimed = node;
        // Merge intents carry the post-merge total; create intents verify count == machineCount.
        if (claimed == null || !transferId.equals(claimed.transferId()) || !sourceId.equals(claimed.sourceId())
                || (machineTotal > 0 ? claimed.machineCount() != machineTotal
                : claimed.machineCount() != transferTag.getInteger("count"))) throw dataError(null);
    }

    /**
     * One projection is replayable when the disk copy equals the new or the embedded previous state.
     */
    private static boolean matchesProjection(NBTTagCompound disk, NBTTagCompound next, NBTTagCompound previous) {
        return disk.equals(next) || (previous != null && disk.equals(previous));
    }

    private static void deleteTransferIntent(Path file) {
        forcePendingProjections();
        try {
            Files.delete(file);
        } catch (IOException failure) {
            throw dataError(failure);
        }
    }

    private static void requireSchema(NBTTagCompound tag, int supported) {
        if (tag == null || tag.getInteger("schema") != supported) throw dataError(null);
    }

    private static final ThreadLocal<IoReads> IO_READS = new ThreadLocal<>();
    private static final class IoReads {
        final Map<Path, NBTTagCompound> tags = new HashMap<>();
        final Map<Path, byte[]> fingerprints = new HashMap<>();
        final Map<NBTTagCompound, ProjectionPlan> projections = new IdentityHashMap<>();
        final Set<Path> pendingForces = new LinkedHashSet<>();
        final List<Runnable> acknowledgements = new ArrayList<>();
    }

    private static final class ProjectionPlan {
        final CloudworksData cloud;
        final Map<String, NBTTagCompound> next = new LinkedHashMap<>();
        final Map<String, byte[]> fingerprints = new HashMap<>();
        NBTTagList lines;

        ProjectionPlan(CloudworksData cloud) { this.cloud = cloud; }
    }

    private static ProjectionPlan projectionPlan(NBTTagCompound intent) {
        IoReads batch = IO_READS.get();
        return batch == null ? null : batch.projections.get(intent);
    }

    private static NBTTagCompound shallowHeader(NBTTagCompound source) {
        NBTTagCompound result = new NBTTagCompound();
        for (String key : source.getKeySet()) result.setTag(key, source.getTag(key));
        return result;
    }

    /** Keep the intent/journal until every projection in its batch reaches the durability barrier. */
    private static void forcePendingProjections() {
        IoReads cache = IO_READS.get();
        if (cache == null) return;
        for (Path file : cache.pendingForces) {
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    file, java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
            catch (IOException failure) { throw dataError(failure); }
        }
        cache.pendingForces.clear();
    }

    private static NBTTagCompound read(Path file) {
        NBTTagCompound tag = readView(file);
        return IO_READS.get() == null ? tag : tag.copy();
    }

    /** Internal read-only consumers share a batch snapshot; mutable callers use read(). */
    private static NBTTagCompound readView(Path file) {
        IoReads cache = IO_READS.get();
        if (cache != null && cache.tags.containsKey(file)) return cache.tags.get(file);
        try {
            NBTTagCompound tag = CompressedStreamTools.read(file.toFile());
            DEBUG_FILE_READS.incrementAndGet();
            if (tag == null) throw dataError(null);
            if (cache != null) cache.tags.put(file, tag);
            return tag;
        } catch (IOException e) {
            throw dataError(e);
        }
    }

    private static byte[] diskFingerprint(Path file) {
        IoReads cache = IO_READS.get();
        if (cache == null) return CloudworksNbtFingerprint.of(readView(file));
        return cache.fingerprints.computeIfAbsent(file, ignored -> CloudworksNbtFingerprint.of(readView(file)));
    }

    private static NBTTagCompound preservePortWatermark(Path file, NBTTagCompound next) {
        if (!Files.exists(file)) return next;
        NBTTagCompound previous = readView(file);
        if (!previous.hasKey("cwPortSequence", 4)) return next;
        long watermark = Math.max(previous.getLong("cwPortSequence"), next.getLong("cwPortSequence"));
        if (next.hasKey("cwPortSequence", 4) && next.getLong("cwPortSequence") == watermark) return next;
        NBTTagCompound result = shallowHeader(next);
        result.setLong("cwPortSequence", watermark);
        return result;
    }

    private static void write(Path file, NBTTagCompound tag) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "cloudworks-", ".tmp");
            CompressedStreamTools.write(tag, temporary.toFile());
            long writtenBytes = Files.size(temporary);
            IoReads batch = IO_READS.get();
            boolean immediate = batch == null || file.getFileName().toString().endsWith(".pending.dat")
                    || file.getParent().getFileName().toString().equals("port-journal");
            if (immediate) {
                try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                        temporary, java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
            }
            // Do not fall back to truncating the live file if atomic replacement is unavailable.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (!immediate) batch.pendingForces.add(file);
            DEBUG_FILE_WRITES.incrementAndGet();
            DEBUG_BYTES_WRITTEN.addAndGet(writtenBytes);
            IoReads cache = IO_READS.get();
            if (cache != null) {
                cache.tags.put(file, tag.copy());
                cache.fingerprints.remove(file);
            }
        } catch (IOException e) {
            throw dataError(e);
        } finally {
            if (temporary != null) try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    private static IllegalStateException error(String key) {
        return new IllegalStateException("cloudworks.error." + key);
    }

    private static IllegalStateException dataError(Throwable cause) {
        return new IllegalStateException("cloudworks.error.storage", cause);
    }

    public void register() {
    }

    public synchronized void load(File worldDirectory) {
        if (parent != null) throw new IllegalStateException("Only the world manager may load a save");
        Path next = worldDirectory.toPath().toAbsolutePath().normalize().resolve("cloudworks");
        if (next.equals(root)) return;
        if (root != null) throw new IllegalStateException("Cloudworks world must be closed before switching saves");
        try {
            Files.createDirectories(next);
        } catch (IOException e) {
            throw dataError(e);
        }
        closing = false;
        domains.clear();
        lineDomains.clear();
        terminalDomains.clear();
        terminalOwners.clear();
        cloudViews.clear();
        globalSimulatedTick = 0;
        worldEpoch++;
        terminalSessions.clear();
        activeEdits.clear();
        pendingBlueprints.clear();
        clouds.clear();
        clearGuiSources();
        lines.clear();
        revisions.clear();
        commandRevisions.clear();
        runtimeDirtyLines.clear();
        runtimeWarehouseDeltas.clear();
        durableCloudBaselines.clear();
        runtimeIngress.clear();
        checkpointGenerations.clear();
        ports.clear();
        portMounts.clear();
        dirtyPorts.clear();
        typeUnblockListeners.clear();
        symbolTables.clear();
        resourceIdCaches.clear();
        sessionGenerations.clear();
        machineCaches.clear();
        recycleBins.clear();
        pendingRecycleOverflow.clear();
        pendingUploadAcks.clear();
        durableUploadTransfers.clear();
        sourceDebts.clear();
        pendingSourceDebtWrites.clear();
        failedOwners.clear();
        ownerFailureCauses.clear();
        recoveryRequired = false;
        recoveryCause = null;
        Path identity = next.resolve("storage-id.dat");
        if (Files.exists(identity)) storageId = UUID.fromString(read(identity).getString("id"));
        else {
            storageId = UUID.randomUUID();
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("id", storageId.toString());
            write(identity, tag);
        }
        portJournal = new CloudworksPortJournal(next, new CloudworksPortJournal.Storage() {
            @Override public NBTTagCompound read(Path file) { return CloudworksPlayerDataManager.read(file); }
            @Override public void write(Path file, NBTTagCompound tag) { CloudworksPlayerDataManager.write(file, tag); }
            @Override public void flush() { forcePendingProjections(); }
        });
        persistence = new CloudworksPersistenceQueue(ioCapacity, ioSink, "Cloudworks-IO");
        root = next;
    }

    public synchronized void close() {
        long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        boolean interrupted = false;
        if (parent == null) {
            synchronized (domains) { closing = true; }
            for (CloudworksPlayerDataManager domain : domains.values()) {
                while (persistence != null && !persistence.dead() && !domain.pendingSourceDebtWrites.isEmpty()) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) {
                        System.err.println("Cloudworks shutdown deadline exceeded while flushing source debt");
                        break;
                    }
                    synchronized (domain) { domain.flushSourceDebtWrites(); }
                    try { persistence.drain(Math.max(1, Math.min(30_000,
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)))); }
                    catch (InterruptedException interruption) { interrupted = true; }
                }
            }
            boolean dead = false;
            if (persistence != null) {
                persistence.close(deadlineNanos);
                dead = persistence.dead();
                persistence = null;
            }
            for (CloudworksPlayerDataManager domain : domains.values()) {
                domain.persistence = null;
                domain.recoveryRequired |= dead;
                synchronized (domain) { domain.close(); }
            }
            recoveryRequired |= dead;
        }
        boolean crashed = persistence != null && persistence.dead();
        if (persistence != null) {
            while (!persistence.dead() && !pendingSourceDebtWrites.isEmpty()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    System.err.println("Cloudworks shutdown deadline exceeded while flushing source debt");
                    break;
                }
                flushSourceDebtWrites();
                try { persistence.drain(Math.max(1, Math.min(30_000,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)))); }
                catch (InterruptedException interruption) { interrupted = true; }
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining > 0) try {
                persistence.drain(Math.max(1, Math.min(30_000,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
            persistence.close(deadlineNanos);
            crashed |= persistence.dead();
            persistence = null;
        }
        // Finalize parked upload acknowledgements with the disk truth now that
        // the queue has settled; anything unresolved stays with its source and
        // recovers through recoverUpload after the next load.
        if (!pendingUploadAcks.isEmpty()) {
            Iterator<Map.Entry<UUID, PendingUploadAck>> iterator = pendingUploadAcks.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, PendingUploadAck> entry = iterator.next();
                boolean durable = durableUploadTransfers.remove(entry.getKey());
                if (!durable) {
                    try {
                        durable = root != null && uploadDurable(entry.getValue().owner, entry.getKey());
                    } catch (RuntimeException probeFailure) {
                        durable = false;
                    }
                }
                if (durable) {
                    settleSourceDebt(entry.getValue().source.debitPlayer(), entry.getKey(),
                            tryAcknowledge(entry.getValue().source, entry.getKey()));
                } else tryRollback(entry.getValue().source, entry.getKey());
                iterator.remove();
            }
        }
        // A crashed pipeline keeps its file residue as evidence: never overwrite.
        // Healthy owners flush; failed domains keep their residue untouched.
        if (!recoveryRequired && !crashed) {
            for (CloudworksLineEditSession edit : new ArrayList<>(activeEdits.values())) abortLineEdit(edit);
            flushInternal(true);
            // A healthy close must not leave an intent file behind when the
            // queue was drained concurrently with the final flush.
            for (UUID owner : new ArrayList<>(clouds.keySet())) {
                if (failedOwners.contains(owner)) continue;
                replayPendingTransfer(owner);
                if (!failedOwners.contains(owner) && Files.exists(pendingIntentFile(owner)))
                    deleteTransferIntent(pendingIntentFile(owner));
            }
            if (parent == null && root != null && Files.isDirectory(root)) {
                try (DirectoryStream<Path> owners = Files.newDirectoryStream(root)) {
                    for (Path ownerDirectory : owners) {
                        if (!Files.isDirectory(ownerDirectory)) continue;
                        try {
                            UUID owner = UUID.fromString(ownerDirectory.getFileName().toString());
                            if (clouds.containsKey(owner) || failedOwners.contains(owner)) continue;
                            replayPendingTransfer(owner);
                            if (!failedOwners.contains(owner) && Files.exists(pendingIntentFile(owner)))
                                deleteTransferIntent(pendingIntentFile(owner));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                } catch (IOException failure) {
                    throw dataError(failure);
                }
            }
        }
        terminalSessions.clear();
        activeEdits.clear();
        pendingBlueprints.clear();
        clouds.clear();
        clearGuiSources();
        lines.clear();
        revisions.clear();
        commandRevisions.clear();
        runtimeDirtyLines.clear();
        runtimeWarehouseDeltas.clear();
        durableCloudBaselines.clear();
        runtimeIngress.clear();
        checkpointGenerations.clear();
        ports.clear();
        portMounts.clear();
        dirtyPorts.clear();
        typeUnblockListeners.clear();
        symbolTables.clear();
        resourceIdCaches.clear();
        sessionGenerations.clear();
        machineCaches.clear();
        recycleBins.clear();
        if (parent == null) pendingRecycleOverflow.clear();
        pendingUploadAcks.clear();
        durableUploadTransfers.clear();
        if (parent == null) sourceDebts.clear();
        pendingSourceDebtWrites.clear();
        if (parent == null) {
            failedOwners.clear();
            ownerFailureCauses.clear();
            domains.clear();
            lineDomains.clear();
            terminalDomains.clear();
            terminalOwners.clear();
            cloudViews.clear();
        }
        globalSimulatedTick = 0;
        root = null;
        if (interrupted) Thread.currentThread().interrupt();
        portJournal = null;
        worldEpoch++;
        recoveryRequired = false;
        recoveryCause = null;
    }

    /**
     * True while durable batches are outstanding; used to pause new captures.
     */
    public boolean persistenceBusy() {
        CloudworksPersistenceQueue queue = persistence;
        return queue != null && queue.backlogged();
    }

    /**
     * Outstanding durable batches including the applying one; backpressure input.
     */
    public int persistenceBacklogDepth() {
        CloudworksPersistenceQueue queue = persistence;
        return queue == null ? 0 : queue.backlogDepth();
    }

    /**
     * IO pipeline capacity; the capture backpressure depth derives from it.
     */
    public int persistenceIoCapacity() {
        return ioCapacity;
    }

    /**
     * Waits until every submitted batch is durable; interactive entries use this.
     */
    public void drainPersistence() {
        if (!awaitPersistence(10_000)) throw dataError(null);
    }

    public boolean awaitPersistence(long timeoutMillis) {
        if (timeoutMillis < 0) throw new IllegalArgumentException("Negative persistence wait");
        if (parent == null) {
            for (CloudworksPlayerDataManager domain : domains.values())
                synchronized (domain) { domain.flushSourceDebtWrites(); }
        } else synchronized (this) { flushSourceDebtWrites(); }
        CloudworksPersistenceQueue queue = persistence;
        if (queue == null) return true;
        try {
            return queue.drain(timeoutMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw dataError(interrupted);
        }
    }

    /**
     * Bounded drain for server-thread entries (GPT review: no 10s stalls).
     * Returns true when the queue went idle; false means alive-but-backlogged
     * and the caller must degrade (queued write or parked acknowledgement).
     * A dead pipeline still throws — that is a storage failure, not a stall.
     */
    private boolean drainInteraction() {
        CloudworksPersistenceQueue queue = persistence;
        if (queue == null) return true;
        try {
            boolean drained = queue.drain(interactionDrainMillis);
            if (!drained && queue.dead()) throw dataError(queue.lastIoFailure());
            return drained;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw dataError(interrupted);
        }
    }

    /**
     * Interactive ordered write: bounded drain first; while the backlog
     * persists the write is queued behind it instead of stalling the caller.
     * FIFO submission order under the manager lock keeps every file
     * monotonically fresh, so a queued write can never regress disk state.
     * The owner parameter makes a durable failure attributable to one domain.
     */
    private void orderedWrite(UUID owner, Path file, NBTTagCompound tag) {
        if (persistence == null) write(file, preservePortWatermark(file, tag));
        else persistence.submitFiles(owner, new CloudworksPersistenceQueue.FileWrite(file, tag));
    }

    /**
     * Arms the next durable batch to stop at the given step; fault-injection tests only.
     */
    public void debugArmIoAbort(CloudworksPersistenceQueue.AbortPoint point) {
        CloudworksPersistenceQueue queue = persistence;
        if (queue == null) throw new IllegalStateException("no world session");
        queue.debugArmAbort(point);
    }

    /**
     * Arms the next applied batch to fail with a plain RuntimeException; fault-injection tests only.
     */
    public void debugFailNextIoApply() {
        CloudworksPersistenceQueue queue = persistence;
        if (queue == null) throw new IllegalStateException("no world session");
        queue.debugFailNextApply();
    }

    /**
     * One-shot pause of the IO worker's next poll; backlog tests only.
     */
    public void debugPauseIoWorker(long millis) {
        CloudworksPersistenceQueue queue = persistence;
        if (queue == null) throw new IllegalStateException("no world session");
        queue.debugPauseWorker(millis);
    }

    /**
     * Full-queue submit refusals so far; diagnostics only.
     */
    public long debugBusyRefusals() {
        CloudworksPersistenceQueue queue = persistence;
        return queue == null ? 0 : queue.busyRefusals();
    }

    /**
     * Batches of failed owners dropped by the IO worker; diagnostics only.
     */
    public long debugDroppedBatches() {
        CloudworksPersistenceQueue queue = persistence;
        return queue == null ? 0 : queue.droppedBatches();
    }

    /** Test hook for the bounded interactive drain budget. */
    public void debugInteractionDrainMillis(long millis) {
        interactionDrainMillis = Math.max(1L, millis);
        if (parent == null) for (CloudworksPlayerDataManager domain : domains.values())
            domain.interactionDrainMillis = interactionDrainMillis;
    }

    /**
     * The failure that poisoned the IO pipeline, if any; kept as evidence.
     */
    public Throwable lastIoFailure() {
        CloudworksPersistenceQueue queue = persistence;
        return queue == null ? null : queue.lastIoFailure();
    }

    /**
     * Diagnostic backlog components; tests only.
     */
    public String debugBacklogComponents() {
        CloudworksPersistenceQueue queue = persistence;
        return queue == null ? "no-queue" : queue.debugBacklogComponents();
    }

    public CloudworksData getOrCreate(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.getOrCreate(owner);
        }
        synchronized (this) {
            Objects.requireNonNull(owner);
            requireLoaded();
            if (recoveryRequired || failedOwners.contains(owner))
                throw dataError(ownerFailureCauses.getOrDefault(owner, recoveryCause));
            CloudworksData cached = clouds.get(owner);
            if (cached != null) return cached;
            replayPendingTransfer(owner);
            try {
                portJournal.compact(owner);
            } catch (RuntimeException failure) {
                failedOwners.add(owner);
                ownerFailureCauses.put(owner, failure);
                throw dataError(failure);
            }
            Path file = cloudFile(owner);
            CloudworksData cloud;
            if (Files.exists(file)) {
                NBTTagCompound tag = read(file);
                requireSchema(tag, CloudworksData.SCHEMA_VERSION);
                cloud = CloudworksData.read(tag);
                if (!owner.equals(cloud.owner()) || new HashSet<>(cloud.lineIds()).size() != cloud.lineIds().size()) {
                    throw dataError(null);
                }
            } else {
                cloud = CloudworksData.create(owner);
            }
            // Legacy inline staging inventories are ignored; the split warehouse files
            // are authoritative, and a missing file simply means an empty start.
            cloud.resetStagingInventories();
            applyWarehouse(receivingFile(owner), cloud::applyReceiving);
            applyWarehouse(transferStoreFile(owner), cloud::applyTransfer);
            applyWarehouse(sendingFile(owner), cloud::applySending);
            for (UUID id : cloud.lineIds()) {
                try {
                    NBTTagCompound tag = read(lineFile(owner, id));
                    requireSchema(tag, ProductionLineData.SCHEMA_VERSION);
                    long generation = tag.getLong("generation");
                    ProductionLineData line = ProductionLineData.read(tag.copy());
                    if (owner.equals(line.owner()) && id.equals(line.lineId())) {
                        CloudworksTopologyValidator.Result topology = CloudworksTopologyValidator.validate(line);
                        if (!topology.valid())
                            throw new IllegalArgumentException("Invalid persisted topology: " + topology.reason());
                        lines.put(id, line);
                        guiLines.put(id, line.write());
                        checkpointGenerations.put(id, generation);
                    }
                } catch (RuntimeException damaged) {
                    // Keep the index and original bytes. The list shows this line as unavailable.
                }
            }
            cleanupUnindexedLineFiles(owner, cloud);
            clouds.put(owner, cloud);
            durableCloudBaselines.put(owner, cloud.copyForRuntime());
            revisions.put(owner, 0L);
            commandRevisions.put(owner, 0L);
            loadMachineCache(owner);
            pendingBlueprints.remove(owner);
            Path blueprintFile = blueprintPendingFile(owner);
            if (Files.exists(blueprintFile)) {
                try {
                    CloudworksPendingBlueprint pendingBlueprint = CloudworksPendingBlueprint.read(read(blueprintFile));
                    if (pendingBlueprint != null && !cloud.lineIds().contains(pendingBlueprint.line))
                        throw CloudworksBlueprint.invalid();
                    if (pendingBlueprint != null) pendingBlueprints.put(owner, pendingBlueprint);
                } catch (RuntimeException failure) {
                    if (mekanism.common.Mekanism.logger != null)
                        mekanism.common.Mekanism.logger.warn("Rejected Cloudworks pending blueprint {}", blueprintFile, failure);
                    try {
                        Files.deleteIfExists(blueprintFile);
                    } catch (IOException deleteFailure) {
                        failure.addSuppressed(deleteFailure);
                        throw dataError(failure);
                    }
                }
            }
            if (!pendingBlueprints.containsKey(owner)) discardOrphanWaitingNodes(owner);
            // NOTE: the debit ledger is keyed by DEBIT PLAYER and loaded on demand
            // (append/remove/apply) — deliberately NOT here, so its recovery never
            // depends on the cloud record being enabled (GPT review 5).
            loadRecycleBin(owner);
            backfillMachineCache(owner);
            guiMachines.put(owner, machineCache(owner).write());
            guiRecycle.put(owner, recycleBin(owner).write());
            return cloud;
        }
    }

    private int discardWaitingNodes(ProductionLineData line) {
        Set<UUID> waiting = new HashSet<>();
        line.machines().removeIf(machine -> {
            if (machine.waitingBlueprint() == null) return false;
            waiting.add(machine.nodeId());
            return true;
        });
        line.channels().removeIf(channel -> {
            if (channel.waitingBlueprint() == null) return false;
            waiting.add(channel.id());
            return true;
        });
        if (!waiting.isEmpty()) line.links().removeIf(link ->
                waiting.contains(link.sourceNode()) || waiting.contains(link.targetNode()));
        return waiting.size();
    }

    private void discardOrphanWaitingNodes(UUID owner) {
        if (pendingBlueprints.containsKey(owner)) return;
        int removed = 0;
        for (ProductionLineData line : new ArrayList<>(lines.values())) {
            if (!owner.equals(line.owner())) continue;
            NBTTagCompound previous = line.write();
            int count = discardWaitingNodes(line);
            if (count == 0) continue;
            try {
                writeCheckpoint(line);
                removed += count;
            } catch (RuntimeException failure) {
                lines.put(line.lineId(), ProductionLineData.read(previous));
                throw failure;
            }
        }
        if (removed > 0 && mekanism.common.Mekanism.logger != null)
            mekanism.common.Mekanism.logger.warn("Removed {} orphan Cloudworks blueprint nodes for owner {}", removed, owner);
    }

    private void discardLegacyPendingFromIntent(NBTTagCompound intent) {
        if (!intent.hasKey("blueprintPending", 10)) return;
        try {
            CloudworksPendingBlueprint.read(intent.getCompoundTag("blueprintPending"));
        } catch (CloudworksLineDraftCodec.UnsupportedBlueprintVersionException legacy) {
            NBTTagList entries = intent.getTagList("lines", 10);
            NBTTagList cleaned = new NBTTagList();
            int removed = 0;
            for (int index = 0; index < entries.tagCount(); index++) {
                NBTTagCompound entry = entries.getCompoundTagAt(index);
                ProductionLineData line = ProductionLineData.read(entry.copy());
                int count = discardWaitingNodes(line);
                removed += count;
                NBTTagCompound next = count == 0 ? entry.copy() : line.write();
                if (entry.hasKey("generation", 4)) next.setLong("generation", entry.getLong("generation"));
                cleaned.appendTag(next);
            }
            intent.setTag("lines", cleaned);
            NBTTagCompound emptyPending = CloudworksPendingBlueprint.empty();
            intent.setTag("blueprintPending", emptyPending);
            ProjectionPlan plan = projectionPlan(intent);
            if (plan != null) {
                plan.next.put("blueprintPending", emptyPending);
                plan.fingerprints.remove("blueprintPending");
                plan.lines = cleaned;
            }
            if (intent.hasKey("nextFingerprints", 10))
                intent.getCompoundTag("nextFingerprints").setByteArray("blueprintPending",
                        projectionFingerprint(intent, "blueprintPending", emptyPending));
            if (mekanism.common.Mekanism.logger != null)
                mekanism.common.Mekanism.logger.warn(
                        "Discarded legacy Cloudworks pending blueprint from recovery intent; removed {} waiting nodes", removed);
        }
    }

    private void cleanupUnindexedLineFiles(UUID owner, CloudworksData cloud) {
        Path production = root.resolve(owner.toString()).resolve("production");
        if (!Files.isDirectory(production)) return;
        Set<UUID> indexed = new HashSet<>(cloud.lineIds());
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(production, "*.dat")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                boolean runtime = name.endsWith(".runtime.dat");
                String uuidText = name.substring(0, name.length() - (runtime ? 12 : 4));
                UUID id;
                try { id = UUID.fromString(uuidText); }
                catch (IllegalArgumentException ignored) { continue; }
                if (indexed.contains(id)) continue;
                if (Files.deleteIfExists(file)) removed++;
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException failure) {
            throw dataError(failure);
        }
        if (removed > 0 && mekanism.common.Mekanism.logger != null)
            mekanism.common.Mekanism.logger.warn("Removed {} unindexed Cloudworks production files for owner {}",
                    removed, owner);
    }

    public UUID storageId() {
        if (root == null || closing) throw error("busy");
        return storageId;
    }

    public boolean portMounted(UUID owner, UUID id, Object holder) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domains.get(owner);
            return target != null && target.portMounted(owner, id, holder);
        }
        synchronized (this) { return root != null && portMounts.get(id) == holder; }
    }

    public CloudworksPortRecord mountPort(UUID owner, UUID id, boolean input, Object holder, boolean fresh) {
        if (parent == null) return domain(owner).mountPort(owner, id, input, holder, fresh);
        synchronized (this) {
            getOrCreate(owner);
            Objects.requireNonNull(holder);
            Objects.requireNonNull(id);
            if (portMounts.containsKey(id) && portMounts.get(id) != holder) return null;
            CloudworksPortRecord record = ports.get(id);
            if (record == null) {
                Path file = root.resolve(owner.toString()).resolve("ports").resolve(id + ".dat");
                if (Files.exists(file)) record = CloudworksPortRecord.read(read(file));
                else if (fresh) {
                    record = CloudworksPortRecord.empty(id, owner, input);
                    dirtyPorts.add(id);
                }
                else throw dataError(null);
                ports.put(id, record);
            }
            if (!record.id.equals(id) || !record.owner.equals(owner) || record.input != input) throw dataError(null);
            portMounts.put(id, holder);
            return record;
        }
    }

    public void unmountPort(UUID owner, UUID id, Object holder) {
        if (parent == null) { domain(owner).unmountPort(owner, id, holder); return; }
        synchronized (this) { portMounts.remove(id, holder); }
    }

    public void savePort(UUID owner, UUID id, Object holder, CloudworksPortRecord record) {
        if (parent == null) { domain(owner).savePort(owner, id, holder, record); return; }
        synchronized (this) {
            if (!portMounted(owner, id, holder) || !record.id.equals(id)
                    || !record.owner.equals(owner) || record.input != ports.get(id).input) throw error("busy");
            NBTTagCompound operation = new NBTTagCompound();
            operation.setTag("port", record.write());
            ports.put(id, record);
            dirtyPorts.add(id);
            try {
                publishPort(owner, operation);
                dirtyPorts.remove(id);
            } catch (IllegalStateException failure) {
                if (!"cloudworks.error.busy".equals(failure.getMessage())) throw failure;
            }
        }
    }

    public void flushMountedPorts() {
        if (parent == null) {
            for (CloudworksPlayerDataManager target : domains.values()) target.flushMountedPorts();
            return;
        }
        synchronized (this) {
            for (Object holder : new ArrayList<>(portMounts.values()))
                if (holder instanceof mekanism.cloudworks.common.content.CloudworksPortBinding binding) binding.flush();
        }
    }

    public void capturePort(UUID owner, UUID id, Object holder, CloudworksPortRecord record) {
        if (parent == null) { domain(owner).capturePort(owner, id, holder, record); return; }
        synchronized (this) {
            if (!portMounted(owner, id, holder) || !record.id.equals(id) || !record.owner.equals(owner))
                throw error("busy");
            ports.put(id, record);
            dirtyPorts.add(id);
        }
    }

    public void maintainPortRecords(long tick) {
        if (parent == null) {
            for (Map.Entry<UUID, CloudworksPlayerDataManager> entry : domains.entrySet())
                if (Math.floorMod(tick, 200) == Math.floorMod(entry.getKey().hashCode(), 200))
                    entry.getValue().maintainPortRecords(tick);
            return;
        }
        synchronized (this) {
            if (ports.isEmpty() || portMaintenancePending || root == null) return;
            UUID owner = ports.values().iterator().next().owner;
            for (UUID id : new ArrayList<>(dirtyPorts)) {
                NBTTagCompound operation = new NBTTagCompound();
                operation.setTag("port", ports.get(id).write());
                try { publishPort(owner, operation); dirtyPorts.remove(id); }
                catch (RuntimeException busy) { return; }
            }
            NBTTagCompound maintenance = new NBTTagCompound();
            maintenance.setInteger("schema", 3);
            maintenance.setString("owner", owner.toString());
            try {
                portMaintenancePending = true;
                persistence.submitIntent(owner, maintenance);
            } catch (RuntimeException busy) { portMaintenancePending = false; }
        }
    }

    public int transferPort(UUID owner, UUID id, Object holder, List<mekanism.api.inventory.IInventorySlot> slots,
            List<mekanism.cloudworks.common.content.CloudworksHybridTank> tanks, double energy,
            java.util.function.DoubleConsumer setEnergy, boolean[] grace) {
        return transferPort(owner, id, holder, slots, tanks, energy,
                mekanism.cloudworks.common.tile.prefab.TileEntityCloudworksPort.MAX_ENERGY, setEnergy, grace);
    }

    public int transferPort(UUID owner, UUID id, Object holder, List<mekanism.api.inventory.IInventorySlot> slots,
            List<mekanism.cloudworks.common.content.CloudworksHybridTank> tanks, double energy, double maxEnergy,
            java.util.function.DoubleConsumer setEnergy, boolean[] grace) {
        if (!Double.isFinite(maxEnergy) || maxEnergy < 0) throw new IllegalArgumentException("Invalid port capacity");
        if (parent == null) return domain(owner).transferPort(owner, id, holder, slots, tanks, energy, maxEnergy, setEnergy, grace);
        synchronized (this) {
            if (!portMounted(owner, id, holder)) throw error("busy");
            CloudworksData cloud = requireEnabled(owner);
            flushRuntimePending(owner);
            CloudworksPortRecord before = CloudworksPortRecord.capture(id, owner, ports.get(id).input, slots, tanks, energy);
            Map<QIOResourceDescriptor, Long> moved = new LinkedHashMap<>();
            Map<QIOResourceDescriptor, Integer> resourceIds = new LinkedHashMap<>();
            NBTTagList operations = new NBTTagList();
            double previousEnergy = before.input ? cloud.receivingEnergy() : cloud.sendingEnergy();
            double nextPortEnergy = energy, nextWarehouseEnergy = previousEnergy;
            CloudworksPortRecord after;
            int blocked = -1;
            try {
                if (before.input) {
                    mekanism.cloudworks.common.content.CloudworksPortIo.UploadPlan plan =
                            mekanism.cloudworks.common.content.CloudworksPortIo.planUpload(slots, tanks,
                                    descriptor -> cloud.receiving().insertable(descriptor, Long.MAX_VALUE), grace,
                                    cloud.receiving().room());
                    if (plan.pauses()) {
                        savePort(owner, id, holder, before);
                        return plan.pauseIndex;
                    }
                    for (Map.Entry<QIOResourceDescriptor, Long> entry : plan.fitting.entrySet()) {
                        resourceIds.put(entry.getKey(), resourceId(owner, entry.getKey()));
                        cloud.receiving().insert(entry.getKey(), entry.getValue());
                        moved.put(entry.getKey(), entry.getValue());
                    }
                    mekanism.cloudworks.common.content.CloudworksPortIo.applyDrains(slots, tanks, plan.takes);
                    double accepted = Math.min(energy, Math.max(0D, cloud.receivingEnergyCapacity() - previousEnergy));
                    nextPortEnergy -= accepted;
                    nextWarehouseEnergy += accepted;
                } else {
                    Map<QIOResourceDescriptor, Long> available = cloud.sending().snapshot();
                    Map<QIOResourceDescriptor, Long> request =
                            mekanism.cloudworks.common.content.CloudworksPortIo.buildRequest(available, slots, tanks);
                    for (Map.Entry<QIOResourceDescriptor, Long> entry : request.entrySet()) {
                        resourceIds.put(entry.getKey(), resourceId(owner, entry.getKey()));
                        long amount = cloud.sending().extract(entry.getKey(), entry.getValue());
                        moved.put(entry.getKey(), amount);
                        mekanism.cloudworks.common.content.CloudworksPortIo.insertPlanned(slots, tanks, entry.getKey(), amount);
                    }
                    double taken = Math.min(previousEnergy, Math.max(0D,
                            maxEnergy - energy));
                    nextPortEnergy += taken;
                    nextWarehouseEnergy -= taken;
                    boolean supported = available.keySet().stream().anyMatch(
                            mekanism.cloudworks.common.content.CloudworksPortIo::isSupportedDownloadResource);
                    if (request.isEmpty() && supported || taken == 0 && previousEnergy > 0) blocked = 0;
                }
                String warehouse = before.input ? "receiving" : "sending";
                CloudworksInventory inventory = before.input ? cloud.receiving() : cloud.sending();
                for (QIOResourceDescriptor descriptor : moved.keySet()) {
                    NBTTagCompound operation = new NBTTagCompound();
                    operation.setString("warehouse", warehouse);
                    operation.setTag("descriptor", descriptor.write());
                    operation.setLong("amount", inventory.amount(descriptor));
                    operations.appendTag(operation);
                }
                if (nextWarehouseEnergy != previousEnergy) {
                    NBTTagCompound operation = new NBTTagCompound();
                    operation.setString("energyKey", before.input ? "receivingEnergy" : "sendingEnergy");
                    operation.setDouble("energy", nextWarehouseEnergy);
                    operations.appendTag(operation);
                }
                cloud.setWarehouseEnergy(before.input ? nextWarehouseEnergy : cloud.receivingEnergy(),
                        before.input ? cloud.sendingEnergy() : nextWarehouseEnergy);
                setEnergy.accept(nextPortEnergy);
                after = CloudworksPortRecord.capture(id, owner, before.input, slots, tanks, nextPortEnergy);
                NBTTagCompound port = new NBTTagCompound();
                port.setTag("port", after.write());
                operations.appendTag(port);
                NBTTagCompound intent = new NBTTagCompound();
                intent.setInteger("schema", 2);
                intent.setString("owner", owner.toString());
                intent.setTag("operations", operations);
                // Preflight every ingress revision before admitting an irrevocable durable batch.
                Math.addExact(revisions.get(owner), moved.size() + (nextWarehouseEnergy != previousEnergy ? 1L : 0L));
                persistence.submitIntent(owner, intent);
            } catch (RuntimeException failure) {
                for (Map.Entry<QIOResourceDescriptor, Long> entry : moved.entrySet()) {
                    if (before.input) cloud.receiving().extract(entry.getKey(), entry.getValue());
                    else cloud.sending().insertIgnoringTypeCap(entry.getKey(), entry.getValue());
                }
                cloud.setWarehouseEnergy(before.input ? previousEnergy : cloud.receivingEnergy(),
                        before.input ? cloud.sendingEnergy() : previousEnergy);
                before.restore(slots, tanks);
                setEnergy.accept(energy);
                throw failure;
            }
            ports.put(id, after);
            dirtyPorts.remove(id);
            for (Map.Entry<QIOResourceDescriptor, Long> entry : moved.entrySet()) {
                changedFromRuntime(owner);
                recordRuntimeIngress(owner, before.input
                        ? mekanism.cloudworks.common.runtime.CloudworksExternalEvent.Kind.RECEIVING_RESOURCE
                        : mekanism.cloudworks.common.runtime.CloudworksExternalEvent.Kind.SENDING_RESOURCE,
                        resourceIds.get(entry.getKey()), entry.getValue(), 0);
            }
            if (nextWarehouseEnergy != previousEnergy) {
                changedFromRuntime(owner);
                recordRuntimeIngress(owner, before.input
                        ? mekanism.cloudworks.common.runtime.CloudworksExternalEvent.Kind.RECEIVING_ENERGY
                        : mekanism.cloudworks.common.runtime.CloudworksExternalEvent.Kind.SENDING_ENERGY,
                        -1, 0, Math.abs(nextWarehouseEnergy - previousEnergy));
            }
            durableCloudBaselines.put(owner, cloud.copyForRuntime());
            return blocked;
        }
    }

    private void clearGuiSources() {
        runtimeUnmaterialized.clear();
        guiLines.clear();
        guiMachines.clear();
        guiRecycle.clear();
    }

    /** Only copies the small line index. Frozen payloads are decoded after releasing the manager lock. */
    public CloudworksGuiSource guiSource(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.guiSource(owner);
        }
        synchronized (this) {
            CloudworksData cloud = requireEnabled(owner);
            Map<UUID, NBTTagCompound> tags = new LinkedHashMap<>();
            for (UUID id : cloud.lineIds()) {
                NBTTagCompound tag = guiLines.get(id);
                if (tag != null) tags.put(id, tag);
            }
            return new CloudworksGuiSource(cloud, tags, guiMachines.get(owner),
                    guiRecycle.get(owner), commandRevisions.get(owner),
                    parent == null ? globalSimulatedTick : parent.globalSimulatedTick);
        }
    }

    public long revision(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.revision(owner);
        }
        synchronized (this) {
            getOrCreate(owner);
            return revisions.get(owner);
        }
    }

    /** Optimistic GUI control version; progress and physical port movement do not invalidate a command. */
    public long commandRevision(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.commandRevision(owner);
        }
        synchronized (this) {
            getOrCreate(owner);
            return commandRevisions.get(owner);
        }
    }

    /**
     * True when the pipeline itself died (global fail-stop; simulated death or unattributable batches).
     */
    public synchronized boolean recoveryRequired() {
        return recoveryRequired;
    }

    /**
     * True when THIS owner's durable batches failed (per-owner fault domain).
     */
    public boolean recoveryRequired(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.recoveryRequired(owner);
        }
        synchronized (this) {
            Objects.requireNonNull(owner);
            return recoveryRequired || failedOwners.contains(owner);
        }
    }

    /**
     * The failure that poisoned this owner's domain, if any; kept as evidence.
     */
    public Throwable recoveryCause(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.recoveryCause(owner);
        }
        synchronized (this) {
            return failedOwners.contains(owner) ? ownerFailureCauses.get(owner) : recoveryCause;
        }
    }

    private void changed(UUID owner) {
        changedFromRuntime(owner);
        commandRevisions.put(owner, Math.addExact(commandRevisions.get(owner), 1));
    }

    private void changedFromRuntime(UUID owner) {
        revisions.put(owner, Math.addExact(revisions.get(owner), 1));
    }

    private void recordRuntimeIngress(UUID owner, mekanism.cloudworks.common.runtime.CloudworksExternalEvent.Kind kind,
            int resource, long amount, double energy) {
        runtimeIngress.computeIfAbsent(owner, ignored ->
                new mekanism.cloudworks.common.runtime.CloudworksRuntimeIngressJournal())
                .record(revisions.get(owner), kind, resource, amount, energy);
    }

    public long runtimeExternalSequence(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.runtimeExternalSequence(owner);
        }
        synchronized (this) {
            mekanism.cloudworks.common.runtime.CloudworksRuntimeIngressJournal journal = runtimeIngress.get(owner);
            return journal == null ? 0 : journal.sequence();
        }
    }

    public boolean onlyRuntimeIngressSince(UUID owner, long previousRevision) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.onlyRuntimeIngressSince(owner, previousRevision);
        }
        synchronized (this) {
            long current = revisions.getOrDefault(owner, -1L);
            if (current == previousRevision) return true;
            mekanism.cloudworks.common.runtime.CloudworksRuntimeIngressJournal journal = runtimeIngress.get(owner);
            return journal != null && journal.covers(previousRevision, current);
        }
    }

    public List<mekanism.cloudworks.common.runtime.CloudworksExternalEvent> runtimeIngressAfter(
            UUID owner, long sequence) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.runtimeIngressAfter(owner, sequence);
        }
        synchronized (this) {
            mekanism.cloudworks.common.runtime.CloudworksRuntimeIngressJournal journal = runtimeIngress.get(owner);
            return journal == null ? sequence == 0 ? Collections.emptyList() : null : journal.after(sequence);
        }
    }

    public ProductionLineData line(UUID id) {
        if (parent == null) {
            CloudworksPlayerDataManager target = lineDomains.get(id);
            return target == null ? null : target.line(id);
        }
        synchronized (this) {
            if (runtimeUnmaterialized.contains(id)) {
                NBTTagCompound published = guiLines.get(id);
                if (published == null) throw new IllegalStateException("Missing Runtime line publication");
                lines.put(id, ProductionLineData.read(published.copy()));
                runtimeUnmaterialized.remove(id);
            }
            return lines.get(id);
        }
    }

    /** Normal tick eligibility reads metadata only, not machine inventories. */
    public List<UUID> runtimeLineIds(CloudworksData cloud, CloudworksSessionPolicy sessions) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(cloud.owner());
            return target.runtimeLineIds(cloud, sessions);
        }
        synchronized (this) {
            List<UUID> selected = new ArrayList<>();
            for (UUID id : cloud.lineIds()) {
                NBTTagCompound tag = guiLines.get(id);
                if (tag != null && tag.getBoolean("explicitRouting")
                        && sessions.canRun(cloud, UUID.fromString(tag.getString("owner")),
                            tag.getBoolean("enabled"), tag.getBoolean("runWhileOwnerOffline"))) selected.add(id);
            }
            return selected;
        }
    }

    /**
     * Read-only line ids of an already-loaded player; tab completion must never
     * trigger disk loads or create data, so uncached players simply suggest nothing.
     */
    public List<UUID> cachedLineIds(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.cachedLineIds(owner);
        }
        synchronized (this) {
            CloudworksData cloud = clouds.get(Objects.requireNonNull(owner));
            return cloud == null ? Collections.<UUID>emptyList() : new ArrayList<>(cloud.lineIds());
        }
    }

    public List<CloudworksData> cloudSnapshot() {
        if (parent == null) {
            return new ArrayList<>(cloudViews.values());
        }
        synchronized (this) {
            if (parent == null) return new ArrayList<>(cloudViews.values());
            return new ArrayList<>(clouds.values());
        }
    }

    public List<ProductionLineData> lines(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.lines(owner);
        }
        synchronized (this) {
            CloudworksData cloud = clouds.get(owner);
            if (cloud == null) return Collections.emptyList();
            List<ProductionLineData> result = new ArrayList<>();
            for (UUID id : cloud.lineIds()) {
                ProductionLineData line = line(id);
                if (line != null) result.add(line);
            }
            return result;
        }
    }

    // addNode and addMachinesToNode (the non-transactional node paths) were
    // removed: uploadMachines creates nodes and mergeMachines extends them, both
    // through the single durable transferId coordinator; tests build equivalent
    // state through depositMachines + edit, both live APIs.

    /**
     * Read-only eligibility probe for port tiles: false when the cloud is absent, disabled or failed.
     */
    public boolean enabledForTransfer(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.enabledForTransfer(owner);
        }
        synchronized (this) {
            try {
                return getOrCreate(owner).enabled();
            } catch (RuntimeException unavailable) {
                return false;
            }
        }
    }

    /**
     * Read-only sending-warehouse snapshot for download request planning. An absent
     * cloud yields an empty snapshot; null is returned only when the manager refuses
     * the read (not loaded, recovery required, failed owner).
     */
    public Map<mekanism.api.qio.resource.QIOResourceDescriptor, Long> sendingSnapshot(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.sendingSnapshot(owner);
        }
        synchronized (this) {
            try {
                return getOrCreate(owner).sending().snapshot();
            } catch (RuntimeException unavailable) {
                return null;
            }
        }
    }

    /**
     * Read-only sending energy relay view for download-port capacity planning.
     */
    public double sendingEnergy(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.sendingEnergy(owner);
        }
        synchronized (this) {
            return getOrCreate(owner).sendingEnergy();
        }
    }

    /**
     * Read-only receiving-warehouse snapshot (三仓查看 display view).
     */
    public Map<mekanism.api.qio.resource.QIOResourceDescriptor, Long> receivingSnapshot(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.receivingSnapshot(owner);
        }
        synchronized (this) {
            try {
                return getOrCreate(owner).receiving().snapshot();
            } catch (RuntimeException unavailable) {
                return null;
            }
        }
    }

    /**
     * Read-only transfer-warehouse snapshot (三仓查看 display view).
     */
    public Map<mekanism.api.qio.resource.QIOResourceDescriptor, Long> transferSnapshot(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.transferSnapshot(owner);
        }
        synchronized (this) {
            try {
                return getOrCreate(owner).transfer().snapshot();
            } catch (RuntimeException unavailable) {
                return null;
            }
        }
    }

    /**
     * Variant-level view of one machine-cache type entry (机器缓存层中列): every
     * NBT variant with its stored count, line-claimed amount and free amount.
     */
    public List<Map<String, Object>> machineCacheVariants(UUID owner, int typeIndex) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.machineCacheVariants(owner, typeIndex);
        }
        synchronized (this) {
            mekanism.cloudworks.common.data.CloudworksMachineCache cache = machineCache(owner);
            if (typeIndex < 0 || typeIndex >= cache.typeCount()) throw error("not_found");
            Map<Integer, Map<NBTTagCompound, Long>> occupied = deriveOccupation(owner, cache);
            Map<NBTTagCompound, Long> byTag = occupied.getOrDefault(typeIndex, Collections.emptyMap());
            List<Map<String, Object>> view = new ArrayList<>();
            List<mekanism.cloudworks.common.data.CloudworksMachineCache.Entry> entries = cache.entries();
            for (mekanism.cloudworks.common.data.CloudworksMachineCache.Variant variant : entries.get(typeIndex).variants()) {
                long claimed = byTag.getOrDefault(variant.tag(), 0L);
                Map<String, Object> row = new LinkedHashMap<>();
                ItemStack machine = entries.get(typeIndex).template();
                machine.setTagCompound(variant.tag().getKeySet().isEmpty() ? null : variant.tag());
                row.put("machine", machine);
                row.put("count", variant.count());
                row.put("claimed", claimed);
                row.put("available", Math.max(0, variant.count() - claimed));
                view.add(row);
            }
            return view;
        }
    }

    /**
     * Read-only receiving room for one resource type (port upload planning, 协议储存箱).
     * Lazy-loads the owner's cloud exactly like every other port probe
     * ({@code getOrCreate}), so a cloud that exists on disk but is not yet in
     * memory (fresh server session) answers with its real room instead of a
     * spurious 0 that would pause the upload port. Manager/owner failures throw;
     * both port call sites already catch and retry the whole cycle without
     * touching their pause state.
     */
    public long receivingRoom(UUID owner, mekanism.api.qio.resource.QIOResourceDescriptor descriptor) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.receivingRoom(owner, descriptor);
        }
        synchronized (this) {
            return getOrCreate(owner).receiving().insertable(descriptor, Long.MAX_VALUE);
        }
    }

    // GLM handoff §4.7: insertReceiving / extractSending (the pre-lease direct
    // port-write legs) were deleted together with their retired fixtures —
    // the durable transfer leases are the only port storage surface now.

    // ------------------------------------------------ 云端升级池（升级层）

    /**
     * 云端升级池: 正数入库（上传/回池），负数扣减（安装）; 池不足时拒绝。
     * Returns the new pool value, or -1 when a deduction would go negative.
     */
    public int addUpgradePool(UUID owner, boolean speed, int delta) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.addUpgradePool(owner, speed, delta);
        }
        synchronized (this) {
            CloudworksData cloud = requireEnabled(owner);
            int current = cloud.upgradePool(speed);
            if (delta < 0 && current + delta < 0) return -1;
            // P1-3 fix: snapshot BEFORE the mutation — the rollback must restore the
            // pre-change pool, not read back what was just written into memory.
            CloudworksData previous = cloud.copyForRuntime();
            int next = cloud.addUpgradePool(speed, delta);
            try {
                publishTags(owner, cloud.write(), Collections.emptyList());
                drainInteraction();
                return next;
            } catch (RuntimeException failure) {
                clouds.put(owner, previous);
                throw failure;
            }
        }
    }

    /** 删线/卸机器溢出回池（内部路径——调用方在同一意图里已携带 cloud 投影）。 */
    private void returnUpgradesToPool(CloudworksData cloud, int speedCount, int energyCount) {
        if (speedCount > 0) cloud.addUpgradePool(true, speedCount);
        if (energyCount > 0) cloud.addUpgradePool(false, energyCount);
    }

    private void refreshLineUpgrades(CloudworksData cloud, ProductionLineData line) {
        int speed = line.speedUpgrades(), energy = line.energyUpgrades();
        double storedEnergy = line.energy();
        line.recomputeUpgradeLimits();
        returnUpgradesToPool(cloud, speed - line.speedUpgrades(), energy - line.energyUpgrades());
        settleLineEnergy(cloud, line, storedEnergy);
    }

    private void settleLineEnergy(CloudworksData cloud, ProductionLineData line, double storedEnergy) {
        line.setEnergy(storedEnergy, line.maxEnergy());
        double excess = storedEnergy - line.energy();
        if (excess > 0 && !cloud.unlimitedEnergy()) {
            if (cloud.maxEnergy() - cloud.energy() < excess) throw error("energy_not_empty");
            cloud.setEnergy(cloud.energy() + excess, cloud.maxEnergy(), false);
        }
    }

    public void registerTypeUnblockListener(UUID owner, mekanism.api.qio.resource.QIOResourceDescriptor descriptor,
                                                         Runnable callback) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.registerTypeUnblockListener(owner, descriptor, callback);
            return;
        }
        synchronized (this) {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(descriptor);
            Objects.requireNonNull(callback);
            typeUnblockListeners.computeIfAbsent(owner, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(descriptor, ignored -> new ArrayList<>()).add(callback);
        }
    }

    public void unregisterTypeUnblockListener(UUID owner, mekanism.api.qio.resource.QIOResourceDescriptor descriptor,
                                                           Runnable callback) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.unregisterTypeUnblockListener(owner, descriptor, callback);
            return;
        }
        synchronized (this) {
            Map<mekanism.api.qio.resource.QIOResourceDescriptor, List<Runnable>> byType = typeUnblockListeners.get(owner);
            if (byType == null) return;
            List<Runnable> callbacks = byType.get(descriptor);
            if (callbacks == null || !callbacks.remove(callback)) return;
            if (callbacks.isEmpty()) byType.remove(descriptor);
            if (byType.isEmpty()) typeUnblockListeners.remove(owner);
        }
    }

    /**
     * Server-tick dispatch: fires and removes every watcher whose type now has receiving room.
     */
    public void dispatchTypeUnblockNotifications() {
        if (parent == null) {
            for (CloudworksPlayerDataManager target : domains.values()) target.dispatchTypeUnblockNotifications();
            return;
        }
        synchronized (this) {
            if (typeUnblockListeners.isEmpty()) return;
            Iterator<Map.Entry<UUID, Map<mekanism.api.qio.resource.QIOResourceDescriptor, List<Runnable>>>> ownerIterator
                    = typeUnblockListeners.entrySet().iterator();
            while (ownerIterator.hasNext()) {
                Map.Entry<UUID, Map<mekanism.api.qio.resource.QIOResourceDescriptor, List<Runnable>>> ownerEntry = ownerIterator.next();
                CloudworksData cloud = clouds.get(ownerEntry.getKey());
                if (cloud == null) continue;
                Iterator<Map.Entry<mekanism.api.qio.resource.QIOResourceDescriptor, List<Runnable>>> typeIterator
                        = ownerEntry.getValue().entrySet().iterator();
                while (typeIterator.hasNext()) {
                    Map.Entry<mekanism.api.qio.resource.QIOResourceDescriptor, List<Runnable>> typeEntry = typeIterator.next();
                    if (cloud.receiving().insertable(typeEntry.getKey(), 1L) <= 0) continue;
                    typeIterator.remove();
                    for (Runnable callback : new ArrayList<>(typeEntry.getValue())) {
                        try {
                            callback.run();
                        } catch (RuntimeException ignored) {
                            // a dead port must never stop other notifications
                        }
                    }
                }
                if (ownerEntry.getValue().isEmpty()) ownerIterator.remove();
            }
        }
    }

    public void enable(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.enable(owner);
            return;
        }
        synchronized (this) {
            setCloudEnabled(owner, true);
        }
    }

    /**
     * P1-2 fail-closed probe: does a cloud RECORD exist and is it enabled?
     * Never creates and never loads anything — a fresh player stays record-free
     * (no getOrCreate side effects on the login path). Used by the login
     * reconciliation to detect the enable-direction crash window (record ON +
     * capability OFF) and heal it back to the safe stopped state.
     */
    public boolean recordEnabled(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.recordEnabled(owner);
        }
        synchronized (this) {
            Objects.requireNonNull(owner);
            CloudworksData cached = clouds.get(owner);
            if (cached != null) return cached.enabled();
            try {
                if (root == null) return false;
                Path file = cloudFile(owner);
                if (!Files.exists(file)) return false;
                return read(file).getBoolean("enabled");
            } catch (RuntimeException unreadable) {
                return false;   // unreadable record: nothing provably running, skip healing
            }
        }
    }

    /**
     * Admin enable/disable of one owner's cloud. The record is the execution
     * authority (ticks, runnability, manager entries); the caller flips the
     * player capability (the GUI entrance permit) in a direction-aware order —
     * capability LEADS on enable and TRAILS on disable (P1-2, user decision
     * 2026-10-05) — so a crash between the two writes always fails closed.
     * The login reconciliation (Cloudworks#playerLoggedIn) additionally heals
     * any pre-existing record-ON/capability-OFF state back to stopped.
     */
    public void setCloudEnabled(UUID owner, boolean enabled) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.setCloudEnabled(owner, enabled);
            return;
        }
        synchronized (this) {
            CloudworksData cloud = getOrCreate(owner);
            boolean previous = cloud.enabled();
            if (previous == enabled) return;
            cloud.setEnabled(enabled);
            try {
                persistCloudCore(owner, cloud);
            } catch (RuntimeException failure) {
                cloud.setEnabled(previous);
                throw failure;
            }
            changed(owner);
        }
    }

    public ProductionLineData createLine(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.createLine(owner);
        }
        synchronized (this) {
            CloudworksData cloud = requireEnabled(owner);
            if (cloud.lineIds().size() >= Math.min(cloud.unlockedLines(),
                    mekanism.cloudworks.common.config.CloudworksConfig.settings().maxLines)) throw error("limit");
            ProductionLineData line = new ProductionLineData(UUID.randomUUID(), owner);
            line.setExplicitRouting(true);
            writeCheckpoint(line);
            cloud.lineIds().add(line.lineId());
            try {
                persistCloudCore(owner, cloud);
            } catch (RuntimeException e) {
                cloud.lineIds().remove(line.lineId());
                throw e;
            }
            lines.put(line.lineId(), line);
            changed(owner);
            return line;
        }
    }

    /**
     * Deletes a stopped line. Everything it held is released: node input/output
     * caches and channel buffers are routed to the recycle bin, machine claims
     * dissolve automatically (the derived occupation drops), and links,
     * endpoints and the line energy pool are discarded with the line.
     */
    public boolean deleteLine(UUID owner, UUID id) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.deleteLine(owner, id);
        }
        synchronized (this) {
            ProductionLineData line = owned(owner, id);
            if (line == null) return false;
            if (line.enabled()) throw error("line_active");
            // One durable intent carries the index removal AND the recycle deposit of
            // everything the line held: a process death between the two writes can no
            // longer duplicate the resources. Pre-submit failures roll memory back (P1).
            mekanism.cloudworks.common.data.CloudworksRecycleBin bin = recycleBin(owner);
            NBTTagCompound previousBin = bin.write();
            NBTTagCompound previousLine = line.write();
            CloudworksData cloud = clouds.get(owner);
            int index = cloud.lineIds().indexOf(id);
            if (index < 0) return false;
            try {
                long discarded = 0;
                for (MachineNodeData node : line.machines()) {
                    node.cancelOperation();
                    discarded += recycleIntoBin(bin, node.inputs(), node.outputs(), node.templates());
                }
                for (CloudworksChannelNode channel : line.channels())
                    for (Map.Entry<mekanism.api.qio.resource.QIOResourceDescriptor, Long> entry
                            : channel.buffer().snapshot().entrySet())
                        discarded += bin.deposit(entry.getKey(), entry.getValue());
                boolean binChanged = !bin.write().equals(previousBin);
                cloud.lineIds().remove(index);
                // 升级层: 删线时已安装升级全部回池（同一意图携带 cloud 投影）
                returnUpgradesToPool(cloud, line.speedUpgrades(), line.energyUpgrades());
                try {
                    CloudworksPendingBlueprint pendingBlueprint = pendingBlueprints.get(owner);
                    publish0(owner, cloud.write(), Collections.emptyList(), binChanged ? bin.write() : null,
                            null, null, null, true, false,
                            pendingBlueprint != null && pendingBlueprint.line.equals(id) ? CloudworksPendingBlueprint.empty() : null,
                            Collections.singletonList(id));
                } catch (RuntimeException failure) {
                    cloud.lineIds().add(index, id);
                    returnUpgradesToPool(cloud, -line.speedUpgrades(), -line.energyUpgrades());
                    lines.put(id, ProductionLineData.read(previousLine));
                    throw failure;
                }
                if (discarded > 0) pendingRecycleOverflow.add(owner);
                lines.remove(id);
                if (parent != null) parent.lineDomains.remove(id, this);
                runtimeUnmaterialized.remove(id);
                guiLines.remove(id);
                checkpointGenerations.remove(id);
                return true;
            } catch (RuntimeException failure) {
                recycleBins.put(owner, mekanism.cloudworks.common.data.CloudworksRecycleBin.read(previousBin));
                lines.put(id, ProductionLineData.read(previousLine));
                throw failure;
            }
        }
    }

    public boolean renameLine(UUID owner, UUID id, String name) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.renameLine(owner, id, name);
        }
        synchronized (this) {
            ProductionLineData line = owned(owner, id);
            if (line == null) return false;
            String checked = checkedName(name);
            String previous = line.name();
            line.setName(checked);
            try {
                writeCheckpoint(line);
            } catch (RuntimeException e) {
                line.setName(previous);
                throw e;
            }
            if (!previous.equals(checked)) changed(owner);
            return true;
        }
    }

    public boolean toggleLine(UUID owner, UUID id, boolean enabled) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.toggleLine(owner, id, enabled);
        }
        synchronized (this) {
            ProductionLineData line = owned(owner, id);
            if (line == null) return false;
            if (enabled) {
                CloudworksTopologyValidator.Result validation = CloudworksTopologyValidator.validate(line);
                if (!validation.valid() || line.machines().isEmpty()) throw error("invalid_line");
            }
            boolean previous = line.enabled();
            line.setEnabled(enabled);
            try {
                writeCheckpoint(line);
            } catch (RuntimeException e) {
                line.setEnabled(previous);
                throw e;
            }
            if (previous != enabled) changed(owner);
            return true;
        }
    }

    /** One-time item unlock; the card is consumed only after the cloud update is durable. */
    public int unlockOfflineProcessing(UUID owner) {
        if (parent == null) return domain(owner).unlockOfflineProcessing(owner);
        synchronized (this) {
            CloudworksData cloud = getOrCreate(owner);
            if (!cloud.enabled()) return -1;
            if (!mekanism.cloudworks.common.config.CloudworksConfig.settings().offlineProcessingEnabled) return -3;
            if (cloud.offlineProcessingUnlocked()) return -2;
            CloudworksData previous = cloud.copyForRuntime();
            try {
                cloud.setOfflineProcessingUnlocked(true);
                publishTags(owner, cloud.write(), Collections.emptyList());
                drainInteraction();
                return 1;
            } catch (RuntimeException failure) {
                clouds.put(owner, previous);
                throw failure;
            }
        }
    }

    /**
     * Trusted server unlock API for commands, items and advancements.
     */
    public void setOfflineProcessingUnlocked(UUID owner, boolean unlocked) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.setOfflineProcessingUnlocked(owner, unlocked);
            return;
        }
        synchronized (this) {
            CloudworksData cloud = requireEnabled(owner);
            boolean previous = cloud.offlineProcessingUnlocked();
            if (previous == unlocked) return;
            cloud.setOfflineProcessingUnlocked(unlocked);
            try {
                persistCloudCore(owner, cloud);
            } catch (RuntimeException failure) {
                cloud.setOfflineProcessingUnlocked(previous);
                throw failure;
            }
            changed(owner);
        }
    }

    public void setRunWhileOwnerOffline(UUID owner, UUID id, boolean enabled) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.setRunWhileOwnerOffline(owner, id, enabled);
            return;
        }
        synchronized (this) {
            ProductionLineData line = owned(owner, id);
            if (line == null) throw error("not_found");
            if (enabled && !mekanism.cloudworks.common.config.CloudworksConfig.settings().offlineProcessingEnabled)
                throw error("offline_disabled");
            if (enabled && !clouds.get(owner).offlineProcessingUnlocked()) throw error("offline_locked");
            boolean previous = line.runWhileOwnerOffline();
            if (previous == enabled) return;
            line.setRunWhileOwnerOffline(enabled);
            try {
                writeCheckpoint(line);
            } catch (RuntimeException failure) {
                line.setRunWhileOwnerOffline(previous);
                throw failure;
            }
            changed(owner);
        }
    }

    /** A leased physical terminal. Pending durable projections lock all slot access. */
    public static final class TerminalSession {
        private final UUID owner;
        private final mekanism.cloudworks.common.content.CloudworksTerminalHost host;
        private CloudworksTerminalState pending;
        private boolean pendingApplied;
        private boolean closed;
        private TerminalSession(UUID owner, mekanism.cloudworks.common.content.CloudworksTerminalHost host) {
            this.owner = owner;
            this.host = host;
        }
    }

    public TerminalSession openTerminal(UUID owner,
            mekanism.cloudworks.common.content.CloudworksTerminalHost host) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.openTerminal(owner, host);
        }
        synchronized (this) {
            if (host instanceof mekanism.cloudworks.common.tile.TileEntityCloudDashboard dashboard
                    && !Objects.equals(owner, dashboard.getSecurity().getOwnerUUID())) throw error("owner");
            requireEnabled(owner);
            if (!host.available()) throw error("busy");
            if (!drainInteraction() || recoveryRequired(owner)) throw error("busy");
            if (terminalSessions.containsKey(host.terminalId())) throw error("busy");
            host.persistIdentity();
            if (!host.identityDurable()) throw error("busy");
            TerminalSession session = new TerminalSession(owner, host);
            Path file = terminalFile(host.terminalId());
            if (Files.exists(file)) {
                CloudworksTerminalState state = CloudworksTerminalState.read(read(file));
                if (!owner.equals(state.owner())) throw error("owner");
                if (!state.transferId().equals(host.appliedTerminalTransfer())) session.pending = state;
            } else {
                terminalPublish(session, CloudworksTerminalState.capture(owner, host), null, null);
            }
            terminalSessions.put(host.terminalId(), session);
            parent.terminalDomains.put(host.terminalId(), this);
            parent.terminalOwners.put(host.terminalId(), owner);
            return session;
        }
    }

    /** Poll only; server tick never waits for disk. */
    public boolean terminalReady(TerminalSession session) {
        if (session == null) return false;
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(session.owner);
            return target.terminalReady(session);
        }
        synchronized (this) {
            if (session == null || session.closed || terminalSessions.get(session.host.terminalId()) != session
                    || !session.host.available() || recoveryRequired(session.owner)) return false;
            if (session.host instanceof mekanism.cloudworks.common.tile.TileEntityCloudDashboard dashboard
                    && !session.owner.equals(dashboard.getSecurity().getOwnerUUID())) return false;
            CloudworksData enabledCloud = clouds.get(session.owner);
            if (enabledCloud == null || !enabledCloud.enabled()) return false;
            if (session.pending == null) return session.host.identityDurable();
            if (persistenceBacklog(session.owner) != 0) return false;
            if (!session.pendingApplied) {
                CloudworksTerminalState disk = CloudworksTerminalState.read(read(terminalFile(session.host.terminalId())));
                if (!disk.transferId().equals(session.pending.transferId())) throw dataError(null);
                disk.apply(session.host);
                session.host.persistIdentity();
                session.pendingApplied = true;
            }
            if (!session.host.identityDurable()) return false;
            session.pending = null;
            session.pendingApplied = false;
            return true;
        }
    }

    public void closeTerminal(TerminalSession session) {
        if (session == null) return;
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(session.owner);
            target.closeTerminal(session);
            return;
        }
        synchronized (this) {
            if (session == null) return;
            session.closed = true;
            terminalSessions.remove(session.host.terminalId(), session);
        }
    }

    /**
     * Ordinary clicks remain host-owned. Retain the last cloud-transfer acknowledgement:
     * publishing these clicks as cloud projections could replay stale slots over a saved player inventory.
     */
    public void checkpointTerminal(TerminalSession session) {
        if (session == null) return;
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(session.owner);
            target.checkpointTerminal(session);
            return;
        }
        synchronized (this) {
            if (!terminalReady(session)) throw error("busy");
            session.host.persistIdentity();
        }
    }

    public void processTerminalMachineInput(TerminalSession session) {
        if (session == null) return;
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(session.owner);
            target.processTerminalMachineInput(session);
            return;
        }
        synchronized (this) {
            if (!terminalReady(session)) return;
            CloudworksTerminalState before = CloudworksTerminalState.capture(session.owner, session.host);
            ItemStack machines = before.stack(0);
            if (machines.isEmpty()) return;
            if (!CloudworksRegistries.isRegistered(machines)) throw error("machine");
            CloudworksMachineCache cache = CloudworksMachineCache.read(machineCache(session.owner).write());
            cache.deposit(machines);
            terminalPublish(session, before.withSlot(0, ItemStack.EMPTY), null, cache);
        }
    }

    public void processTerminalEnergyInput(TerminalSession session) {
        if (session == null) return;
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(session.owner);
            target.processTerminalEnergyInput(session);
            return;
        }
        synchronized (this) {
            if (!terminalReady(session)) return;
            CloudworksTerminalState before = CloudworksTerminalState.capture(session.owner, session.host);
            ItemStack cubeStack = before.stack(2);
            if (cubeStack.isEmpty()) return;
            TerminalEnergyPayload payload = terminalEnergyPayload(cubeStack);
            if (cubeStack.getCount() != 1 || payload == null || payload.capacity <= 0D)
                throw error("energy_item");
            CloudworksData cloud = CloudworksData.read(requireEnabled(session.owner).write());
            cloud.depositEnergy(0D, payload.capacity, payload.unlimited);
            terminalPublish(session, before.withSlot(2, ItemStack.EMPTY), cloud, null);
        }
    }

    public void processTerminalUpgradeInput(TerminalSession session) {
        if (session == null) return;
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(session.owner);
            target.processTerminalUpgradeInput(session);
            return;
        }
        synchronized (this) {
            if (!terminalReady(session)) return;
            CloudworksTerminalState before = CloudworksTerminalState.capture(session.owner, session.host);
            ItemStack upgrades = before.stack(3);
            if (upgrades.isEmpty() || !(upgrades.getItem() instanceof mekanism.common.base.IUpgradeItem upgradeItem))
                throw error("upgrade");
            mekanism.common.Upgrade type = upgradeItem.getUpgradeType(upgrades);
            if (type != mekanism.common.Upgrade.SPEED && type != mekanism.common.Upgrade.ENERGY)
                throw error("upgrade");
            CloudworksData cloud = CloudworksData.read(requireEnabled(session.owner).write());
            cloud.addUpgradePool(type == mekanism.common.Upgrade.SPEED, upgrades.getCount());
            terminalPublish(session, before.withSlot(3, ItemStack.EMPTY), cloud, null);
        }
    }

    private void terminalPublish(TerminalSession session, CloudworksTerminalState state,
                                 CloudworksData cloud, CloudworksMachineCache cache) {
        if (session.host instanceof mekanism.cloudworks.common.tile.TileEntityCloudDashboard) {
            // The terminal intent contains the complete slot projection. Keep
            // the dashboard dirty for vanilla's save path, but avoid writing
            // and compressing the whole region synchronously on every transfer.
            session.host.persist();
        } else {
            session.host.persistIdentity();
            if (!session.host.identityDurable()) throw error("busy");
        }
        CloudworksData current = cloud == null ? requireEnabled(session.owner) : cloud;
        publish0(session.owner, current.write(), Collections.emptyList(), null,
                cache == null ? null : cache.write(), null, state.write());
        session.pending = state;
        session.pendingApplied = false;
    }

    public void processTerminalInputs(TerminalSession session) {
        if (session == null) return;
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(session.owner);
            target.processTerminalInputs(session);
            return;
        }
        synchronized (this) {
            if (!terminalReady(session)) return;
            CloudworksTerminalState before = CloudworksTerminalState.capture(session.owner, session.host);
            ItemStack machines = before.stack(0);
            if (!machines.isEmpty()) {
                processTerminalMachineInput(session);
                return;
            }
            ItemStack cubeStack = before.stack(2);
            if (!cubeStack.isEmpty()) {
                TerminalEnergyPayload payload = terminalEnergyPayload(cubeStack);
                if (cubeStack.getCount() != 1 || payload == null || payload.capacity <= 0D)
                    throw error("energy_item");
                CloudworksData cloud = CloudworksData.read(requireEnabled(session.owner).write());
                cloud.depositEnergy(0D, payload.capacity, payload.unlimited);
                terminalPublish(session, before.withSlot(2, ItemStack.EMPTY), cloud, null);
                return;
            }
            ItemStack upgrades = before.stack(3);
            if (!upgrades.isEmpty() && upgrades.getItem() instanceof mekanism.common.base.IUpgradeItem upgradeItem) {
                mekanism.common.Upgrade type = upgradeItem.getUpgradeType(upgrades);
                if (type != mekanism.common.Upgrade.SPEED && type != mekanism.common.Upgrade.ENERGY) throw error("upgrade");
                CloudworksData cloud = CloudworksData.read(requireEnabled(session.owner).write());
                cloud.addUpgradePool(type == mekanism.common.Upgrade.SPEED, upgrades.getCount());
                terminalPublish(session, before.withSlot(3, ItemStack.EMPTY), cloud, null);
            }
        }
    }

    private static int terminalRoom(ItemStack current, ItemStack incoming) {
        if (current.isEmpty()) return incoming.getMaxStackSize();
        if (!ItemStack.areItemsEqual(current, incoming) || !ItemStack.areItemStackTagsEqual(current, incoming)) return 0;
        return Math.max(0, incoming.getMaxStackSize() - current.getCount());
    }

    private static ItemStack terminalMerge(ItemStack current, ItemStack incoming) {
        ItemStack result = incoming.copy();
        result.setCount(incoming.getCount() + (current.isEmpty() ? 0 : current.getCount()));
        return result;
    }

    public void terminalDownloadMachines(TerminalSession session, int type, int variant, boolean shift) {
        if (session == null) return;
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(session.owner);
            target.terminalDownloadMachines(session, type, variant, shift);
            return;
        }
        synchronized (this) {
            if (!terminalReady(session)) return;
            CloudworksMachineCache cache = CloudworksMachineCache.read(machineCache(session.owner).write());
            if (type < 0 || type >= cache.typeCount()) throw error("not_found");
            CloudworksMachineCache.Entry entry = cache.entries().get(type);
            if (variant < 0 || variant >= entry.variants().size()) throw error("not_found");
            NBTTagCompound target = entry.variants().get(variant).tag();
            ItemStack template = entry.template();
            template.setTagCompound(target.copy());
            CloudworksTerminalState before = CloudworksTerminalState.capture(session.owner, session.host);
            int wanted = Math.min(shift ? template.getMaxStackSize() : 1, terminalRoom(before.stack(1), template));
            if (wanted == 0) return;
            Map<NBTTagCompound, Long> claimed = deriveOccupation(session.owner, cache).getOrDefault(type, Collections.emptyMap());
            List<ItemStack> taken = cache.withdraw(type, wanted, value -> {
                if (!value.tag().equals(target)) return 0;
                return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value.count() - claimed.getOrDefault(target, 0L)));
            });
            if (taken.isEmpty()) return;
            // One selected variant, one max-stack-bounded destination.
            terminalPublish(session, before.withSlot(1, terminalMerge(before.stack(1), taken.get(0))), null, cache);
        }
    }

    public void terminalDownloadUpgrade(TerminalSession session, boolean speed, boolean shift) {
        if (session == null) return;
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(session.owner);
            target.terminalDownloadUpgrade(session, speed, shift);
            return;
        }
        synchronized (this) {
            if (!terminalReady(session)) return;
            CloudworksData cloud = CloudworksData.read(requireEnabled(session.owner).write());
            ItemStack stack = mekanism.common.util.UpgradeUtils.getStack(
                    speed ? mekanism.common.Upgrade.SPEED : mekanism.common.Upgrade.ENERGY, 1);
            CloudworksTerminalState before = CloudworksTerminalState.capture(session.owner, session.host);
            int wanted = Math.min(cloud.upgradePool(speed), Math.min(shift ? stack.getMaxStackSize() : 1,
                    terminalRoom(before.stack(4), stack)));
            if (wanted == 0) return;
            stack.setCount(wanted);
            cloud.addUpgradePool(speed, -wanted);
            terminalPublish(session, before.withSlot(4, terminalMerge(before.stack(4), stack)), cloud, null);
        }
    }

    /** Old ownerless dashboards retain the journal owner instead of being claimed by the next visitor. */
    public void claimDashboard(mekanism.cloudworks.common.tile.TileEntityCloudDashboard dashboard,
                                             UUID placer) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(terminalOwners.getOrDefault(dashboard.terminalId(), placer));
            target.claimDashboard(dashboard, placer);
            return;
        }
        synchronized (this) {
            if (dashboard.getSecurity().getOwnerUUID() != null) return;
            requireLoaded();
            Path file = terminalFile(dashboard.terminalId());
            UUID owner = Files.exists(file) ? CloudworksTerminalState.read(read(file)).owner() : placer;
            parent.terminalOwners.put(dashboard.terminalId(), owner);
            dashboard.getSecurity().setOwnerUUID(owner);
            dashboard.markDirty();
        }
    }

    public void detachTerminal(mekanism.cloudworks.common.content.CloudworksTerminalHost host) {
        if (parent == null) {
            CloudworksPlayerDataManager target = terminalDomains.get(host.terminalId());
            if (target != null) target.detachTerminal(host);
            return;
        }
        synchronized (this) {
            TerminalSession session = terminalSessions.get(host.terminalId());
            if (session != null && session.host == host) closeTerminal(session);
        }
    }

    public int persistenceBacklog(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.persistenceBacklog(owner);
        }
        synchronized (this) {
            CloudworksPersistenceQueue queue = persistence;
            return queue == null ? 0 : queue.ownerBacklog(owner);
        }
    }

    private void publishPort(UUID owner, NBTTagCompound operation) {
        if (recoveryRequired || failedOwners.contains(owner)) throw dataError(recoveryCause(owner));
        NBTTagCompound intent = new NBTTagCompound();
        intent.setInteger("schema", 2);
        intent.setString("owner", owner.toString());
        NBTTagList operations = new NBTTagList();
        operations.appendTag(operation);
        intent.setTag("operations", operations);
        persistence.submitIntent(owner, intent);
    }

    private Map<String, Path> projectionPaths(UUID owner, NBTTagCompound intent) {
        Map<String, Path> paths = new LinkedHashMap<>();
        paths.put("cloud", cloudFile(owner));
        paths.put("receiving", receivingFile(owner));
        paths.put("transfer", transferStoreFile(owner));
        paths.put("sending", sendingFile(owner));
        if (intent.hasKey("recycle", 10)) paths.put("recycle", recycleFile(owner));
        if (intent.hasKey("machineCache", 10)) paths.put("machineCache", machineCacheFile(owner));
        if (intent.hasKey("blueprintPending", 10)) paths.put("blueprintPending", blueprintPendingFile(owner));
        if (intent.hasKey("terminal", 10)) paths.put("terminal", terminalFile(
                CloudworksTerminalState.read(intent.getCompoundTag("terminal")).terminalId()));
        return paths;
    }

    private static NBTTagCompound warehouseDifference(CloudworksData before, CloudworksData after) {
        CloudworksWarehouseDelta delta = new CloudworksWarehouseDelta();
        after.receiving().collectChanges(before.receiving(), "receiving", delta);
        after.transfer().collectChanges(before.transfer(), "transfer", delta);
        after.sending().collectChanges(before.sending(), "sending", delta);
        if (before.receivingCursor() != after.receivingCursor()) delta.cursor("receiving", after.receivingCursor());
        if (before.transferCursor() != after.transferCursor()) delta.cursor("transfer", after.transferCursor());
        return delta.write();
    }

    private static void applyCore(CloudworksData cloud, NBTTagCompound core) { cloud.applyCore(core); }

    private static void applyMemoryDelta(CloudworksData cloud, NBTTagCompound delta) {
        for (String name : new String[]{"receiving", "transfer", "sending"}) {
            CloudworksInventory inventory = "receiving".equals(name) ? cloud.receiving()
                    : "transfer".equals(name) ? cloud.transfer() : cloud.sending();
            NBTTagCompound warehouse = delta.getCompoundTag(name);
            NBTTagList entries = warehouse.getTagList("entries", 10);
            for (int index = 0; index < entries.tagCount(); index++) {
                NBTTagCompound entry = entries.getCompoundTagAt(index);
                setAmount(inventory, QIOResourceDescriptor.read(entry.getCompoundTag("descriptor")), entry.getLong("amount"));
            }
            if (warehouse.hasKey("cursor", 3)) {
                if ("receiving".equals(name)) cloud.setReceivingCursor(warehouse.getInteger("cursor"));
                else if ("transfer".equals(name)) cloud.setTransferCursor(warehouse.getInteger("cursor"));
            }
        }
    }

    private static void setAmount(CloudworksInventory inventory, QIOResourceDescriptor descriptor, long amount) {
        long current = inventory.amount(descriptor);
        if (current > amount) inventory.extract(descriptor, current - amount);
        else if (amount > current && inventory.insertIgnoringTypeCap(descriptor, amount - current) != amount - current)
            throw new IllegalStateException("Cloudworks delta exceeds capacity");
    }

    private NBTTagCompound nextProjection(UUID owner, NBTTagCompound intent, CloudworksData cloud, String key) {
        ProjectionPlan plan = projectionPlan(intent);
        if (plan != null) return plan.next.computeIfAbsent(key,
                ignored -> buildNextProjection(owner, intent, cloud, key));
        return buildNextProjection(owner, intent, cloud, key);
    }

    private NBTTagCompound buildNextProjection(UUID owner, NBTTagCompound intent, CloudworksData cloud, String key) {
        DEBUG_PROJECTION_BUILDS.incrementAndGet();
        if (key.equals("cloud")) return preservePortWatermark(cloudFile(owner), cloud.writeCore());
        if (key.equals("receiving") || key.equals("transfer") || key.equals("sending"))
            return warehouseProjection(owner, intent, cloud, key);
        NBTTagCompound next = key.equals("terminal")
                ? CloudworksTerminalState.read(intent.getCompoundTag(key)).write() : intent.getCompoundTag(key);
        return preservePortWatermark(projectionPaths(owner, intent).get(key), next);
    }

    private static byte[] projectionFingerprint(NBTTagCompound intent, String key, NBTTagCompound next) {
        ProjectionPlan plan = projectionPlan(intent);
        if (plan != null && plan.next.get(key) == next) return plan.fingerprints.computeIfAbsent(key, ignored -> {
            DEBUG_PROJECTION_HASHES.incrementAndGet();
            return CloudworksNbtFingerprint.of(next);
        });
        DEBUG_PROJECTION_HASHES.incrementAndGet();
        return CloudworksNbtFingerprint.of(next);
    }

    private void packProjectionPatches(UUID owner, NBTTagCompound intent) {
        NBTTagCompound patches = new NBTTagCompound();
        ProjectionPlan plan = projectionPlan(intent);
        for (String key : new String[]{"machineCache", "recycle", "terminal", "blueprintPending"}) {
            if (!intent.hasKey(key, 10)) continue;
            Path file = projectionPaths(owner, intent).get(key);
            NBTTagCompound entry = new NBTTagCompound();
            if ("terminal".equals(key))
                entry.setString("terminalId", intent.getCompoundTag(key).getString("terminalId"));
            entry.setTag("patch", CloudworksNbtPatch.between(Files.exists(file) ? readView(file) : null,
                    plan == null ? intent.getCompoundTag(key) : plan.next.get(key)));
            patches.setTag(key, entry);
        }
        NBTTagList lines = intent.getTagList("lines", 10), deltaLines = new NBTTagList();
        if (plan != null) plan.lines = lines;
        for (int index = 0; index < lines.tagCount(); index++) {
            NBTTagCompound next = lines.getCompoundTagAt(index);
            UUID id = UUID.fromString(next.getString("lineId"));
            Path file = lineFile(owner, id);
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("lineId", id.toString());
            entry.setTag("patch", CloudworksNbtPatch.between(Files.exists(file) ? readView(file) : null, next));
            deltaLines.appendTag(entry);
        }
        for (String key : patches.getKeySet()) intent.removeTag(key);
        intent.setTag("projectionPatches", patches);
        intent.setTag("linePatches", deltaLines);
        intent.setTag("lines", new NBTTagList());
    }

    private NBTTagCompound expandProjectionPatches(UUID owner, NBTTagCompound intent) {
        if (!intent.hasKey("projectionPatches", 10)) return intent;
        NBTTagCompound expanded = shallowHeader(intent);
        ProjectionPlan prepared = projectionPlan(intent);
        if (prepared != null && prepared.lines != null) {
            for (String key : intent.getCompoundTag("projectionPatches").getKeySet())
                expanded.setTag(key, prepared.next.get(key));
            expanded.setTag("lines", prepared.lines);
            IO_READS.get().projections.put(expanded, prepared);
            return expanded;
        }
        NBTTagCompound patches = intent.getCompoundTag("projectionPatches");
        for (String key : patches.getKeySet()) {
            NBTTagCompound entry = patches.getCompoundTag(key);
            Path file = "machineCache".equals(key) ? machineCacheFile(owner)
                    : "recycle".equals(key) ? recycleFile(owner)
                    : "blueprintPending".equals(key) ? blueprintPendingFile(owner)
                    : terminalFile(UUID.fromString(entry.getString("terminalId")));
            expanded.setTag(key, CloudworksNbtPatch.apply(Files.exists(file) ? readView(file) : null,
                    entry.getCompoundTag("patch")));
        }
        NBTTagList deltaLines = intent.getTagList("linePatches", 10), lines = new NBTTagList();
        for (int index = 0; index < deltaLines.tagCount(); index++) {
            NBTTagCompound entry = deltaLines.getCompoundTagAt(index);
            Path file = lineFile(owner, UUID.fromString(entry.getString("lineId")));
            lines.appendTag(CloudworksNbtPatch.apply(Files.exists(file) ? readView(file) : null, entry.getCompoundTag("patch")));
        }
        expanded.setTag("lines", lines);
        return expanded;
    }

    private void prepareProjectionFingerprints(UUID owner, NBTTagCompound intent) {
        CloudworksData cloud = CloudworksData.read(intent.getCompoundTag("cloud"));
        IoReads batch = IO_READS.get();
        if (batch != null) batch.projections.put(intent, new ProjectionPlan(cloud));
        NBTTagCompound previous = new NBTTagCompound(), next = new NBTTagCompound();
        NBTTagCompound existed = new NBTTagCompound();
        for (Map.Entry<String, Path> entry : projectionPaths(owner, intent).entrySet()) {
            boolean exists = Files.exists(entry.getValue());
            existed.setBoolean(entry.getKey(), exists);
            if (exists) previous.setByteArray(entry.getKey(), diskFingerprint(entry.getValue()));
            next.setByteArray(entry.getKey(), projectionFingerprint(intent, entry.getKey(),
                    nextProjection(owner, intent, cloud, entry.getKey())));
        }
        intent.setBoolean("cloudExisted", existed.getBoolean("cloud"));
        intent.setTag("projectionExisted", existed);
        intent.setTag("previousFingerprints", previous);
        intent.setTag("nextFingerprints", next);
    }

    private void validateProjectionFingerprints(UUID owner, NBTTagCompound intent) {
        NBTTagCompound previous = intent.getCompoundTag("previousFingerprints");
        NBTTagCompound next = intent.getCompoundTag("nextFingerprints");
        NBTTagCompound existed = intent.getCompoundTag("projectionExisted");
        for (Map.Entry<String, Path> entry : projectionPaths(owner, intent).entrySet()) {
            String key = entry.getKey();
            if (!Files.exists(entry.getValue())) {
                if (existed.getBoolean(key)) throw dataError(null);
                continue;
            }
            byte[] actual = diskFingerprint(entry.getValue());
            if (!Arrays.equals(actual, next.getByteArray(key))
                    && (!existed.getBoolean(key) || !Arrays.equals(actual, previous.getByteArray(key))))
                throw dataError(null);
        }
    }

    private void writeIntentProjection(NBTTagCompound intent, String key, Path file,
            NBTTagCompound next, NBTTagCompound previous, boolean strict) {
        next = preservePortWatermark(file, next);
        if (intent.hasKey("previousFingerprints", 10)) {
            byte[] nextHash = intent.getCompoundTag("nextFingerprints").getByteArray(key);
            if (!Arrays.equals(projectionFingerprint(intent, key, next), nextHash)) throw dataError(null);
            if (!strict && intent.getCompoundTag("projectionExisted").getBoolean(key)
                    && Arrays.equals(nextHash, intent.getCompoundTag("previousFingerprints").getByteArray(key)))
                return;
        }
        writeProjection(file, next, previous, strict);
    }

    /**
     * Configures the four warehouse energy flows; persisted in cloud.dat.
     */
    public void setEnergyFlows(UUID owner, boolean receivingToCloud, boolean receivingToLines,
                                            boolean cloudToSending, boolean linesToSending) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.setEnergyFlows(owner, receivingToCloud, receivingToLines, cloudToSending, linesToSending);
            return;
        }
        synchronized (this) {
            CloudworksData cloud = requireEnabled(owner);
            CloudworksData previous = cloud.copyForRuntime();
            try {
                cloud.setEnergyFlows(receivingToCloud, receivingToLines, cloudToSending, linesToSending);
                publishTags(owner, cloud.write(), Collections.emptyList());
                drainInteraction();
            } catch (RuntimeException failure) {
                clouds.put(owner, previous);
                throw failure;
            }
        }
    }

    /**
     * Consumable expansion card (产线扩容卡): raises the owner's usable line ceiling
     * by one, up to the configured server limit, so the new
     * slot is immediately creatable. Returns the new unlocked line count, or -1
     * when the cloud is not available.
     */
    public int expandLineLimit(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.expandLineLimit(owner);
        }
        synchronized (this) {
            CloudworksData cloud;
            try {
                cloud = requireEnabled(owner);
            } catch (RuntimeException unavailable) {
                return -1;
            }
            int maximum = mekanism.cloudworks.common.config.CloudworksConfig.settings().maxLines;
            if (cloud.unlockedLines() >= maximum) return -2;
            int unlocked = cloud.unlockedLines() + 1;
            CloudworksData previous = cloud.copyForRuntime();
            try {
                cloud.setLimits(unlocked, Math.max(cloud.maxLines(), unlocked), cloud.workRate());
                publishTags(owner, cloud.write(), Collections.emptyList());
                drainInteraction();
                return unlocked;
            } catch (RuntimeException failure) {
                clouds.put(owner, previous);
                throw failure;
            }
        }
    }

    /**
     * Consumable expansion card (节点扩容卡): raises the owner's per-line machine
     * node ceiling by {@code amount}, clamped to the configured server limit.
     * Returns the new ceiling, -1 when unavailable, or -2 at the limit.
     */
    public int expandNodeLimit(UUID owner, int amount) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.expandNodeLimit(owner, amount);
        }
        synchronized (this) {
            CloudworksData cloud;
            try {
                cloud = requireEnabled(owner);
            } catch (RuntimeException unavailable) {
                return -1;
            }
            if (amount <= 0) throw new IllegalArgumentException("Expansion amount must be positive");
            int maximum = mekanism.cloudworks.common.config.CloudworksConfig.settings().maxMachineNodes;
            if (cloud.maxMachineNodes() >= maximum) return -2;
            int ceiling = (int) Math.min(maximum, (long) cloud.maxMachineNodes() + amount);
            CloudworksData previous = cloud.copyForRuntime();
            try {
                cloud.setMaxMachineNodes(ceiling);
                publishTags(owner, cloud.write(), Collections.emptyList());
                drainInteraction();
                return ceiling;
            } catch (RuntimeException failure) {
                clouds.put(owner, previous);
                throw failure;
            }
        }
    }

    public int increaseWorkRate(UUID owner) {
        if (parent == null) return domain(owner).increaseWorkRate(owner);
        synchronized (this) {
            CloudworksData cloud;
            try { cloud = requireEnabled(owner); } catch (RuntimeException unavailable) { return -1; }
            if (cloud.workRate() >= mekanism.cloudworks.common.config.CloudworksConfig.settings().maxWorkRate) return -2;
            int next = cloud.workRate() + 1;
            CloudworksData previous = cloud.copyForRuntime();
            try {
                cloud.setLimits(cloud.unlockedLines(), cloud.maxLines(), next);
                publishTags(owner, cloud.write(), Collections.emptyList());
                drainInteraction();
                return next;
            } catch (RuntimeException failure) {
                clouds.put(owner, previous);
                throw failure;
            }
        }
    }

    // GLM handoff §4.7: routeWarehouseEnergy / refreshLineEnergyCapacities /
    // settleServerEnergy / serverPower / flushEnergyRouting and the
    // energyRoutingDirty bookkeeping were deleted with their retired fixtures —
    // the Runtime's EnergySettlementSystem owns the four flow gates now.

    public synchronized int advanceSimulatedClock() {
        globalSimulatedTick = globalSimulatedTick == 24000 ? 1 : globalSimulatedTick + 1;
        return globalSimulatedTick;
    }

    /** Mirrors the overworld day cycle in the cloud clock's 1..24000 domain. */
    public synchronized int syncSimulatedClock(long worldTime) {
        globalSimulatedTick = (int) Math.floorMod(worldTime, 24000L) + 1;
        return globalSimulatedTick;
    }

    public synchronized int simulatedTick() {
        return globalSimulatedTick;
    }

    public CloudworksGenerationContext generationContext(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.generationContext(owner);
        }
        synchronized (this) {
            CloudworksData cloud = requireEnabled(owner);
            return new CloudworksGenerationContext(cloud.simulatedHeight(),
                    parent == null ? globalSimulatedTick : parent.globalSimulatedTick,
                    cloud.lavaHeatCards(), cloud.netherEnvironmentCard());
        }
    }

    public int expandSimulatedHeight(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.expandSimulatedHeight(owner);
        }
        synchronized (this) {
            CloudworksData cloud;
            try { cloud = requireEnabled(owner); } catch (RuntimeException unavailable) { return -1; }
            if (cloud.simulatedHeight() >= 256) return -2;
            CloudworksData previous = cloud.copyForRuntime();
            try {
                cloud.setSimulatedHeight(cloud.simulatedHeight() + 1);
                publishTags(owner, cloud.write(), Collections.emptyList());
                drainInteraction();
                return cloud.simulatedHeight();
            } catch (RuntimeException failure) {
                clouds.put(owner, previous);
                throw failure;
            }
        }
    }

    public int expandLavaHeat(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.expandLavaHeat(owner);
        }
        synchronized (this) {
            CloudworksData cloud;
            try { cloud = requireEnabled(owner); } catch (RuntimeException unavailable) { return -1; }
            if (cloud.lavaHeatCards() >= 7) return -2;
            CloudworksData previous = cloud.copyForRuntime();
            try {
                cloud.setLavaHeatCards(cloud.lavaHeatCards() + 1);
                publishTags(owner, cloud.write(), Collections.emptyList());
                drainInteraction();
                return cloud.lavaHeatCards();
            } catch (RuntimeException failure) {
                clouds.put(owner, previous);
                throw failure;
            }
        }
    }

    public boolean unlockNetherEnvironment(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.unlockNetherEnvironment(owner);
        }
        synchronized (this) {
            CloudworksData cloud;
            try { cloud = requireEnabled(owner); } catch (RuntimeException unavailable) { return false; }
            if (cloud.netherEnvironmentCard()) return false;
            CloudworksData previous = cloud.copyForRuntime();
            try {
                cloud.setNetherEnvironmentCard(true);
                publishTags(owner, cloud.write(), Collections.emptyList());
                drainInteraction();
                return true;
            } catch (RuntimeException failure) {
                clouds.put(owner, previous);
                throw failure;
            }
        }
    }

    /** One enabled-node charge per server tick; no disk publication here. */
    // GLM handoff §4.7: settleServerEnergy / serverPower(List) / flushEnergyRouting
    // deleted — the Runtime's energy settlement owns these paths now.

    /**
     * Single server-side entry for machine uploads (GPT design
     * CLOUDWORKS_MACHINE_UPLOAD_COMPONENT_DESIGN_20261002 §4.3). One source
     * reservation plus one durable intent carrying the machine-cache
     * projection, the line projection (the new node) and the transfer segment:
     * the machines have exactly one owner across any process death. A
     * RESERVED reservation without a durable intent rolls back (§5.1); a
     * durable intent only ever acknowledges, never re-creates the node or
     * re-deposits the cache (§5.2); repeated recovery of the same transferId
     * is idempotent success (§5.3).
     *
     * <p>Post-durable failures (the intent file exists) are commit-eventual:
     * the source is acknowledged and the intent replays at the next load.
     * Pre-durable failures roll everything back — source unchanged, no node,
     * no cache. Physical sources are accessed only by the server boundary;
     * the Runtime itself never acknowledges an inventory object.</p>
     */
    public CloudworksUploadResult uploadMachines(ICloudworksUploadSource source,
                                                              UUID lineId, ItemStack stack, int count) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(Objects.requireNonNull(source, "source").owner());
            return target.uploadMachines(source, lineId, stack, count);
        }
        synchronized (this) {
            return uploadMachines(source, lineId, stack, count, 0, 0, 0);
        }
    }

    /**
     * Coordinate form with the new node's editor placement (GUI supplies it later).
     */
    public CloudworksUploadResult uploadMachines(ICloudworksUploadSource source,
                                                              UUID lineId, ItemStack stack, int count, int x, int z, int dimensionId) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(Objects.requireNonNull(source, "source").owner());
            return target.uploadMachines(source, lineId, stack, count, x, z, dimensionId);
        }
        synchronized (this) {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(lineId, "lineId");
            requireLoaded();
            if (stack == null || stack.isEmpty())
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.MACHINE_UNREGISTERED);
            if (count <= 0) return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.COUNT_INVALID);
            UUID owner = source.owner();
            if (owner == null) return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.OWNER_MISMATCH);
            if (recoveryRequired(owner))
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.STORAGE_FAILURE);
            drainInteraction();   // order behind outstanding batches before reserving (bounded)
            CloudworksData cloud = getOrCreate(owner);
            if (!cloud.enabled()) return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.CLOUD_DISABLED);
            // GLM handoff §3.5.3: materialize the latest Runtime projection before
            // appending a node — publishing a pre-Runtime line object here would
            // overwrite worker progress with stale inventories.
            ProductionLineData line = line(lineId);
            if (line == null || !owner.equals(line.owner()) || !cloud.lineIds().contains(lineId)) {
                CloudworksPlayerDataManager otherDomain = parent.lineDomains.get(lineId);
                if (otherDomain != null && otherDomain != this)
                    return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.OWNER_MISMATCH);
                // A line that exists under another loaded owner is an owner
                // mismatch, not a miss: the source is bound to the wrong cloud.
                for (CloudworksData other : clouds.values())
                    if (!other.owner().equals(owner) && other.lineIds().contains(lineId))
                        return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.OWNER_MISMATCH);
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.LINE_NOT_FOUND);
            }
            if (line.enabled()) return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.LINE_ACTIVE);
            if (line.machines().size() >= Math.min(cloud.maxMachineNodes(),
                    mekanism.cloudworks.common.config.CloudworksConfig.settings().maxMachineNodes))
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.NODE_LIMIT);
            if (count > mekanism.cloudworks.common.config.CloudworksConfig.settings().maxMachinesPerNode)
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.COUNT_INVALID);
            if (!CloudworksRegistries.isRegistered(stack))
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.MACHINE_UNREGISTERED);
            UUID sourceId = source.sourceId();
            if (sourceId == null) return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.SOURCE_REFUSED);
            if (!source.canUpload(lineId, stack, count))
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.SOURCE_REFUSED);
            UUID transferId = UUID.randomUUID();
            CloudworksUploadReservation reservation;
            try {
                reservation = source.reserve(lineId, stack, count, transferId);
            } catch (RuntimeException reserveFailure) {
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.SOURCE_REFUSED);
            }
            if (!reservationCovers(reservation, sourceId, owner, lineId, transferId, stack, count)) {
                if (reservation != null) tryRollback(source, transferId);
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.SOURCE_REFUSED);
            }
            NBTTagCompound previousLine = line.write();
            mekanism.cloudworks.common.data.CloudworksMachineCache cache = machineCache(owner);
            NBTTagCompound previousCache = cache.write();
            CloudworksData previousCloud = cloud.copyForRuntime();
            UUID nodeId = UUID.randomUUID();
            try {
                ItemStack machineStack = reservation.machineStack();
                machineStack.setCount(count);
                cache.deposit(machineStack, count);
                MachineNodeData node = new MachineNodeData(nodeId, machineStack, x, z);
                node.setDimensionId(dimensionId);
                node.setMachineCount(count);
                node.setActive(true);
                node.setUploadOrigin(transferId, sourceId);
                line.machines().add(node);
                refreshLineUpgrades(cloud, line);
                NBTTagCompound transfer = new NBTTagCompound();
                transfer.setInteger("schema", 1);
                transfer.setString("transferId", transferId.toString());
                transfer.setString("sourceId", sourceId.toString());
                transfer.setString("lineId", lineId.toString());
                transfer.setString("nodeId", nodeId.toString());
                transfer.setString("sourceSlot", reservation.sourceSlot() == null ? "" : reservation.sourceSlot());
                transfer.setTag("machineStack", reservation.machineStack().writeToNBT(new NBTTagCompound()));
                transfer.setInteger("count", count);
                UUID debitPlayer = source.debitPlayer();
                if (debitPlayer != null) transfer.setString("debitPlayer", debitPlayer.toString());
                publishUploadIntent(owner, line.write(), cache.write(), transfer);
                // Durability before acknowledge (§4.3 step 7 -> 8) — bounded, never a
                // 10s stall: a live backlog parks the acknowledgement and the server
                // tick completes it once the intent is durable (GPT review).
                boolean drained = drainInteraction();
                if (drained && !recoveryRequired(owner)) {
                    settleSourceDebt(source.debitPlayer(), transferId, tryAcknowledge(source, transferId));
                    return CloudworksUploadResult.success(count, transferId);
                }
                if (!drained) {
                    // Alive but backlogged: the intent is queued and will apply in
                    // order; single ownership is guaranteed by the crash rules, and
                    // the parked acknowledgement stays strictly after durability.
                    pendingUploadAcks.put(transferId, new PendingUploadAck(source, owner));
                    return CloudworksUploadResult.success(count, transferId);
                }
                // Settled with a poison flag (e.g. a RuntimeException apply failure
                // on an empty dead queue): the probe disposition in the catch path
                // decides acknowledge versus rollback.
                throw new IllegalStateException("cloudworks.error.storage");
            } catch (RuntimeException failure) {
                lines.put(lineId, ProductionLineData.read(previousLine));
                machineCaches.put(owner, mekanism.cloudworks.common.data.CloudworksMachineCache.read(previousCache));
                clouds.put(owner, previousCloud);
                // The durable state alone decides the source disposition: an
                // intent file carrying this transferId (or an applied node)
                // guarantees the commit completes at replay — rolling the source
                // back would duplicate the machines.
                boolean durable;
                try {
                    durable = uploadDurable(owner, transferId);
                } catch (RuntimeException probeFailure) {
                    return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.STORAGE_FAILURE);
                }
                if (durable) {
                    settleSourceDebt(source.debitPlayer(), transferId, tryAcknowledge(source, transferId));
                    return CloudworksUploadResult.success(count, transferId);
                }
                tryRollback(source, transferId);
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.STORAGE_FAILURE);
            }
        }
    }

    /**
     * Merge form of the coordinated upload (GPT review round 3): adds machines
     * to an EXISTING node through the same durable transferId transaction —
     * machine-cache deposit, node count bump and the node's origin stamp ride
     * one intent, so the source-side acknowledge/rollback keeps exactly-one
     * owner semantics. The stack must be the node's exact variant.
     */
    public CloudworksUploadResult mergeMachines(ICloudworksUploadSource source,
                                                             UUID lineId, UUID nodeId, ItemStack stack, int count) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(Objects.requireNonNull(source, "source").owner());
            return target.mergeMachines(source, lineId, nodeId, stack, count);
        }
        synchronized (this) {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(lineId, "lineId");
            Objects.requireNonNull(nodeId, "nodeId");
            requireLoaded();
            if (stack == null || stack.isEmpty())
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.MACHINE_UNREGISTERED);
            if (count <= 0) return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.COUNT_INVALID);
            UUID owner = source.owner();
            if (owner == null) return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.OWNER_MISMATCH);
            if (recoveryRequired(owner))
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.STORAGE_FAILURE);
            drainInteraction();
            CloudworksData cloud = getOrCreate(owner);
            if (!cloud.enabled()) return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.CLOUD_DISABLED);
            // GLM handoff §3.5.3: same materialization rule as uploadMachines — the
            // merged node count must extend the latest Runtime state.
            ProductionLineData line = line(lineId);
            if (line == null || !owner.equals(line.owner()) || !cloud.lineIds().contains(lineId))
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.LINE_NOT_FOUND);
            if (line.enabled()) return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.LINE_ACTIVE);
            MachineNodeData target = null;
            for (MachineNodeData node : line.machines()) if (node.nodeId().equals(nodeId)) target = node;
            if (target == null) return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.LINE_NOT_FOUND);
            if (count > mekanism.cloudworks.common.config.CloudworksConfig.settings().maxMachinesPerNode - target.machineCount())
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.COUNT_INVALID);
            if (!CloudworksRegistries.isRegistered(stack))
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.MACHINE_UNREGISTERED);
            ItemStack template = target.machine();
            if (!ItemStack.areItemsEqual(template, stack) || !stripCaps(stack).equals(stripCaps(template)))
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.MACHINE_MISMATCH);
            UUID sourceId = source.sourceId();
            if (sourceId == null) return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.SOURCE_REFUSED);
            if (!source.canUpload(lineId, stack, count))
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.SOURCE_REFUSED);
            UUID transferId = UUID.randomUUID();
            CloudworksUploadReservation reservation;
            try {
                reservation = source.reserve(lineId, stack, count, transferId);
            } catch (RuntimeException reserveFailure) {
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.SOURCE_REFUSED);
            }
            if (!reservationCovers(reservation, sourceId, owner, lineId, transferId, stack, count)) {
                if (reservation != null) tryRollback(source, transferId);
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.SOURCE_REFUSED);
            }
            NBTTagCompound previousLine = line.write();
            mekanism.cloudworks.common.data.CloudworksMachineCache cache = machineCache(owner);
            NBTTagCompound previousCache = cache.write();
            CloudworksData previousCloud = cloud.copyForRuntime();
            long machineTotal = Math.addExact(target.machineCount(), count);
            try {
                ItemStack machineStack = reservation.machineStack();
                machineStack.setCount(count);
                cache.deposit(machineStack, count);
                target.setMachineCount(machineTotal);
                refreshLineUpgrades(cloud, line);
                // The node stamps the LATEST transaction that touched it: the durable
                // probe and the recovery rules key on this transferId.
                target.setUploadOrigin(transferId, sourceId);
                NBTTagCompound transfer = new NBTTagCompound();
                transfer.setInteger("schema", 1);
                transfer.setString("transferId", transferId.toString());
                transfer.setString("sourceId", sourceId.toString());
                transfer.setString("lineId", lineId.toString());
                transfer.setString("nodeId", nodeId.toString());
                transfer.setString("sourceSlot", reservation.sourceSlot() == null ? "" : reservation.sourceSlot());
                transfer.setTag("machineStack", reservation.machineStack().writeToNBT(new NBTTagCompound()));
                transfer.setInteger("count", count);
                transfer.setLong("machineTotal", machineTotal);
                UUID debitPlayer = source.debitPlayer();
                if (debitPlayer != null) transfer.setString("debitPlayer", debitPlayer.toString());
                publishUploadIntent(owner, line.write(), cache.write(), transfer);
                boolean drained = drainInteraction();
                if (drained && !recoveryRequired(owner)) {
                    settleSourceDebt(source.debitPlayer(), transferId, tryAcknowledge(source, transferId));
                    return CloudworksUploadResult.success(count, transferId);
                }
                if (!drained) {
                    pendingUploadAcks.put(transferId, new PendingUploadAck(source, owner));
                    return CloudworksUploadResult.success(count, transferId);
                }
                throw new IllegalStateException("cloudworks.error.storage");
            } catch (RuntimeException failure) {
                lines.put(lineId, ProductionLineData.read(previousLine));
                machineCaches.put(owner, mekanism.cloudworks.common.data.CloudworksMachineCache.read(previousCache));
                clouds.put(owner, previousCloud);
                boolean durable;
                try {
                    durable = uploadDurable(owner, transferId);
                } catch (RuntimeException probeFailure) {
                    return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.STORAGE_FAILURE);
                }
                if (durable) {
                    settleSourceDebt(source.debitPlayer(), transferId, tryAcknowledge(source, transferId));
                    return CloudworksUploadResult.success(count, transferId);
                }
                tryRollback(source, transferId);
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.STORAGE_FAILURE);
            }
        }
    }

    /**
     * Settles the ledger after an acknowledge: full settlement removes the entry, a shortfall reduces it.
     */
    private void settleSourceDebt(UUID debitPlayer, UUID transferId, long shortfall) {
        if (debitPlayer == null) return;
        if (shortfall == 0) removeSourceDebt(debitPlayer, transferId);
        else if (shortfall > 0) reduceSourceDebt(debitPlayer, transferId, (int) Math.min(shortfall, Integer.MAX_VALUE));
    }

    /**
     * Crash-recovery entry for sources that still hold a pending reservation
     * (design §5): the durable state alone decides the disposition. A pending
     * or applied intent carrying the transferId acknowledges the source —
     * never re-creating the node or re-depositing the cache; a transferId with
     * no durable trace rolls the reservation back. Idempotent: repeated calls
     * repeat only the acknowledge/rollback, which sources must tolerate.
     */
    public CloudworksUploadResult recoverUpload(ICloudworksUploadSource source, UUID transferId) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(Objects.requireNonNull(source, "source").owner());
            return target.recoverUpload(source, transferId);
        }
        synchronized (this) {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(transferId, "transferId");
            requireLoaded();
            UUID owner = source.owner();
            if (owner == null) return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.OWNER_MISMATCH);
            if (!recoveryRequired(owner)) drainInteraction();   // bounded settle of in-flight batches
            boolean durable;
            try {
                durable = uploadDurable(owner, transferId);
            } catch (RuntimeException probeFailure) {
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.STORAGE_FAILURE);
            }
            if (durable) {
                settleSourceDebt(source.debitPlayer(), transferId, tryAcknowledge(source, transferId));
                return CloudworksUploadResult.success((int) committedUploadCount(owner, transferId), transferId);
            }
            tryRollback(source, transferId);
            return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.NOT_COMMITTED);
        }
    }

    /**
     * Machines of the committed upload transaction, from the live lines; 0 when not loaded.
     */
    private long committedUploadCount(UUID owner, UUID transferId) {
        CloudworksData cloud = clouds.get(owner);
        if (cloud == null) return 0;
        for (ProductionLineData line : lines(owner))
            for (MachineNodeData node : line.machines())
                if (transferId.equals(node.transferId())) return node.machineCount();
        return 0;
    }

    /**
     * Server-tick completion of parked upload acknowledgements (main thread
     * only): once the queue is idle — or definitively dead — the disk probe
     * alone decides each disposition, acknowledge or rollback, exactly like
     * the synchronous path would have. Crash-safe: sources that never see the
     * completion re-present their reservation via {@link #recoverUpload}.
     */
    public int completePendingUploadAcknowledgements() {
        if (parent == null) {
            int total = 0;
            for (CloudworksPlayerDataManager target : domains.values()) total += target.completePendingUploadAcknowledgements();
            return total;
        }
        synchronized (this) {
            flushSourceDebtWrites();
            if (pendingUploadAcks.isEmpty() || root == null) return 0;
            CloudworksPersistenceQueue queue = persistence;
            boolean settled = queue == null || queue.backlogDepth() == 0 || queue.dead();
            if (!settled) return 0;
            int completed = 0;
            Iterator<Map.Entry<UUID, PendingUploadAck>> iterator = pendingUploadAcks.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, PendingUploadAck> entry = iterator.next();
                PendingUploadAck pending = entry.getValue();
                boolean durable = durableUploadTransfers.remove(entry.getKey());
                if (!durable) {
                    try {
                        durable = uploadDurable(pending.owner, entry.getKey());
                    } catch (RuntimeException probeFailure) {
                        continue;   // unreadable residue: retry on a later tick
                    }
                }
                if (durable) {
                    settleSourceDebt(pending.source.debitPlayer(), entry.getKey(),
                            tryAcknowledge(pending.source, entry.getKey()));
                } else tryRollback(pending.source, entry.getKey());
                iterator.remove();
                completed++;
            }
            return completed;
        }
    }

    /**
     * Parked upload acknowledgements awaiting durability; diagnostics and tests.
     */
    public int debugPendingUploadAckCount() {
        if (parent == null) {
            int total = 0;
            for (CloudworksPlayerDataManager target : domains.values()) total += target.debugPendingUploadAckCount();
            return total;
        }
        synchronized (this) {
            return pendingUploadAcks.size();
        }
    }

    /**
     * Applies the durable debit ledger of one PLAYER (GPT review 5): entries
     * belong to committed uploads whose physical deduction is outstanding. The
     * deduction is identity-validated — only stacks matching the recorded
     * exact machine are touched. A FULL settlement removes the entry; a
     * SHORTFALL reduces the entry to the remaining count and KEEPS it (retried
     * at the next login — the machines may come back — and permanently visible
     * for audit; never silently dropped while anything is outstanding).
     * Loads its own ledger file: independent of cloud enablement.
     * Returns the number of entries fully applied.
     */
    public int applySourceDebts(UUID player, List<ItemStack> slots) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(player);
            return target.applySourceDebts(player, slots);
        }
        synchronized (this) {
            Objects.requireNonNull(player);
            if (root == null) return 0;
            if (recoveryRequired(player)) throw dataError(recoveryCause(player));
            loadSourceDebts(player);
            List<NBTTagCompound> entries = sourceDebts.get(player);
            if (entries == null) return 0;
            int applied = 0;
            boolean changed = false;
            synchronized (entries) {
                Iterator<NBTTagCompound> iterator = entries.iterator();
                while (iterator.hasNext()) {
                    NBTTagCompound entry = iterator.next();
                    ItemStack machine = new ItemStack(entry.getCompoundTag("machine"));
                    long count = entry.getInteger("count");
                    if (machine.isEmpty() || count <= 0) {
                        iterator.remove();
                        changed = true;
                        continue;
                    }   // corrupt: drop
                    long missing = count;
                    for (int index = 0; index < slots.size() && missing > 0; index++) {
                        ItemStack stored = slots.get(index);
                        if (stored == null || stored.isEmpty() || !ItemStack.areItemsEqual(stored, machine)) continue;
                        NBTTagCompound storedTag = stored.getTagCompound();
                        NBTTagCompound wantedTag = machine.getTagCompound();
                        if (!java.util.Objects.equals(
                                storedTag == null ? new NBTTagCompound() : stripCapsCopy(storedTag),
                                wantedTag == null ? new NBTTagCompound() : stripCapsCopy(wantedTag))) continue;
                        long take = Math.min(missing, stored.getCount());
                        if (take > 0) {
                            stored.shrink((int) take);
                            missing -= take;
                        }
                    }
                    if (missing == 0) {
                        applied++;
                        iterator.remove();
                    } else {
                        // Keep the remainder durable: retried next login, auditable forever.
                        entry.setInteger("count", (int) Math.min(missing, Integer.MAX_VALUE));
                        System.err.println("CLOUDWORKS-DEBIT-RECOVERY shortfall transfer=" + entry.getString("transferId")
                                + " player=" + player + " missing=" + missing);
                        debugDebitRecoveryShortfalls.incrementAndGet();
                    }
                    changed = true;
                }
                if (changed) persistSourceDebts(player, entries);
            }
            return applied;
        }
    }

    /**
     * Ledger entries that could not be fully deducted at recovery; diagnostics only.
     */
    public long debugDebitRecoveryShortfalls() {
        return debugDebitRecoveryShortfalls.get();
    }

    /**
     * Live-player form used by the login hook; main thread only.
     */
    public void applySourceDebts(net.minecraft.entity.player.EntityPlayerMP player) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(player.getUniqueID());
            target.applySourceDebts(player);
            return;
        }
        synchronized (this) {
            applySourceDebts(player.getUniqueID(), player.inventory.mainInventory);
            player.inventory.markDirty();
            player.inventoryContainer.detectAndSendChanges();
        }
    }

    /**
     * Disk truth of one upload transaction (design §5): either the pending
     * intent file carries the transferId (written, apply incomplete — replay
     * finishes it) or a line file on disk holds a node with the transferId
     * (applied). The in-memory maps are deliberately ignored: publish
     * installs fresh objects before the batch is durable.
     */
    private boolean uploadDurable(UUID owner, UUID transferId) {
        Path pending = pendingIntentFile(owner);
        if (Files.isRegularFile(pending)) {
            NBTTagCompound intent = read(pending);
            if (intent.hasKey("transfer", 10) && transferId.equals(
                    UUID.fromString(intent.getCompoundTag("transfer").getString("transferId")))) return true;
        }
        Path production = root.resolve(owner.toString()).resolve("production");
        if (!Files.isDirectory(production)) return false;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(production, "*.dat")) {
            for (Path file : files) {
                if (file.getFileName().toString().endsWith(".runtime.dat")) continue;
                NBTTagList machines = read(file).getTagList("machines", 10);
                for (int i = 0; i < machines.tagCount(); i++) {
                    NBTTagCompound node = machines.getCompoundTagAt(i);
                    if (node.hasKey("transferId")
                            && transferId.equals(UUID.fromString(node.getString("transferId")))) return true;
                }
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException failure) {
            throw dataError(failure);
        }
        return false;
    }

    /**
     * Removes a node from a stopped line. Always possible: connected links are
     * dropped, machines return to the cache as available (the derived claim
     * dissolves) and the node's input/output caches are routed to the recycle
     * bin — progress is discarded with the node.
     */
    public boolean removeNode(UUID owner, UUID lineId, UUID nodeId) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.removeNode(owner, lineId, nodeId);
        }
        synchronized (this) {
            ProductionLineData line = owned(owner, lineId);
            if (line == null || line.enabled()) throw error("line_active");
            MachineNodeData found = null;
            for (MachineNodeData node : line.machines())
                if (node.nodeId().equals(nodeId)) {
                    found = node;
                    break;
                }
            if (found == null) return false;
            found.cancelOperation();
            // One durable intent carries the updated line AND the recycle deposit: a
            // process death between the two writes can no longer leave the resources in
            // both the node's caches and the bin. Any pre-submit failure rolls the
            // in-memory line and bin back so the removal stays retryable (P1).
            NBTTagCompound previousLine = line.write();
            mekanism.cloudworks.common.data.CloudworksRecycleBin bin = recycleBin(owner);
            NBTTagCompound previousBin = bin.write();
            CloudworksData cloud = requireEnabled(owner);
            CloudworksData previousCloud = cloud.copyForRuntime();
            try {
                long discarded = recycleIntoBin(bin, found.inputs(), found.outputs(), found.templates());
                line.links().removeIf(link -> link.sourceNode().equals(nodeId) || link.targetNode().equals(nodeId));
                line.machines().remove(found);
                boolean cancelledBlueprint = cancelMissingPending(owner, line);
                refreshLineUpgrades(cloud, line);
                publish0(owner, cloud.write(), Collections.singletonList(line.write()),
                        !bin.write().equals(previousBin) ? bin.write() : null, null, null, null,
                        true, false, cancelledBlueprint ? CloudworksPendingBlueprint.empty() : null);
                if (discarded > 0) pendingRecycleOverflow.add(owner);
                return true;
            } catch (RuntimeException failure) {
                lines.put(lineId, ProductionLineData.read(previousLine));
                recycleBins.put(owner, mekanism.cloudworks.common.data.CloudworksRecycleBin.read(previousBin));
                clouds.put(owner, previousCloud);
                throw failure;
            }
        }
    }

    public ProductionLineLink addLink(UUID owner, UUID lineId, UUID sourceNode, String sourcePort,
                                                   UUID targetNode, String targetPort) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.addLink(owner, lineId, sourceNode, sourcePort, targetNode, targetPort);
        }
        synchronized (this) {
            return addLink(owner, lineId, sourceNode, sourcePort, targetNode, targetPort, 0, 1D);
        }
    }

    public ProductionLineLink addLink(UUID owner, UUID lineId, UUID sourceNode, String sourcePort,
                                                   UUID targetNode, String targetPort, int priority, double ratio) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.addLink(owner, lineId, sourceNode, sourcePort, targetNode, targetPort, priority, ratio);
        }
        synchronized (this) {
            ProductionLineData line = owned(owner, lineId);
            if (line == null || line.enabled()) throw error("line_active");
            ProductionLineLink link = new ProductionLineLink(UUID.randomUUID(), sourceNode, sourcePort, targetNode, targetPort);
            link.setPriority(priority);
            link.setRatio(ratio);
            for (ProductionLineLink existing : line.links()) {
                if (existing.sourceNode().equals(sourceNode) && existing.targetNode().equals(targetNode)
                        && existing.sourcePort().equals(sourcePort) && existing.targetPort().equals(targetPort))
                    throw error("invalid_link");
            }
            line.links().add(link);
            CloudworksTopologyValidator.Result validation = CloudworksTopologyValidator.validate(line);
            if (!validation.valid()) {
                line.links().remove(link);
                throw error("invalid_link");
            }
            writeCheckpoint(line);
            changed(owner);
            return link;
        }
    }

    public boolean removeLink(UUID owner, UUID lineId, UUID linkId) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.removeLink(owner, lineId, linkId);
        }
        synchronized (this) {
            ProductionLineData line = owned(owner, lineId);
            if (line == null || line.enabled()) throw error("line_active");
            boolean removed = line.links().removeIf(link -> link.linkId().equals(linkId));
            if (removed) {
                writeCheckpoint(line);
                changed(owner);
            }
            return removed;
        }
    }

    private ProductionLineData owned(UUID owner, UUID id) {
        CloudworksData cloud = requireEnabled(owner);
        // GLM handoff §3.5.3: the live map may hold the pre-Runtime object while
        // the authoritative projection sits in the frozen guiLines tag. Reading
        // through line(id) materializes the latest Runtime state first, so an
        // edit can never publish stale progress/inventories over it.
        ProductionLineData line = line(id);
        return cloud.lineIds().contains(id) && line != null && owner.equals(line.owner()) ? line : null;
    }

    /**
     * All editor mutations are owner checked, stopped-line only, and restored if saving fails.
     * 升级层: any edit that lowers upgrade caps clamps the installed counts — the
     * clamped-away upgrades return to the cloud pool instead of vanishing.
     */
    public void edit(UUID owner, UUID id, java.util.function.Consumer<ProductionLineData> edit) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.edit(owner, id, edit);
            return;
        }
        synchronized (this) {
            ProductionLineData line = owned(owner, id);
            if (line == null || line.enabled()) throw error("line_active");
            NBTTagCompound previous = line.write();
            CloudworksData cloud = requireEnabled(owner);
            CloudworksData previousCloud = cloud.copyForRuntime();
            int speedBefore = line.speedUpgrades(), energyBefore = line.energyUpgrades();
            try {
                edit.accept(line);
                boolean cancelledBlueprint = cancelMissingPending(owner, line);
                mekanism.cloudworks.common.config.CloudworksConfig.Settings settings =
                        mekanism.cloudworks.common.config.CloudworksConfig.settings();
                if (line.machines().size() > Math.max(Math.min(cloud.maxMachineNodes(), settings.maxMachineNodes),
                        previous.getTagList("machines", 10).tagCount())) throw error("node_limit");
                Map<UUID, Long> previousCounts = new HashMap<>();
                NBTTagList previousMachines = previous.getTagList("machines", 10);
                for (int index = 0; index < previousMachines.tagCount(); index++) {
                    NBTTagCompound tag = previousMachines.getCompoundTagAt(index);
                    previousCounts.put(UUID.fromString(tag.getString("nodeId")), tag.getLong("machineCount"));
                }
                for (MachineNodeData node : line.machines())
                    if (node.machineCount() > Math.max(settings.maxMachinesPerNode,
                            previousCounts.getOrDefault(node.nodeId(), 0L))) throw error("machine_count_limit");
                double storedEnergy = line.energy();
                line.recomputeUpgradeLimits();
                settleLineEnergy(cloud, line, storedEnergy);
                CloudworksTopologyValidator.Result valid = CloudworksTopologyValidator.validate(line);
                if (!valid.valid()) throw error("invalid_link");
                int speedDropped = speedBefore - line.speedUpgrades();
                int energyDropped = energyBefore - line.energyUpgrades();
                returnUpgradesToPool(cloud, speedDropped, energyDropped);
                if (cancelledBlueprint) {
                    publish0(owner, cloud.write(), Collections.singletonList(line.write()), null,
                            null, null, null, true, false, CloudworksPendingBlueprint.empty());
                } else if (!previousCloud.writeCore().equals(cloud.writeCore())) {
                    // Counts and returned upgrades must be published together.
                    publishTags(owner, cloud.write(), Collections.singletonList(line.write()));
                } else {
                    writeCheckpoint(line);
                }
                changed(owner);
            } catch (RuntimeException failure) {
                lines.put(id, ProductionLineData.read(previous));
                clouds.put(owner, previousCloud);
                throw failure;
            }
        }
    }

    @Nullable
    private static TerminalEnergyPayload terminalEnergyPayload(ItemStack stack) {
        if (!mekanism.cloudworks.common.content.CloudworksTerminalSlots.acceptsEnergy(stack)) {
            return null;
        }
        IStrictEnergyHandler handler = EnergyCompatUtils.getStrictEnergyHandler(stack);
        if (handler == null) return null;
        double capacity = 0D;
        for (int container = 0; container < handler.getEnergyContainerCount(); container++) {
            double maximum = handler.getMaxEnergy(container);
            if (!Double.isFinite(maximum) || maximum <= 0D) return null;
            capacity = Math.min(Double.MAX_VALUE, capacity + maximum);
        }
        boolean unlimited = stack.getItem() instanceof ItemBlockEnergyCube cube
                && cube.getBaseTier(stack) == BaseTier.CREATIVE;
        return new TerminalEnergyPayload(capacity, unlimited);
    }

    private static final class TerminalEnergyPayload {
        private final double capacity;
        private final boolean unlimited;

        private TerminalEnergyPayload(double capacity, boolean unlimited) {
            this.capacity = capacity;
            this.unlimited = unlimited;
        }
    }

    /**
     * P1-4 单事务装卸（Mekanism 不变量对齐）: line count ±1 AND cloud pool ∓1
     * commit through ONE durable intent (line checkpoint + cloud core in the same
     * transaction) or roll back together — the old two-write flow duplicated
     * upgrades on install-crash and lost them on remove-crash. All checks
     * (pool ≥1, cap, stopped line, empty-energy-on-ENERGY-removal) run INSIDE the
     * transaction, eliminating the check-then-act window. The energy-capacity
     * recalculation is built into {@link ProductionLineData#setUpgrades}.
     */
    public void installUpgradeFromPool(UUID owner, UUID id, boolean speed) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.installUpgradeFromPool(owner, id, speed);
            return;
        }
        synchronized (this) {
            upgradeTransaction(owner, id, speed, true);
        }
    }

    public void removeUpgradeToPool(UUID owner, UUID id, boolean speed) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.removeUpgradeToPool(owner, id, speed);
            return;
        }
        synchronized (this) {
            upgradeTransaction(owner, id, speed, false);
        }
    }

    private void upgradeTransaction(UUID owner, UUID id, boolean speed, boolean install) {
        ProductionLineData line = owned(owner, id);
        if (line == null || line.enabled()) throw error("line_active");
        CloudworksData cloud = requireEnabled(owner);
        NBTTagCompound previousLine = line.write();
        CloudworksData previousCloud = cloud.copyForRuntime();
        try {
            refreshLineUpgrades(cloud, line);
            int count = speed ? line.speedUpgrades() : line.energyUpgrades();
            int maximum = speed ? line.maxSpeedUpgrades() : line.maxEnergyUpgrades();
            if (install) {
                if (cloud.upgradePool(speed) < 1) throw error("pool_empty");
                if (count >= maximum) throw error("not_found");
                line.setUpgrades(speed ? count + 1 : line.speedUpgrades(),
                        speed ? line.energyUpgrades() : count + 1);
                cloud.addUpgradePool(speed, -1);
            } else {
                if (count <= 0) throw error("not_found");
                if (!speed && line.energy() > 0D) throw error("energy_not_empty");
                line.setUpgrades(speed ? count - 1 : line.speedUpgrades(),
                        speed ? line.energyUpgrades() : count - 1);
                cloud.addUpgradePool(speed, 1);
            }
            // The upgrade cache and the line are one ownership transfer. Publish
            // both projections through the single durable-intent choke point so
            // a process death can only leave an unapplied intent or a replayable
            // line+pool state; it can never commit one side independently.
            publishTags(owner, cloud.write(), Collections.singletonList(line.write()));
            changed(owner);
        } catch (RuntimeException failure) {
            lines.put(id, ProductionLineData.read(previousLine));
            clouds.put(owner, previousCloud);
            throw failure;
        }
    }

    public ProductionLineData ownedLine(UUID owner, UUID id) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.ownedLine(owner, id);
        }
        synchronized (this) {
            return owned(owner, id);
        }
    }

    public CloudworksLineEditSession beginLineEdit(UUID owner, UUID id) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.beginLineEdit(owner, id);
        }
        synchronized (this) {
            ProductionLineData line = owned(owner, id);
            if (line == null) throw error("not_found");
            if (activeEdits.values().stream().anyMatch(edit -> edit.line.equals(id))) throw error("busy");
            boolean running = line.enabled();
            if (running) toggleLine(owner, id, false);
            line = owned(owner, id);
            mekanism.cloudworks.common.network.data.CloudworksLineDraft initial = CloudworksLineConfiguration.capture(line);
            initial.enabled = running;
            initial.maxNodes = Math.min(requireEnabled(owner).maxMachineNodes(),
                    mekanism.cloudworks.common.config.CloudworksConfig.settings().maxMachineNodes);
            CloudworksLineEditSession session = new CloudworksLineEditSession(owner, id, running, structuralLine(line.write()), initial);
            CloudworksPendingBlueprint waiting = pendingBlueprints.get(owner);
            if (waiting != null && waiting.line.equals(id)) session.pending = waiting;
            activeEdits.put(session.token, session);
            return session;
        }
    }

    public void updateLineDraft(CloudworksLineEditSession session, CloudworksLineDraft draft, int dimension) {
        if (parent == null) { domain(session.owner).updateLineDraft(session, draft, dimension); return; }
        synchronized (this) {
            requireEdit(session);
            CloudworksLineDraft next = CloudworksLineDraftCodec.copy(draft);
            Set<UUID> existing = new HashSet<>();
            session.current.machines.forEach(machine -> existing.add(machine.id));
            for (CloudworksLineDraft.Machine machine : next.machines)
                if (!existing.contains(machine.id)) machine.dimension = dimension;
            CloudworksPendingBlueprint previous = session.pending;
            boolean cancel = previous != null && !nodeIds(next).containsAll(previous.nodes());
            if (cancel) {
                CloudworksDraftGeometry.remove(next, previous.nodes());
                session.pending = null;
            }
            try {
                validateEditorDraft(session, next);
                CloudworksLineDraft unchanged = CloudworksLineDraftCodec.copy(session.current);
                next.machines.forEach(machine -> machine.missing = 0);
                unchanged.machines.forEach(machine -> machine.missing = 0);
                if (!cancel && Arrays.equals(CloudworksLineDraftCodec.encode(next), CloudworksLineDraftCodec.encode(unchanged))) return;
                if (cancel) commitLineEdit0(session, next, dimension, false);
                session.current = next;
            } catch (RuntimeException failure) { session.pending = previous; throw failure; }
        }
    }

    public CloudworksLineDraft lineEditSnapshot(CloudworksLineEditSession session) {
        if (parent == null) return domain(session.owner).lineEditSnapshot(session);
        synchronized (this) {
            CloudworksLineDraft snapshot = session.snapshot();
            if (session.pending != null) CloudworksBlueprintShortages.apply(snapshot, machineCache(session.owner),
                    reservationLines(session.owner, machineCache(session.owner)));
            return snapshot;
        }
    }

    public void precheckBlueprint(CloudworksLineEditSession session, CloudworksBlueprint blueprint) {
        if (parent == null) { domain(session.owner).precheckBlueprint(session, blueprint); return; }
        synchronized (this) {
            requireEdit(session);
            blueprint.validate();
            if (session.pending != null || pendingBlueprints.get(session.owner) != null) throw error("blueprint_pending");
            int maximum = Math.min(requireEnabled(session.owner).maxMachineNodes(),
                    mekanism.cloudworks.common.config.CloudworksConfig.settings().maxMachineNodes);
            if ((long) session.current.machines.size() + blueprint.draft().machines.size() > maximum) throw error("node_limit");
        }
    }

    public void placeBlueprint(CloudworksLineEditSession session, CloudworksBlueprint blueprint,
            int anchorX, int anchorY, int dimension) {
        if (parent == null) { domain(session.owner).placeBlueprint(session, blueprint, anchorX, anchorY, dimension); return; }
        synchronized (this) {
            precheckBlueprint(session, blueprint);
            CloudworksLineDraft placed = blueprint.remap(session.line, anchorX, anchorY);
            CloudworksLineDraft next = CloudworksLineDraftCodec.copy(session.current);
            boolean enough = requirements(session, placed).stream().noneMatch(requirement -> requirement.missing > 0);
            CloudworksPendingBlueprint waiting = enough ? null : new CloudworksPendingBlueprint(
                    UUID.randomUUID(), session.line, anchorX, anchorY, blueprint, placed);
            for (CloudworksLineDraft.Machine machine : placed.machines) {
                CloudworksLineDraft.Machine node = new CloudworksLineDraft.Machine(machine.id, machine.type,
                        machine.x, machine.y, enough ? machine.count : 0);
                node.dimension = machine.dimension; node.active = machine.active;
                node.waiting = waiting == null ? null : waiting.id;
                node.required = enough ? 0 : machine.count;
                node.missing = node.required;
                next.machines.add(node);
            }
            for (CloudworksLineDraft.Channel channel : placed.channels) {
                channel.waiting = waiting == null ? null : waiting.id;
                next.channels.add(channel);
            }
            next.links.addAll(placed.links);
            for (mekanism.api.qio.resource.QIOResourceDescriptor marker : placed.markers)
                if (next.markers.size() < 25 && !next.markers.contains(marker)) next.markers.add(marker);
            next.explicitRouting |= placed.explicitRouting || !placed.channels.isEmpty();
            next.receivingFlow = placed.receivingFlow;
            next.sendingFlow = placed.sendingFlow;
            CloudworksPendingBlueprint previous = session.pending;
            session.pending = waiting;
            try {
                normalizeUpgradeLimits(next);
                validateEditorDraft(session, next);
                commitLineEdit0(session, next, dimension, false);
                session.current = next;
            } catch (RuntimeException failure) { session.pending = previous; throw failure; }
        }
    }

    public static final class BlueprintRequirement {
        public final ItemStack stack;
        public final long required, missing;
        public BlueprintRequirement(ItemStack stack, long required, long missing) {
            this.stack = stack.copy(); this.required = required; this.missing = missing;
        }
    }

    public List<BlueprintRequirement> blueprintRequirements(CloudworksLineEditSession session) {
        if (parent == null) return domain(session.owner).blueprintRequirements(session);
        synchronized (this) {
            requireEdit(session);
            if (session.pending == null) throw error("not_found");
            return requirements(session, session.pending.placed());
        }
    }

    private List<BlueprintRequirement> requirements(CloudworksLineEditSession session, CloudworksLineDraft group) {
        CloudworksMachineCache cache = machineCache(session.owner);
        return CloudworksBlueprintShortages.requirements(group, session.current, cache, reservationLines(session.owner, cache));
    }

    public boolean fillBlueprint(CloudworksLineEditSession session, boolean cancel, int dimension) {
        if (parent == null) return domain(session.owner).fillBlueprint(session, cancel, dimension);
        synchronized (this) {
            requireEdit(session);
            CloudworksPendingBlueprint waiting = session.pending;
            if (waiting == null) throw error("not_found");
            if (!cancel && blueprintRequirements(session).stream().anyMatch(requirement -> requirement.missing > 0)) return false;
            CloudworksLineDraft next = CloudworksLineDraftCodec.copy(session.current);
            if (cancel) CloudworksDraftGeometry.remove(next, waiting.nodes());
            else {
                Map<UUID, CloudworksLineDraft.Machine> desired = new HashMap<>();
                waiting.placed().machines.forEach(machine -> desired.put(machine.id, machine));
                for (CloudworksLineDraft.Machine machine : next.machines) if (waiting.id.equals(machine.waiting)) {
                    CloudworksLineDraft.Machine original = desired.get(machine.id);
                    if (original == null) throw error("editor");
                    machine.count = original.count; machine.active = original.active; machine.waiting = null;
                    machine.required = machine.missing = 0;
                }
                for (CloudworksLineDraft.Channel channel : next.channels)
                    if (waiting.id.equals(channel.waiting)) channel.waiting = null;
            }
            session.pending = null;
            try {
                normalizeUpgradeLimits(next);
                validateEditorDraft(session, next);
                commitLineEdit0(session, next, dimension, false);
                session.current = next;
            } catch (RuntimeException failure) { session.pending = waiting; throw failure; }
            return true;
        }
    }

    private void requireEdit(CloudworksLineEditSession session) {
        if (session == null || activeEdits.get(session.token) != session) throw error("stale");
        ProductionLineData line = owned(session.owner, session.line);
        if (line == null || line.enabled() || !session.baseline.equals(structuralLine(line.write()))) throw error("stale");
    }

    private boolean cancelMissingPending(UUID owner, ProductionLineData line) {
        CloudworksPendingBlueprint pending = pendingBlueprints.get(owner);
        if (pending == null || !pending.line.equals(line.lineId())) return false;
        Set<UUID> present = new HashSet<>();
        line.machines().forEach(node -> present.add(node.nodeId()));
        line.channels().forEach(node -> present.add(node.id()));
        Set<UUID> waiting = pending.nodes();
        if (present.containsAll(waiting)) return false;
        line.machines().removeIf(node -> waiting.contains(node.nodeId()));
        line.channels().removeIf(node -> waiting.contains(node.id()));
        line.links().removeIf(link -> waiting.contains(link.sourceNode()) || waiting.contains(link.targetNode()));
        return true;
    }

    private static Set<UUID> nodeIds(CloudworksLineDraft draft) {
        Set<UUID> ids = new HashSet<>();
        draft.machines.forEach(machine -> ids.add(machine.id));
        draft.channels.forEach(channel -> ids.add(channel.id));
        return ids;
    }

    private static void normalizeUpgradeLimits(CloudworksLineDraft draft) {
        draft.maxSpeed = mekanism.cloudworks.common.upgrade.CloudworksUpgradeLimits.capacity(
                mekanism.common.Upgrade.SPEED, draft.machines.stream().mapToLong(machine -> machine.count));
        draft.maxEnergy = mekanism.cloudworks.common.upgrade.CloudworksUpgradeLimits.capacity(
                mekanism.common.Upgrade.ENERGY, draft.machines.stream().mapToLong(machine -> machine.count));
        draft.speed = Math.min(draft.speed, draft.maxSpeed); draft.energy = Math.min(draft.energy, draft.maxEnergy);
    }

    private void validateEditorDraft(CloudworksLineEditSession session, CloudworksLineDraft draft) {
        requireEdit(session);
        if (draft == null || !session.line.equals(draft.line)) throw error("editor");
        checkedName(draft.name);
        CloudworksData cloud = requireEnabled(session.owner);
        ProductionLineData existing = owned(session.owner, session.line);
        int limit = Math.min(cloud.maxMachineNodes(), mekanism.cloudworks.common.config.CloudworksConfig.settings().maxMachineNodes);
        if (draft.machines.size() > Math.max(limit, existing.machines().size())) throw error("node_limit");
        Set<UUID> ids = new HashSet<>(), waitingIds = session.pending == null ? Collections.emptySet() : session.pending.nodes();
        Map<UUID, CloudworksLineDraft.Machine> before = new HashMap<>();
        session.current.machines.forEach(machine -> before.put(machine.id, machine));
        Map<UUID, CloudworksLineDraft.Machine> pendingMachines = new HashMap<>();
        if (session.pending != null) session.pending.placed().machines.forEach(machine -> pendingMachines.put(machine.id, machine));
        Map<mekanism.cloudworks.common.registry.MachineKey, Long> claims = new HashMap<>();
        CloudworksMachineCache cache = machineCache(session.owner);
        for (CloudworksLineDraft.Machine machine : draft.machines) {
            CloudworksBlueprint.coordinate(machine.x); CloudworksBlueprint.coordinate(machine.y);
            if (machine.id == null || !ids.add(machine.id) || !CloudworksRegistries.isRegistered(machine.type)
                    || machine.count < 0 || (machine.count == 0) != (machine.waiting != null)) throw error("editor");
            if (waitingIds.contains(machine.id) != (machine.waiting != null)
                    || machine.waiting != null && (session.pending == null || !machine.waiting.equals(session.pending.id)))
                throw error("editor");
            if (machine.waiting != null) {
                CloudworksLineDraft.Machine desired = pendingMachines.get(machine.id);
                if (desired == null) throw error("editor");
                machine.required = desired.count;
                machine.missing = Math.min(machine.required, Math.max(0, machine.missing));
            } else machine.required = machine.missing = 0;
            CloudworksLineDraft.Machine original = before.get(machine.id);
            if (original != null && (!CloudworksRegistries.identityKey(original.type).equals(CloudworksRegistries.identityKey(machine.type))
                    || original.dimension != machine.dimension)) throw error("machine");
            long maximum = mekanism.cloudworks.common.config.CloudworksConfig.settings().maxMachinesPerNode;
            if (machine.count > maximum && (original == null || machine.count > original.count)) throw error("machine_count_limit");
            int type = cache.typeIndex(machine.type);
            if (machine.count > 0) {
                if (type < 0) throw error("machine_unavailable");
                claims.merge(CloudworksRegistries.identityKey(machine.type), machine.count, Math::addExact);
            }
        }
        for (CloudworksLineDraft.Channel channel : draft.channels) {
            CloudworksBlueprint.coordinate(channel.x); CloudworksBlueprint.coordinate(channel.y);
            if (channel.id == null || !ids.add(channel.id) || channel.mode == null) throw error("editor");
            if (waitingIds.contains(channel.id) != (channel.waiting != null)
                    || channel.waiting != null && (session.pending == null || !channel.waiting.equals(session.pending.id)))
                throw error("editor");
            Set<mekanism.api.qio.resource.QIOResourceDescriptor> resources = new HashSet<>();
            long sum = 0;
            for (CloudworksLineDraft.Resource resource : channel.resources) {
                if (resource.amount <= 0 || resource.descriptor == null || !resource.descriptor.isResolved()
                        || !resources.add(resource.descriptor)) throw error("editor");
                sum = Math.addExact(sum, resource.amount);
            }
        }
        Set<UUID> wires = new HashSet<>();
        for (CloudworksLineDraft.Wire wire : draft.links)
            if (!wires.add(wire.id) || !ids.contains(wire.source) || !ids.contains(wire.target)
                    || !Double.isFinite(wire.ratio) || wire.ratio < 0 || wire.ratio > 1
                    || wire.filters.stream().anyMatch(resource -> resource == null || !resource.isResolved())
                    || new HashSet<>(wire.filters).size() != wire.filters.size()) throw error("editor_link_data_invalid");
        if (draft.markers.size() > 25 || new HashSet<>(draft.markers).size() != draft.markers.size()
                || draft.markers.stream().anyMatch(resource -> resource == null || !resource.isResolved())) throw error("editor");
        for (ProductionLineData line : reservationLines(session.owner, cache)) {
            if (line.lineId().equals(session.line)) continue;
            for (MachineNodeData machine : line.machines()) {
                int type = cache.typeIndex(machine.machine());
                if (type >= 0) claims.merge(CloudworksRegistries.identityKey(machine.machine()), machine.machineCount(), Math::addExact);
            }
        }
        Map<mekanism.cloudworks.common.registry.MachineKey, Long> holdings = CloudworksBlueprintShortages.holdings(cache);
        for (Map.Entry<mekanism.cloudworks.common.registry.MachineKey, Long> claim : claims.entrySet())
            if (claim.getValue() > holdings.getOrDefault(claim.getKey(), 0L)) throw error("machine_unavailable");
        CloudworksTopologyValidator.Result topology = validateEditorTopology(session.owner, draft);
        if (!topology.valid()) throw error(topology.errorKey());
        int maxSpeed = mekanism.cloudworks.common.upgrade.CloudworksUpgradeLimits.capacity(
                mekanism.common.Upgrade.SPEED, draft.machines.stream().mapToLong(machine -> machine.count));
        int maxEnergy = mekanism.cloudworks.common.upgrade.CloudworksUpgradeLimits.capacity(
                mekanism.common.Upgrade.ENERGY, draft.machines.stream().mapToLong(machine -> machine.count));
        if (draft.speed < 0 || draft.energy < 0 || draft.speed > Math.max(maxSpeed, existing.speedUpgrades())
                || draft.energy > Math.max(maxEnergy, existing.energyUpgrades())
                || draft.speed - existing.speedUpgrades() > cloud.upgradePool(true)
                || draft.energy - existing.energyUpgrades() > cloud.upgradePool(false)) throw error("pool_empty");
        draft.maxNodes = limit;
        draft.maxMachinesPerNode = mekanism.cloudworks.common.config.CloudworksConfig.settings().maxMachinesPerNode;
        draft.maxSpeed = maxSpeed; draft.maxEnergy = maxEnergy;
    }

    private static CloudworksTopologyValidator.Result validateEditorTopology(UUID owner, CloudworksLineDraft draft) {
        ProductionLineData line = new ProductionLineData(draft.line, owner);
        line.setExplicitRouting(draft.explicitRouting || !draft.channels.isEmpty());
        for (CloudworksLineDraft.Machine value : draft.machines) {
            MachineNodeData node = new MachineNodeData(value.id, value.type, value.x, value.y);
            node.setMachineCount(value.count);
            node.setActive(value.active);
            node.setWaitingBlueprint(value.waiting);
            node.setWaitingCount(value.required);
            line.machines().add(node);
        }
        for (CloudworksLineDraft.Channel value : draft.channels) {
            CloudworksChannelNode node = new CloudworksChannelNode(value.id, value.mode, Long.MAX_VALUE);
            node.setPosition(value.x, value.y);
            node.setEnabled(value.active);
            node.setWaitingBlueprint(value.waiting);
            for (CloudworksLineDraft.Resource resource : value.resources)
                node.setResource(resource.descriptor, resource.amount);
            line.channels().add(node);
        }
        for (CloudworksLineDraft.Wire value : draft.links) {
            ProductionLineLink link = new ProductionLineLink(value.id, value.source, value.sourcePort,
                    value.target, value.targetPort);
            link.setEnabled(value.enabled);
            link.setPriority(value.priority);
            link.setRatio(value.ratio);
            link.setResourceFilters(value.filters);
            line.links().add(link);
        }
        return CloudworksTopologyValidator.validate(line);
    }

    /** Unexpected container closure discards only the local edit, never replays its initial configuration. */
    public boolean abortLineEdit(CloudworksLineEditSession session) {
        if (session == null) return false;
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(session.owner);
            return target.abortLineEdit(session);
        }
        synchronized (this) {
            if (activeEdits.remove(session.token) != session) return true;
            if (!session.wasRunning) return true;
            ProductionLineData current = owned(session.owner, session.line);
            if (current == null || current.enabled()) return true;
            // Do not restart a line whose configuration was changed outside this edit session.
            if (!session.baseline.equals(structuralLine(current.write()))) return false;
            return toggleLine(session.owner, session.line, true);
        }
    }

    public void commitLineEdit(CloudworksLineEditSession session,
            mekanism.cloudworks.common.network.data.CloudworksLineDraft draft, int dimension) {
        if (session == null) return;
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(session.owner);
            target.commitLineEdit(session, draft, dimension);
            return;
        }
        synchronized (this) {
            commitLineEdit0(session, draft, dimension, true);
        }
    }

    private void commitLineEdit0(CloudworksLineEditSession session, CloudworksLineDraft draft, int dimension, boolean finish) {
            if (activeEdits.get(session.token) != session || !session.line.equals(draft.line)) throw error("stale");
            validateEditorDraft(session, draft);
            UUID owner = session.owner;
            ProductionLineData current = owned(owner, session.line);
            if (current == null || current.enabled()
                    || !session.baseline.equals(structuralLine(current.write()))) throw error("stale");
            if (draft.markers.size() > 25 || new HashSet<>(draft.markers).size() != draft.markers.size())
                throw error("editor");
            ProductionLineData next = ProductionLineData.read(current.write());
            CloudworksData cloud = CloudworksData.read(requireEnabled(owner).write());
            if (draft.runWhileOwnerOffline && !cloud.offlineProcessingUnlocked()) throw error("offline_locked");
            if (draft.runWhileOwnerOffline && !current.runWhileOwnerOffline()
                    && !mekanism.cloudworks.common.config.CloudworksConfig.settings().offlineProcessingEnabled)
                throw error("offline_disabled");
            CloudworksRecycleBin bin = CloudworksRecycleBin.read(recycleBin(owner).write());
            int nodeLimit = Math.min(cloud.maxMachineNodes(),
                    mekanism.cloudworks.common.config.CloudworksConfig.settings().maxMachineNodes);
            if (draft.machines.size() > Math.max(nodeLimit, current.machines().size())) throw error("node_limit");
            CloudworksMachineCache cache = machineCache(owner);
            Map<UUID, MachineNodeData> oldMachines = new HashMap<>();
            for (MachineNodeData machine : next.machines()) oldMachines.put(machine.nodeId(), machine);
            Map<UUID, mekanism.cloudworks.common.data.CloudworksChannelNode> oldChannels = new HashMap<>();
            for (mekanism.cloudworks.common.data.CloudworksChannelNode channel : next.channels()) oldChannels.put(channel.id(), channel);
            Map<UUID, ProductionLineLink> oldLinks = new HashMap<>();
            for (ProductionLineLink link : next.links()) oldLinks.put(link.linkId(), link);
            next.machines().clear();
            next.channels().clear();
            next.links().clear();
            Set<UUID> ids = new HashSet<>();
            Map<Integer, Long> wanted = new HashMap<>();
            for (mekanism.cloudworks.common.network.data.CloudworksLineDraft.Machine value : draft.machines) {
                if (!ids.add(value.id) || value.count < 0 || value.count == 0 && value.waiting == null
                        || oldChannels.containsKey(value.id)) throw error("editor");
                int type = cache.typeIndex(value.type);
                if (value.count > 0 && type < 0 || !CloudworksRegistries.isRegistered(value.type)) throw error("machine");
                MachineNodeData node = oldMachines.remove(value.id);
                if (value.count > mekanism.cloudworks.common.config.CloudworksConfig.settings().maxMachinesPerNode
                        && (node == null || value.count > node.machineCount())) throw error("machine_count_limit");
                if (node != null && !ItemStack.areItemsEqual(node.machine(), value.type)) throw error("machine");
                if (node == null) {
                    node = new MachineNodeData(value.id, value.type, value.x, value.y);
                    node.setDimensionId(value.dimension);
                    node.setPooledClaim(true);
                } else if (node.machineCount() != value.count) {
                    node.cancelOperation();
                    node.setProgress(0, 1);
                    node.setPooledClaim(true);
                }
                node.setMachineCount(value.count);
                node.setWaitingBlueprint(value.waiting);
                node.setWaitingCount(value.required);
                node.setPosition(value.x, value.y);
                node.setActive(value.active && value.count > 0);
                next.machines().add(node);
                if (value.count > 0) wanted.merge(type, value.count, Math::addExact);
            }
            for (ProductionLineData other : lines(owner)) {
                if (other.lineId().equals(next.lineId())) continue;
                for (MachineNodeData node : other.machines()) {
                    int type = cache.typeIndex(node.machine());
                    if (type >= 0) wanted.merge(type, node.machineCount(), Math::addExact);
                }
            }
            List<CloudworksMachineCache.Entry> entries = cache.entries();
            for (Map.Entry<Integer, Long> demand : wanted.entrySet())
                if (demand.getValue() > entries.get(demand.getKey()).count()) throw error("machine_unavailable");
            boolean discarded = false;
            for (MachineNodeData removed : oldMachines.values()) {
                removed.cancelOperation();
                discarded |= recycleIntoBin(bin, removed.inputs(), removed.outputs(), removed.templates()) > 0;
            }
            for (mekanism.cloudworks.common.network.data.CloudworksLineDraft.Channel value : draft.channels) {
                if (!ids.add(value.id) || current.machines().stream().anyMatch(n -> n.nodeId().equals(value.id))) throw error("editor");
                mekanism.cloudworks.common.data.CloudworksChannelNode node = oldChannels.remove(value.id);
                if (node != null && node.mode() != value.mode) throw error("editor");
                if (node == null) node = new mekanism.cloudworks.common.data.CloudworksChannelNode(value.id, value.mode, Long.MAX_VALUE);
                for (mekanism.api.qio.resource.QIOResourceDescriptor descriptor : new ArrayList<>(node.resources().keySet()))
                    node.setResource(descriptor, 0);
                Set<mekanism.api.qio.resource.QIOResourceDescriptor> resources = new HashSet<>();
                for (mekanism.cloudworks.common.network.data.CloudworksLineDraft.Resource resource : value.resources) {
                    if (!resources.add(resource.descriptor) || resource.amount <= 0) throw error("editor");
                    node.setResource(resource.descriptor, resource.amount);
                }
                node.setPosition(value.x, value.y);
                node.setEnabled(value.active);
                node.setWaitingBlueprint(value.waiting);
                next.channels().add(node);
            }
            for (mekanism.cloudworks.common.data.CloudworksChannelNode removed : oldChannels.values())
                for (Map.Entry<mekanism.api.qio.resource.QIOResourceDescriptor, Long> resource : removed.buffer().snapshot().entrySet())
                    discarded |= bin.deposit(resource.getKey(), resource.getValue()) > 0;
            Set<UUID> linkIds = new HashSet<>();
            Set<String> endpoints = new HashSet<>();
            for (mekanism.cloudworks.common.network.data.CloudworksLineDraft.Wire value : draft.links) {
                if (!linkIds.add(value.id)) throw error("editor_duplicate_link");
                if (!ids.contains(value.source) || !ids.contains(value.target)) throw error("editor_link_unknown_node");
                if (!endpoints.add(value.source + "|" + value.sourcePort + "|" + value.target + "|" + value.targetPort))
                    throw error("editor_duplicate_connection");
                ProductionLineLink link = new ProductionLineLink(value.id, value.source, value.sourcePort, value.target, value.targetPort);
                link.setEnabled(value.enabled);
                link.setPriority(value.priority);
                link.setRatio(value.ratio);
                link.setResourceFilters(value.filters);
                ProductionLineLink old = oldLinks.get(value.id);
                if (old != null && old.sourceNode().equals(value.source) && old.targetNode().equals(value.target)
                        && old.sourcePort().equals(value.sourcePort) && old.targetPort().equals(value.targetPort)
                        && old.ratio() == value.ratio && old.resourceFilters().equals(value.filters))
                    link.setRatioRemainder(old.ratioRemainder());
                next.links().add(link);
            }
            if (draft.speed < 0 || draft.speed > next.maxSpeedUpgrades()
                    || draft.energy < 0 || draft.energy > next.maxEnergyUpgrades()) throw error("upgrade");
            int speedDelta = draft.speed - current.speedUpgrades(), energyDelta = draft.energy - current.energyUpgrades();
            if (speedDelta > cloud.upgradePool(true) || energyDelta > cloud.upgradePool(false)) throw error("pool_empty");
            cloud.addUpgradePool(true, -speedDelta);
            cloud.addUpgradePool(false, -energyDelta);
            next.setName(checkedName(draft.name));
            next.setUpgrades(draft.speed, draft.energy);
            settleLineEnergy(cloud, next, current.energy());
            next.setEnergyFlows(draft.receivingFlow, draft.sendingFlow);
            next.setRunWhileOwnerOffline(draft.runWhileOwnerOffline);
            next.setExplicitRouting(draft.explicitRouting || !next.channels().isEmpty());
            next.dynamicResourceDisplayList().clear();
            for (mekanism.api.qio.resource.QIOResourceDescriptor marker : draft.markers) {
                if (!marker.isResolved()) throw error("editor");
                next.dynamicResourceDisplayList().add(marker.write().toString());
            }
            if (finish) {
                CloudworksTopologyValidator.Result topology = CloudworksTopologyValidator.validate(next);
                if (!topology.valid()) throw error(topology.errorKey());
                if (draft.enabled && next.machines().isEmpty()) throw error("editor_active_line_requires_machine");
            }
            next.setEnabled(finish && draft.enabled);
            CloudworksPendingBlueprint durablePending = pendingBlueprints.get(owner);
            NBTTagCompound pendingWrite;
            if (session.pending != null) pendingWrite = session.pending.write();
            else if (durablePending != null && !durablePending.line.equals(session.line)) pendingWrite = durablePending.write();
            else pendingWrite = CloudworksPendingBlueprint.empty();
            publish0(owner, cloud.write(), Collections.singletonList(next.write()), bin.write(), null, null, null,
                    true, false, pendingWrite);
            recycleBins.put(owner, bin);
            if (discarded) pendingRecycleOverflow.add(owner);
            if (finish) activeEdits.remove(session.token);
            else session.baseline = structuralLine(next.write());
    }

    /** Explicit recovery atomically clears a node and moves resources to recycle. */
    public void returnCache(UUID owner, UUID id, UUID nodeId) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.returnCache(owner, id, nodeId);
            return;
        }
        synchronized (this) {
            ProductionLineData line = owned(owner, id);
            if (line == null || line.enabled()) throw error("line_active");
            MachineNodeData node = null;
            for (MachineNodeData candidate : line.machines())
                if (candidate.nodeId().equals(nodeId)) node = candidate;
            if (node == null) throw error("not_found");
            CloudworksRecycleBin bin = recycleBin(owner);
            NBTTagCompound previousLine = line.write(), previousBin = bin.write();
            try {
                node.cancelOperation();
                long discarded = recycleIntoBin(bin, node.inputs(), node.outputs(), node.templates());
                node.inputs().clear();
                node.outputs().clear();
                node.templates().clear();
                node.setProgress(0, 1);
                node.setState("OFF");
                node.setActive(false);
                publishTags(owner, clouds.get(owner).write(), Collections.singletonList(line.write()), bin.write());
                for (CloudworksLineEditSession edit : activeEdits.values()) if (edit.line.equals(id)) {
                    edit.baseline = structuralLine(line.write());
                }
                changed(owner);
                if (discarded > 0) pendingRecycleOverflow.add(owner);
            } catch (RuntimeException failure) {
                lines.put(id, ProductionLineData.read(previousLine));
                recycleBins.put(owner, CloudworksRecycleBin.read(previousBin));
                throw failure;
            }
        }
    }

    private CloudworksData requireEnabled(UUID owner) {
        CloudworksData cloud = getOrCreate(owner);
        if (!cloud.enabled()) throw error("disabled");
        return cloud;
    }

    /**
     * Bounded GUI data: never send machine inventory, journals, or endpoint credentials.
     */
    public NBTTagCompound page(UUID owner, int requestedOffset) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.page(owner, requestedOffset);
        }
        synchronized (this) {
            CloudworksData cloud = requireEnabled(owner);
            NBTTagCompound tag = new NBTTagCompound();
            int total = cloud.lineIds().size();
            int offset = Math.max(0, Math.min(requestedOffset, Math.max(0, total - 1)));
            offset = offset / PAGE_SIZE * PAGE_SIZE;
            tag.setInteger("offset", offset);
            tag.setInteger("total", total);
            mekanism.cloudworks.common.config.CloudworksConfig.Settings settings =
                    mekanism.cloudworks.common.config.CloudworksConfig.settings();
            tag.setInteger("unlocked", Math.min(cloud.unlockedLines(), settings.maxLines));
            tag.setInteger("maximum", settings.maxLines);
            tag.setInteger("workRate", Math.min(cloud.workRate(), settings.maxWorkRate));
            tag.setDouble("energy", cloud.energy());
            tag.setDouble("maxEnergy", cloud.maxEnergy());
            tag.setBoolean("unlimited", cloud.unlimitedEnergy());
            // 能量层视图: 中继存量 + owner 级两门
            tag.setDouble("receivingEnergy", cloud.receivingEnergy());
            tag.setDouble("sendingEnergy", cloud.sendingEnergy());
            tag.setDouble("receivingMaxEnergy", cloud.receivingEnergyCapacity());
            tag.setDouble("sendingMaxEnergy", cloud.sendingEnergyCapacity());
            tag.setBoolean("flowReceivingToCloud", cloud.energyFlowReceivingToCloud());
            tag.setBoolean("flowCloudToSending", cloud.energyFlowCloudToSending());
            // 升级层视图: 云端升级池
            tag.setInteger("speedUpgradePool", cloud.upgradePool(true));
            tag.setInteger("energyUpgradePool", cloud.upgradePool(false));
            tag.setLong("revision", revision(owner));
            NBTTagList entries = new NBTTagList();
            for (int i = offset; i < Math.min(total, offset + PAGE_SIZE); i++) {
                UUID id = cloud.lineIds().get(i);
                ProductionLineData line = line(id);
                NBTTagCompound entry = new NBTTagCompound();
                entry.setString("id", id.toString());
                entry.setBoolean("available", line != null);
                if (line != null) {
                    entry.setString("name", line.name().substring(0, Math.min(64, line.name().length())));
                    entry.setBoolean("enabled", line.enabled());
                    entry.setBoolean("deletable", canDelete(line));
                    entry.setInteger("nodes", line.machines().size());
                    entry.setDouble("energy", line.energy());
                    entry.setDouble("maxEnergy", line.maxEnergy());
                    entry.setInteger("speedUpgrades", line.speedUpgrades());
                    entry.setInteger("energyUpgrades", line.energyUpgrades());
                    entry.setInteger("maxSpeedUpgrades", line.maxSpeedUpgrades());
                    entry.setInteger("maxEnergyUpgrades", line.maxEnergyUpgrades());
                    entry.setBoolean("lineFlowReceiving", line.energyFlowReceivingToLine());
                    entry.setBoolean("lineFlowSending", line.energyFlowLineToSending());
                    net.minecraft.nbt.NBTTagList display = new net.minecraft.nbt.NBTTagList();
                    int displayCount = 0;
                    for (String value : line.dynamicResourceDisplayList()) {
                        if (displayCount++ >= 16) break;
                        display.appendTag(new net.minecraft.nbt.NBTTagString(value));
                    }
                    entry.setTag("dynamicResourceDisplayList", display);
                    NBTTagList nodeTags = new NBTTagList();
                    int nodeDetailCount = 0;
                    for (MachineNodeData node : line.machines()) {
                        if (nodeDetailCount++ >= 32) break;
                        NBTTagCompound nodeTag = new NBTTagCompound();
                        nodeTag.setString("id", node.nodeId().toString());
                        nodeTag.setString("machine", net.minecraft.item.Item.REGISTRY.getNameForObject(node.machine().getItem()).toString());
                        nodeTag.setInteger("metadata", node.machine().getMetadata());
                        nodeTag.setInteger("count", (int) Math.min(Integer.MAX_VALUE, node.machineCount()));
                        nodeTag.setBoolean("active", node.active());
                        nodeTag.setString("state", node.state());
                        nodeTags.appendTag(nodeTag);
                    }
                    entry.setTag("nodesDetail", nodeTags);
                }
                entries.appendTag(entry);
            }
            tag.setTag("lines", entries);
            return tag;
        }
    }

    // ------------------- machine cache & recycle bin -------------------

    public void flush() {
        if (parent == null) {
            for (CloudworksPlayerDataManager target : domains.values()) target.flush();
            return;
        }
        synchronized (this) {
            if (root == null) return;
            // Any poisoned owner makes the full flush refuse: rewriting files of a
            // failed domain would destroy its replay evidence.
            if (recoveryRequired || !failedOwners.isEmpty()) throw dataError(recoveryCause);
            flushInternal(false);
        }
    }

    /**
     * Close-time form: healthy owners flush, failed domains keep their residue.
     */
    private void flushInternal(boolean skipFailedOwners) {
        drainInteraction();
        for (UUID id : new ArrayList<>(runtimeUnmaterialized)) line(id);
        for (ProductionLineData line : lines.values())
            if (!skipFailedOwners || !failedOwners.contains(line.owner())) writeCheckpoint(line);
        for (CloudworksData cloud : clouds.values()) {
            if (skipFailedOwners && failedOwners.contains(cloud.owner())) continue;
            portJournal.compact(cloud.owner());
            orderedWrite(cloud.owner(), receivingFile(cloud.owner()), cloud.writeReceiving());
            orderedWrite(cloud.owner(), transferStoreFile(cloud.owner()), cloud.writeTransfer());
            orderedWrite(cloud.owner(), sendingFile(cloud.owner()), cloud.writeSending());
            orderedWrite(cloud.owner(), cloudFile(cloud.owner()), cloud.writeCore());
            for (CloudworksPortRecord port : ports.values())
                if (port.owner.equals(cloud.owner()))
                    orderedWrite(cloud.owner(), root.resolve(cloud.owner().toString()).resolve("ports")
                            .resolve(port.id + ".dat"), port.write());
        }
    }

    private void requireLoaded() {
        if (root == null) throw error("unavailable");
    }

    private Path cloudFile(UUID owner) {
        return root.resolve(owner.toString()).resolve("cloud.dat");
    }

    private Path receivingFile(UUID owner) {
        return root.resolve(owner.toString()).resolve("receiving.dat");
    }

    private Path transferStoreFile(UUID owner) {
        return root.resolve(owner.toString()).resolve("transfer.dat");
    }

    private Path sendingFile(UUID owner) {
        return root.resolve(owner.toString()).resolve("sending.dat");
    }

    private Path lineFile(UUID owner, UUID id) {
        return root.resolve(owner.toString()).resolve("production").resolve(id + ".dat");
    }

    private Path lineRuntimeFile(UUID owner, UUID id) {
        return root.resolve(owner.toString()).resolve("production").resolve(id + ".runtime.dat");
    }

    private Path pendingIntentFile(UUID owner) {
        return root.resolve(owner.toString()).resolve("transfer.pending.dat");
    }

    private Path terminalFile(UUID id) {
        return root.resolve("terminals").resolve(id + ".dat");
    }

    private Path machineCacheFile(UUID owner) {
        return root.resolve(owner.toString()).resolve("machine-cache.dat");
    }

    private Path blueprintPendingFile(UUID owner) {
        return root.resolve(owner.toString()).resolve("blueprint-pending.dat");
    }

    private Path sourceDebtsFile(UUID owner) {
        return root.resolve(owner.toString()).resolve("source-debits.dat");
    }

    /**
     * Ledger files live per DEBIT PLAYER under a global directory, decoupled from cloud enablement.
     */
    private Path debitLedgerFile(UUID player) {
        return root.resolve("debits").resolve(player.toString() + ".dat");
    }

    private Path recycleFile(UUID owner) {
        return root.resolve(owner.toString()).resolve("recycle.dat");
    }

    /**
     * Owner-level machine cache; missing files start empty (path is the identity).
     */
    public mekanism.cloudworks.common.data.CloudworksMachineCache machineCache(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.machineCache(owner);
        }
        synchronized (this) {
            Objects.requireNonNull(owner);
            return machineCaches.computeIfAbsent(owner, ignored -> new mekanism.cloudworks.common.data.CloudworksMachineCache());
        }
    }

    /**
     * Owner-level recycle bin; missing files start empty.
     */
    public mekanism.cloudworks.common.data.CloudworksRecycleBin recycleBin(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.recycleBin(owner);
        }
        synchronized (this) {
            Objects.requireNonNull(owner);
            return recycleBins.computeIfAbsent(owner, ignored -> new mekanism.cloudworks.common.data.CloudworksRecycleBin());
        }
    }

    /**
     * Deposits machines into the cache; single-file durable write.
     */
    public void depositMachines(UUID owner, ItemStack stack) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.depositMachines(owner, stack);
            return;
        }
        synchronized (this) {
            requireEnabled(owner);
            mekanism.cloudworks.common.data.CloudworksMachineCache cache = machineCache(owner);
            cache.deposit(stack);
            persistMachineCache(owner, cache);
        }
    }

    /**
     * Variant-level withdrawal (终端插槽化·机器下载): takes up to the requested
     * amount from exactly ONE variant of one type, clamped by its free amount.
     */
    public List<ItemStack> withdrawMachines(UUID owner, int typeIndex, int variantIndex, long amount) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.withdrawMachines(owner, typeIndex, variantIndex, amount);
        }
        synchronized (this) {
            requireEnabled(owner);
            mekanism.cloudworks.common.data.CloudworksMachineCache cache = machineCache(owner);
            if (typeIndex < 0 || typeIndex >= cache.typeCount()) throw error("not_found");
            List<mekanism.cloudworks.common.data.CloudworksMachineCache.Entry> entries = cache.entries();
            List<mekanism.cloudworks.common.data.CloudworksMachineCache.Variant> variants = entries.get(typeIndex).variants();
            if (variantIndex < 0 || variantIndex >= variants.size()) throw error("not_found");
            NBTTagCompound targetTag = variants.get(variantIndex).tag();
            long wanted = Math.min(Math.max(0L, amount), MAX_WITHDRAW_PER_CALL);
            if (wanted == 0) return Collections.emptyList();
            Map<Integer, Map<NBTTagCompound, Long>> occupied = deriveOccupation(owner, cache);
            Map<NBTTagCompound, Long> byTag = occupied.getOrDefault(typeIndex, Collections.emptyMap());
            List<ItemStack> withdrawn = cache.withdraw(typeIndex, wanted, variant -> {
                if (!java.util.Objects.equals(variant.tag(), targetTag)) return 0;
                long free = variant.count() - byTag.getOrDefault(variant.tag(), 0L);
                return (int) Math.min(Math.max(0, free), Integer.MAX_VALUE);
            });
            if (!withdrawn.isEmpty()) persistMachineCache(owner, cache);
            return withdrawn;
        }
    }

    /**
     * Terminal-slot upgrade download (终端插槽化·升级下载): atomically takes up to
     * the requested amount from the pool; returns how much was actually taken.
     */
    public int takeUpgradePool(UUID owner, boolean speed, int amount) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.takeUpgradePool(owner, speed, amount);
        }
        synchronized (this) {
            if (amount <= 0) return 0;
            CloudworksData cloud = requireEnabled(owner);
            int taken = Math.min(amount, cloud.upgradePool(speed));
            if (taken <= 0) return 0;
            int result = addUpgradePool(owner, speed, -taken);
            return result < 0 ? 0 : taken;
        }
    }

    /**
     * Withdraws up to the requested amount from the head variants of one type;
     * machines claimed by lines (derived occupation) can never leave.
     */
    public List<ItemStack> withdrawMachines(UUID owner, int typeIndex, long amount) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.withdrawMachines(owner, typeIndex, amount);
        }
        synchronized (this) {
            requireEnabled(owner);
            mekanism.cloudworks.common.data.CloudworksMachineCache cache = machineCache(owner);
            if (typeIndex < 0 || typeIndex >= cache.typeCount()) throw error("not_found");
            long wanted = Math.min(Math.max(0L, amount), MAX_WITHDRAW_PER_CALL);
            if (wanted == 0) return Collections.emptyList();
            Map<Integer, Map<NBTTagCompound, Long>> occupied = deriveOccupation(owner, cache);
            Map<NBTTagCompound, Long> byTag = occupied.getOrDefault(typeIndex, Collections.emptyMap());
            List<ItemStack> withdrawn = cache.withdraw(typeIndex, wanted, variant -> {
                long free = variant.count() - byTag.getOrDefault(variant.tag(), 0L);
                return (int) Math.min(Math.max(0, free), Integer.MAX_VALUE);
            });
            if (!withdrawn.isEmpty()) persistMachineCache(owner, cache);
            return withdrawn;
        }
    }

    /**
     * Bounded display data for the machine cache list (command/GUI).
     */
    public List<Map<String, Object>> machineCacheView(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.machineCacheView(owner);
        }
        synchronized (this) {
            mekanism.cloudworks.common.data.CloudworksMachineCache cache = machineCache(owner);
            Map<Integer, Map<NBTTagCompound, Long>> occupied = deriveOccupation(owner, cache);
            List<Map<String, Object>> view = new ArrayList<>();
            List<mekanism.cloudworks.common.data.CloudworksMachineCache.Entry> entries = cache.entries();
            for (int index = 0; index < entries.size(); index++) {
                mekanism.cloudworks.common.data.CloudworksMachineCache.Entry entry = entries.get(index);
                Map<NBTTagCompound, Long> byTag = occupied.getOrDefault(index, Collections.emptyMap());
                long claimed = 0;
                for (Long amount : byTag.values()) claimed = Math.addExact(claimed, amount);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", index);
                row.put("machine", entry.template().copy());
                row.put("count", entry.count());
                row.put("available", entry.count() - claimed);
                row.put("claimed", claimed);
                List<Map<String, Object>> claims = new ArrayList<>();
                for (ProductionLineData line : lines(owner)) {
                    long lineClaim = 0;
                    for (MachineNodeData node : line.machines()) {
                        if (ItemStack.areItemsEqual(entry.template(), node.machine())) {
                            lineClaim = Math.addExact(lineClaim, node.machineCount());
                        }
                    }
                    if (lineClaim > 0) {
                        Map<String, Object> claim = new LinkedHashMap<>();
                        claim.put("id", line.lineId().toString());
                        claim.put("name", line.name());
                        claim.put("count", lineClaim);
                        claims.add(claim);
                    }
                }
                row.put("claims", claims);
                view.add(row);
            }
            return view;
        }
    }

    /**
     * Occupation derived from the production lines: for every node, its machine
     * template (item+damage+exact tag) claims machineCount machines of the
     * matching cache variant.
     */
    private Map<Integer, Map<NBTTagCompound, Long>> deriveOccupation(UUID owner,
                                                                     mekanism.cloudworks.common.data.CloudworksMachineCache cache) {
        return CloudworksMachineCacheProjection.occupation(cache, reservationLines(owner, cache));
    }

    private List<ProductionLineData> reservationLines(UUID owner, CloudworksMachineCache cache) {
        List<ProductionLineData> claimed = new ArrayList<>(lines(owner));
        for (CloudworksLineEditSession edit : activeEdits.values()) {
            ProductionLineData baseline = claimed.stream().filter(line -> line.lineId().equals(edit.line)).findFirst().orElse(null);
            claimed.removeIf(line -> line.lineId().equals(edit.line));
            ProductionLineData projection = new ProductionLineData(edit.line, owner);
            for (CloudworksLineDraft.Machine machine : edit.current.machines) {
                if (machine.count == 0) continue;
                MachineNodeData node = new MachineNodeData(machine.id, machine.type, machine.x, machine.y);
                node.setMachineCount(machine.count);
                node.setPooledClaim(true);
                projection.machines().add(node);
            }
            // An unexpected close can still restore the persisted baseline. Keep its backing reserved.
            if (baseline != null) {
                Map<mekanism.cloudworks.common.registry.MachineKey, Long> present = new HashMap<>();
                for (MachineNodeData node : projection.machines()) present.merge(CloudworksRegistries.identityKey(node.machine()), node.machineCount(), Math::addExact);
                Map<mekanism.cloudworks.common.registry.MachineKey, Long> original = new HashMap<>();
                for (MachineNodeData node : baseline.machines()) original.merge(CloudworksRegistries.identityKey(node.machine()), node.machineCount(), Math::addExact);
                for (Map.Entry<mekanism.cloudworks.common.registry.MachineKey, Long> type : original.entrySet()) {
                    long extra = type.getValue() - present.getOrDefault(type.getKey(), 0L);
                    if (extra <= 0) continue;
                    MachineNodeData node = new MachineNodeData(UUID.randomUUID(), type.getKey().displayStack(), 0, 0);
                    node.setMachineCount(extra); node.setPooledClaim(true);
                    projection.machines().add(node);
                }
            }
            claimed.add(projection);
        }
        return claimed;
    }

    /**
     * Load-time backfill: every node's claim must be backed by cache variants.
     * Missing or short variants are topped up from the node template (legacy
     * saves keep their counts; NBT variants normalize to the template).
     */
    private void backfillMachineCache(UUID owner) {
        mekanism.cloudworks.common.data.CloudworksMachineCache cache = machineCache(owner);
        Map<Integer, Map<NBTTagCompound, Long>> occupied = deriveOccupation(owner, cache);
        List<mekanism.cloudworks.common.data.CloudworksMachineCache.Entry> entries = cache.entries();
        boolean changed = false;
        for (ProductionLineData line : lines(owner)) {
            for (MachineNodeData node : line.machines()) {
                if (node.pooledClaim()) continue;
                int index = -1;
                for (int i = 0; i < entries.size(); i++)
                    if (ItemStack.areItemsEqual(entries.get(i).template(), node.machine())) {
                        index = i;
                        break;
                    }
                mekanism.cloudworks.common.data.CloudworksMachineCache.Variant variant = null;
                if (index >= 0) variant = mekanism.cloudworks.common.data.CloudworksMachineCache.findVariant(
                        entries.get(index), node.machine());
                long backing = variant == null ? 0 : variant.count();
                long claim = node.machineCount();
                long sameVariantClaims = index < 0 ? 0 : occupied.getOrDefault(index, Collections.emptyMap())
                        .getOrDefault(stripCaps(node.machine()), 0L);
                long needed = Math.max(claim, sameVariantClaims);
                if (backing >= needed) continue;
                cache.deposit(node.machine(), needed - backing);
                changed = true;
            }
        }
        if (changed) persistMachineCache(owner, cache);
    }

    private void loadMachineCache(UUID owner) {
        if (machineCaches.containsKey(owner)) return;
        Path file = machineCacheFile(owner);
        mekanism.cloudworks.common.data.CloudworksMachineCache cache;
        if (Files.exists(file)) {
            NBTTagCompound tag = read(file);
            cache = mekanism.cloudworks.common.data.CloudworksMachineCache.read(tag);
        } else cache = new mekanism.cloudworks.common.data.CloudworksMachineCache();
        machineCaches.put(owner, cache);
    }

    /**
     * Idempotent ledger load for one debit player; callable from any thread that may append.
     */
    private void loadSourceDebts(UUID player) {
        if (sourceDebts.containsKey(player)) return;
        List<NBTTagCompound> entries = new ArrayList<>();
        Path file = debitLedgerFile(player);
        if (Files.exists(file)) {
            NBTTagCompound tag = read(file);
            NBTTagList list = tag.getTagList("entries", 10);
            for (int i = 0; i < list.tagCount(); i++) entries.add(list.getCompoundTagAt(i).copy());
        }
        sourceDebts.putIfAbsent(player, entries);
    }

    /**
     * Appends one durable debit entry, atomic with the intent application that
     * commits the machines. Runs on the IO thread inside applyTransferIntent —
     * NO manager lock here (the main thread may hold it while draining).
     * Idempotent per transferId: a crash between this write and the intent
     * delete replays both, and the replay skips the existing entry.
     */
    private void appendSourceDebt(UUID owner, NBTTagCompound transfer) {
        if (!transfer.hasKey("debitPlayer")) return;
        UUID player;
        try {
            player = UUID.fromString(transfer.getString("debitPlayer"));
        } catch (IllegalArgumentException invalid) {
            return;
        }
        loadSourceDebts(player);
        List<NBTTagCompound> entries = sourceDebts.get(player);
        String transferId = transfer.getString("transferId");
        synchronized (entries) {
            for (NBTTagCompound existing : entries)
                if (existing.getString("transferId").equals(transferId)) return;   // idempotent replay
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("transferId", transferId);
            entry.setString("owner", owner.toString());
            entry.setTag("machine", transfer.getCompoundTag("machineStack").copy());
            entry.setInteger("count", transfer.getInteger("count"));
            entries.add(entry);
            write(debitLedgerFile(player), writeSourceDebtsSnapshot(player));
        }
    }

    /**
     * Snapshot of one player's ledger; entry-list copied under its own monitor.
     */
    private NBTTagCompound writeSourceDebtsSnapshot(UUID player) {
        List<NBTTagCompound> entries = sourceDebts.get(player);
        return writeSourceDebtsSnapshot(entries);
    }

    private static NBTTagCompound writeSourceDebtsSnapshot(List<NBTTagCompound> entries) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", 1);
        NBTTagList list = new NBTTagList();
        if (entries != null) synchronized (entries) {
            for (NBTTagCompound entry : entries) list.appendTag(entry.copy());
        }
        tag.setTag("entries", list);
        return tag;
    }

    /**
     * Removes the ledger entry after the source fully settled the deduction.
     */
    private void removeSourceDebt(UUID player, UUID transferId) {
        loadSourceDebts(player);
        List<NBTTagCompound> entries = sourceDebts.get(player);
        synchronized (entries) {
            boolean removed = entries.removeIf(entry -> entry.getString("transferId").equals(transferId.toString()));
            if (removed) persistSourceDebts(player, entries);
        }
    }

    /**
     * Shortfall form (GPT review 5): part of the deduction could not be served.
     * The entry SHRINKS to the remaining count and stays durable — retried at
     * the next login (identity-validated, can never over-deduct) and visible
     * for audit; it is never silently dropped while anything is outstanding.
     */
    private void reduceSourceDebt(UUID player, UUID transferId, int remaining) {
        loadSourceDebts(player);
        List<NBTTagCompound> entries = sourceDebts.get(player);
        synchronized (entries) {
            boolean changed = false;
            for (NBTTagCompound entry : entries)
                if (entry.getString("transferId").equals(transferId.toString())) {
                    entry.setInteger("count", remaining);
                    changed = true;
                    break;
                }
            if (changed) persistSourceDebts(player, entries);
        }
    }

    private void persistSourceDebts(UUID player, List<NBTTagCompound> entries) {
        if (persistence == null) write(debitLedgerFile(player), writeSourceDebtsSnapshot(player));
        else {
            pendingSourceDebtWrites.put(player, entries);
            flushSourceDebtWrites();
        }
    }

    private void flushSourceDebtWrites() {
        if (persistence == null || persistence.dead()) return;
        Iterator<Map.Entry<UUID, List<NBTTagCompound>>> pending = pendingSourceDebtWrites.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<UUID, List<NBTTagCompound>> entry = pending.next();
            if (persistence.ownerFailed(entry.getKey()) || failedOwners.contains(entry.getKey())) {
                pending.remove();
                continue;
            }
            List<NBTTagCompound> entries = entry.getValue();
            try {
                persistence.submitFiles(entry.getKey(), new CloudworksPersistenceQueue.FileWrite(
                        debitLedgerFile(entry.getKey()), entries, () -> writeSourceDebtsSnapshot(entries)));
                pending.remove();
            } catch (IllegalStateException busy) {
                if ("cloudworks.error.busy".equals(busy.getMessage())) return;
                throw busy;
            }
        }
    }

    private void loadRecycleBin(UUID owner) {
        if (recycleBins.containsKey(owner)) return;
        Path file = recycleFile(owner);
        mekanism.cloudworks.common.data.CloudworksRecycleBin bin;
        if (Files.exists(file)) {
            NBTTagCompound tag = read(file);
            bin = mekanism.cloudworks.common.data.CloudworksRecycleBin.read(tag);
        } else bin = new mekanism.cloudworks.common.data.CloudworksRecycleBin();
        recycleBins.put(owner, bin);
    }

    private void persistMachineCache(UUID owner, mekanism.cloudworks.common.data.CloudworksMachineCache cache) {
        // Upload intents project machine-cache.dat too; this ordered write can
        // never be clobbered by a queued batch's (older) projection.
        NBTTagCompound tag = cache.write();
        orderedWrite(owner, machineCacheFile(owner), tag);
        guiMachines.put(owner, tag);
    }

    private void persistRecycleBin(UUID owner, mekanism.cloudworks.common.data.CloudworksRecycleBin bin) {
        NBTTagCompound tag = bin.write();
        orderedWrite(owner, recycleFile(owner), tag);
        guiRecycle.put(owner, tag);
    }

    /**
     * Owners with pending recycle overflow notices; drained by the server tick service.
     */
    public synchronized java.util.Set<UUID> drainRecycleOverflowNotices() {
        if (pendingRecycleOverflow.isEmpty()) return Collections.emptySet();
        java.util.Set<UUID> drained = new LinkedHashSet<>(pendingRecycleOverflow);
        pendingRecycleOverflow.clear();
        return drained;
    }

    // commitChannelTransfers and the CloudworksChannelTransfers engine were the
    // pre-virtual channel-routing path; the detached virtual tick (intake/outflow
    // inside CloudworksRuntimeSimulation) fully replaced them and both were removed
    // with their dedicated tests (intent semantics stay covered by the virtual
    // snapshot, replay-validation and fault-injection suites).

    /**
     * Routes the whole recycle bin into the configured warehouse (transfer =
     * reprocess, sending = download). The bin projection rides the durable
     * intent with the warehouse it feeds, so the pair changes atomically.
     * Returns the routed total.
     */
    public long routeRecycleBin(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.routeRecycleBin(owner);
        }
        synchronized (this) {
            drainInteraction();   // ordering only; the publish itself is queued
            CloudworksData cloud = requireEnabled(owner);
            mekanism.cloudworks.common.data.CloudworksRecycleBin bin = recycleBin(owner);
            if (bin.typeCount() == 0) return 0L;
            CloudworksData previousCloud = cloud.copyForRuntime();
            NBTTagCompound previousBin = bin.write();
            try {
                Map<mekanism.api.qio.resource.QIOResourceDescriptor, Long> drained = bin.drain();
                long total = 0;
                CloudworksInventory destination = cloud.recycleToTransfer() ? cloud.transfer() : cloud.sending();
                for (Map.Entry<mekanism.api.qio.resource.QIOResourceDescriptor, Long> entry : drained.entrySet()) {
                    if (destination.insert(entry.getKey(), entry.getValue()) != entry.getValue())
                        throw new IllegalStateException("Warehouse rejected recycle routing");
                    total = Math.addExact(total, entry.getValue());
                }
                publishTags(owner, cloud.write(), Collections.emptyList(), bin.write());
                // Bounded drain: the routing is idempotent per bin contents — a crash
                // inside the window replays the intent or leaves the bin untouched.
                drainInteraction();
                return total;
            } catch (RuntimeException failure) {
                clouds.put(owner, previousCloud);
                recycleBins.put(owner, mekanism.cloudworks.common.data.CloudworksRecycleBin.read(previousBin));
                throw failure;
            }
        }
    }

    /**
     * Purges the recycle bin (manual clear; single file, no pairing).
     */
    public void clearRecycleBin(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.clearRecycleBin(owner);
            return;
        }
        synchronized (this) {
            // recycle.dat is also written by queued intents (route projection);
            // persistRecycleBin orders this write behind any backlog (bounded).
            requireEnabled(owner);
            mekanism.cloudworks.common.data.CloudworksRecycleBin bin = recycleBin(owner);
            if (bin.typeCount() == 0) return;
            bin.clear();
            persistRecycleBin(owner, bin);
        }
    }

    /**
     * Global routing target for the recycle bin: transfer (true) or sending (false).
     */
    public void setRecycleRoute(UUID owner, boolean toTransfer) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.setRecycleRoute(owner, toTransfer);
            return;
        }
        synchronized (this) {
            CloudworksData cloud = requireEnabled(owner);
            if (cloud.recycleToTransfer() == toTransfer) return;
            boolean previous = cloud.recycleToTransfer();
            cloud.setRecycleToTransfer(toTransfer);
            try {
                persistCloudCore(owner, cloud);
            } catch (RuntimeException failure) {
                cloud.setRecycleToTransfer(previous);
                throw failure;
            }
            changed(owner);
        }
    }

    // GLM handoff §4.7: captureVirtualSnapshot / captureDetached /
    // commitVirtualSnapshot (the pre-Runtime detached-snapshot commit path)
    // deleted with their retired fixtures; the frozen Runtime capture below is
    // the only capture form.

    /** A cloud without running lines still owns warehouse energy routing. */
    public VirtualSnapshot captureRuntimeSnapshot(UUID owner, List<UUID> selectedIds) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.captureRuntimeSnapshot(owner, selectedIds);
        }
        synchronized (this) {
            CloudworksData cloud = requireEnabled(owner);
            if (selectedIds == null || new HashSet<>(selectedIds).size() != selectedIds.size())
                throw new IllegalArgumentException("Invalid Runtime selection");
            Map<UUID, NBTTagCompound> baselines = new LinkedHashMap<>();
            for (UUID id : selectedIds) {
                NBTTagCompound tag = guiLines.get(id);
                if (tag == null || !owner.toString().equals(tag.getString("owner"))
                        || !cloud.lineIds().contains(id) || !tag.getBoolean("enabled")
                        || !tag.getBoolean("explicitRouting"))
                    throw new IllegalArgumentException("Invalid published Runtime line");
                baselines.put(id, tag);
            }
            CloudworksResourceSymbols symbols = symbolTables.computeIfAbsent(owner, ignored -> new CloudworksResourceSymbols());
            return new VirtualSnapshot(instanceId, worldEpoch, owner, revisions.get(owner),
                    sessionGenerations.getOrDefault(owner, 0L), CloudworksRegistries.version(),
                    CloudworksRegistries.layoutFingerprint(),
                    RecipeHandler.getGlobalRecipeVersion(), UUID.randomUUID(), symbols.generation(),
                    CloudworksTransactionSeeds.next(), cloud, baselines, true);
        }
    }

    /**
     * Session symbol table of one owner; capture paths must use exactly this instance.
     */
    public CloudworksResourceSymbols symbols(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.symbols(owner);
        }
        synchronized (this) {
            Objects.requireNonNull(owner);
            requireLoaded();
            return symbolTables.computeIfAbsent(owner, ignored -> new CloudworksResourceSymbols());
        }
    }

    /**
     * Lease hot path cache. Resource ids are append-only for a world session,
     * so established descriptors never need to wait for a worker's catalog
     * compilation monitor.
     */
    private synchronized int resourceId(UUID owner, QIOResourceDescriptor descriptor) {
        Map<String, Integer> cache = resourceIdCaches.computeIfAbsent(owner,
                ignored -> new HashMap<>());
        String key = descriptor.toString();
        Integer known = cache.get(key);
        if (known != null) return known;
        int id = symbols(owner).id(descriptor);
        cache.put(key, id);
        return id;
    }

    /**
     * Session presence changed (login, logout, revocation). Any snapshot captured
     * before the event must no longer commit; new captures record the new generation.
     */
    public void noteSessionEvent(UUID owner) {
        if (parent == null) {
            if (root == null || closing) return;
            CloudworksPlayerDataManager target = domain(owner);
            target.noteSessionEvent(owner);
            return;
        }
        synchronized (this) {
            Objects.requireNonNull(owner);
            sessionGenerations.merge(owner, 1L, Long::sum);
        }
    }

    /**
     * Runtime checkpoint admission. Its complete projection was validated on the
     * worker; the server does not serialize, traverse or re-read machine nodes.
     * The revision covers every external mutation since the worker's input.
     */
    public CommitStatus commitRuntimeCheckpoint(VirtualSnapshot origin, long expectedRevision,
            mekanism.cloudworks.common.runtime.CloudworksRuntimeCheckpoint checkpoint) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(origin.owner);
            return target.commitRuntimeCheckpoint(origin, expectedRevision, checkpoint);
        }
        synchronized (this) {
            return commitRuntimeCheckpoint(origin, expectedRevision, checkpoint, true);
        }
    }

    public CommitStatus commitRuntimeCheckpoint(VirtualSnapshot origin, long expectedRevision,
            mekanism.cloudworks.common.runtime.CloudworksRuntimeCheckpoint checkpoint, boolean durable) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(origin.owner);
            return target.commitRuntimeCheckpoint(origin, expectedRevision, checkpoint, durable);
        }
        synchronized (this) {
            UUID owner = origin.owner;
            if (!runtimeCurrent(origin, expectedRevision)) return CommitStatus.CONFLICT;
            if (!owner.equals(checkpoint.cloud().owner()) || !clouds.containsKey(owner))
                return CommitStatus.CONFLICT;
            List<mekanism.cloudworks.common.runtime.CloudworksExternalEvent> ingress =
                    runtimeIngressAfter(owner, checkpoint.appliedSequence());
            if (ingress == null) return CommitStatus.CONFLICT;
            checkpoint.mergeIngress(ingress, symbols(owner));
            List<UUID> updated = checkpoint.lineIds();
            List<NBTTagCompound> tags = checkpoint.lineTags();
            Map<UUID, NBTTagCompound> next = new LinkedHashMap<>();
            for (int index = 0; index < updated.size(); index++) {
                UUID id = updated.get(index);
                if (!lines.containsKey(id) || !clouds.get(owner).lineIds().contains(id)
                        || !owner.toString().equals(tags.get(index).getString("owner"))
                        || !id.toString().equals(tags.get(index).getString("lineId")))
                    return CommitStatus.CONFLICT;
                next.put(id, tags.get(index));
            }
            CloudworksWarehouseDelta changes = new CloudworksWarehouseDelta();
            CloudworksWarehouseDelta pendingChanges = runtimeWarehouseDeltas.get(owner);
            if (pendingChanges != null) changes.merge(pendingChanges);
            changes.merge(checkpoint.changes());
            if (durable) {
                Map<UUID, NBTTagCompound> all = pendingRuntimeTags(owner);
                all.putAll(next);
                persistRuntimeTags(owner, checkpoint.coreTag(), all, changes.write());
            }
            if (!checkpoint.acceptOnce()) return CommitStatus.CONFLICT;
            clouds.put(owner, checkpoint.cloud());
            for (int index = 0; index < updated.size(); index++) {
                UUID id = updated.get(index);
                guiLines.put(id, tags.get(index));
                runtimeUnmaterialized.add(id);
            }
            if (!durable) {
                runtimeDirtyLines.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).addAll(next.keySet());
                runtimeWarehouseDeltas.put(owner, changes);
            }
            changedFromRuntime(owner);
            return CommitStatus.COMMITTED;
        }
    }

    private Map<UUID, NBTTagCompound> pendingRuntimeTags(UUID owner) {
        Map<UUID, NBTTagCompound> tags = new LinkedHashMap<>();
        Set<UUID> ids = runtimeDirtyLines.get(owner);
        CloudworksData cloud = clouds.get(owner);
        if (ids != null && cloud != null)
            for (UUID id : ids)
                if (cloud.lineIds().contains(id) && guiLines.containsKey(id)) tags.put(id, guiLines.get(id));
        return tags;
    }

    /** Independent save cadence. Only frozen payload references and line metadata are visited here. */
    public void flushRuntimePending(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.flushRuntimePending(owner);
            return;
        }
        synchronized (this) {
            if (!runtimeDirtyLines.containsKey(owner) || recoveryRequired(owner)) return;
            CloudworksData cloud = clouds.get(owner);
            if (cloud == null) return;
            CloudworksWarehouseDelta changes = runtimeWarehouseDeltas.get(owner);
            persistRuntimeTags(owner, cloud.writeCore(), pendingRuntimeTags(owner),
                    changes == null ? new CloudworksWarehouseDelta().write() : changes.write());
        }
    }

    private void persistRuntimeTags(UUID owner, NBTTagCompound cloud, Map<UUID, NBTTagCompound> tags) {
        persistRuntimeTags(owner, cloud, tags, null);
    }

    private void persistRuntimeTags(UUID owner, NBTTagCompound cloud, Map<UUID, NBTTagCompound> tags,
                                    NBTTagCompound warehouseDelta) {
        NBTTagCompound intent = new NBTTagCompound();
        intent.setInteger("schema", 1);
        intent.setString("owner", owner.toString());
        NBTTagCompound cloudPayload = new NBTTagCompound();
        for (String key : cloud.getKeySet())
            if (warehouseDelta == null || !key.equals("receiving") && !key.equals("transfer") && !key.equals("sending"))
                cloudPayload.setTag(key, cloud.getTag(key).copy());
        intent.setTag("cloud", cloudPayload);
        if (warehouseDelta != null) {
            intent.setTag("warehouseDelta", warehouseDelta.copy());
        }
        NBTTagList entries = new NBTTagList();
        Map<UUID, Long> generations = new LinkedHashMap<>();
        for (Map.Entry<UUID, NBTTagCompound> line : tags.entrySet()) {
            // Shallow headers: every nested machine/channel tag is frozen.
            NBTTagCompound tag = new NBTTagCompound();
            for (String key : line.getValue().getKeySet()) tag.setTag(key, line.getValue().getTag(key));
            long generation = Math.addExact(checkpointGenerations.getOrDefault(line.getKey(), 0L), 1L);
            tag.setLong("generation", generation);
            entries.appendTag(tag);
            generations.put(line.getKey(), generation);
        }
        intent.setTag("lines", entries);
        if (persistence == null) {
            ioSink.beginBatch();
            try {
                ioSink.writeIntentFile(owner, intent);
                ioSink.applyIntentRelaxed(owner, intent, CloudworksPersistenceQueue.AbortPoint.NONE);
                ioSink.commitBatch();
            } finally { ioSink.endBatch(); }
        } else persistence.submitIntent(owner, intent);
        checkpointGenerations.putAll(generations);
        runtimeDirtyLines.remove(owner);
        runtimeWarehouseDeltas.remove(owner);
        CloudworksData submitted = CloudworksData.read(cloudPayload);
        if (warehouseDelta != null) {
            CloudworksData baseline = durableCloudBaselines.get(owner);
            if (baseline != null) {
                submitted = baseline.copyForRuntime();
                applyCore(submitted, cloudPayload);
                applyMemoryDelta(submitted, warehouseDelta);
            }
        }
        durableCloudBaselines.put(owner, submitted);
    }

    /** Constant-size credential check, also used for unchanged Runtime ticks. */
    public boolean runtimeCurrent(VirtualSnapshot origin, long expectedRevision) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(origin.owner);
            return target.runtimeCurrent(origin, expectedRevision);
        }
        synchronized (this) {
            UUID owner = origin.owner;
            if (!origin.boundTo(instanceId, worldEpoch) || recoveryRequired || failedOwners.contains(owner)
                    || !onlyRuntimeIngressSince(owner, expectedRevision)
                    || sessionGenerations.getOrDefault(owner, 0L) != origin.sessionGeneration
                    || CloudworksRegistries.version() != origin.registryVersion
                    || CloudworksRegistries.layoutFingerprint() != origin.layoutFingerprint
                    || RecipeHandler.getGlobalRecipeVersion() != origin.recipeVersion
                    || !Arrays.equals(origin.upgradeSettings,
                            mekanism.cloudworks.common.upgrade.CloudworksUpgradeEffects.settings()))
                return false;
            return clouds.containsKey(owner);
        }
    }

    private void publishTags(UUID owner, NBTTagCompound cloudTag, List<NBTTagCompound> lineTags) {
        publishTags(owner, cloudTag, lineTags, null);
    }

    /**
     * Single publication choke point. Works on validated NBT tags only and
     * installs freshly re-read objects into the live maps, so no caller
     * reference can keep mutating published state after this method returns.
     * The durable intent is written and applied by the single IO thread; the
     * queue enforces one in-flight intent file at a time. The optional recycle
     * projection routes the bin atomically with the warehouse it feeds.
     */
    private void publishTags(UUID owner, NBTTagCompound cloudTag, List<NBTTagCompound> lineTags,
                             NBTTagCompound recycleTag) {
        publish0(owner, cloudTag, lineTags, recycleTag, null, null);
    }

    /**
     * Upload publication form (GPT design §4.3/§6): one durable intent carries
     * the line projection (the new node), the machine-cache projection (the
     * deposited machines) and the transfer segment (transferId identity and
     * recovery data) together, so the machines have exactly one owner across
     * any process death. The caller has already reserved the source machines.
     */
    private void publishUploadIntent(UUID owner, NBTTagCompound lineTag,
                                     NBTTagCompound machineCacheTag, NBTTagCompound transferTag) {
        publish0(owner, clouds.get(owner).write(), Collections.singletonList(lineTag),
                null, machineCacheTag, transferTag);
    }

    /**
     * Shared intent builder; only upload intents carry machineCache/transfer segments.
     */
    private void publish0(UUID owner, NBTTagCompound cloudTag, List<NBTTagCompound> lineTags,
                          NBTTagCompound recycleTag, NBTTagCompound machineCacheTag, NBTTagCompound transferTag) {
        publish0(owner, cloudTag, lineTags, recycleTag, machineCacheTag, transferTag, null);
    }

    private void publish0(UUID owner, NBTTagCompound cloudTag, List<NBTTagCompound> lineTags,
                          NBTTagCompound recycleTag, NBTTagCompound machineCacheTag,
                          NBTTagCompound transferTag, NBTTagCompound terminalTag) {
        publish0(owner, cloudTag, lineTags, recycleTag, machineCacheTag, transferTag, terminalTag, true);
    }

    private void publish0(UUID owner, NBTTagCompound cloudTag, List<NBTTagCompound> lineTags,
                          NBTTagCompound recycleTag, NBTTagCompound machineCacheTag,
                          NBTTagCompound transferTag, NBTTagCompound terminalTag, boolean controlChange) {
        publish0(owner, cloudTag, lineTags, recycleTag, machineCacheTag, transferTag, terminalTag,
                controlChange, false);
    }

    /**
     * Checkpoint-only publication does not change the control revision.
     */
    private void publish0(UUID owner, NBTTagCompound cloudTag, List<NBTTagCompound> lineTags,
                          NBTTagCompound recycleTag, NBTTagCompound machineCacheTag,
                          NBTTagCompound transferTag, NBTTagCompound terminalTag,
                          boolean controlChange, boolean checkpointOnly) {
        publish0(owner, cloudTag, lineTags, recycleTag, machineCacheTag, transferTag, terminalTag,
                controlChange, checkpointOnly, null);
    }

    private void publish0(UUID owner, NBTTagCompound cloudTag, List<NBTTagCompound> lineTags,
                          NBTTagCompound recycleTag, NBTTagCompound machineCacheTag,
                          NBTTagCompound transferTag, NBTTagCompound terminalTag,
                          boolean controlChange, boolean checkpointOnly, NBTTagCompound blueprintPending) {
        publish0(owner, cloudTag, lineTags, recycleTag, machineCacheTag, transferTag, terminalTag,
                controlChange, checkpointOnly, blueprintPending, Collections.emptyList());
    }

    private void publish0(UUID owner, NBTTagCompound cloudTag, List<NBTTagCompound> lineTags,
                          NBTTagCompound recycleTag, NBTTagCompound machineCacheTag,
                          NBTTagCompound transferTag, NBTTagCompound terminalTag,
                          boolean controlChange, boolean checkpointOnly, NBTTagCompound blueprintPending,
                          Collection<UUID> deletedLineIds) {
        // Per-owner P1 gate: a poisoned fault domain never submits new durable
        // work; its later batches would only be dropped by the IO worker.
        if (recoveryRequired || failedOwners.contains(owner)) throw dataError(recoveryCause(owner));
        for (NBTTagCompound tag : lineTags) ProductionLineData.read(tag.copy());
        if (recycleTag != null) mekanism.cloudworks.common.data.CloudworksRecycleBin.read(recycleTag.copy());
        if (terminalTag != null && !owner.equals(CloudworksTerminalState.read(terminalTag).owner()))
            throw dataError(null);
        if (machineCacheTag != null) {
            if (terminalTag != null && transferTag == null) CloudworksMachineCache.read(machineCacheTag.copy());
            else validateUploadSegments(machineCacheTag, transferTag, lineTags);
        }
        NBTTagCompound intent = new NBTTagCompound();
        intent.setInteger("schema", 1);
        intent.setString("owner", owner.toString());
        CloudworksData submittedCloud = CloudworksData.read(cloudTag);
        CloudworksData baseline = durableCloudBaselines.get(owner);
        if (baseline == null) intent.setTag("cloud", cloudTag.copy());
        else {
            intent.setTag("cloud", submittedCloud.writeCore());
            intent.setTag("warehouseDelta", warehouseDifference(baseline, submittedCloud));
        }
        if (recycleTag != null) intent.setTag("recycle", recycleTag.copy());
        if (machineCacheTag != null) intent.setTag("machineCache", machineCacheTag.copy());
        if (transferTag != null) intent.setTag("transfer", transferTag.copy());
        if (terminalTag != null) intent.setTag("terminal", terminalTag.copy());
        if (blueprintPending != null) {
            CloudworksPendingBlueprint.read(blueprintPending);
            intent.setTag("blueprintPending", blueprintPending.copy());
        }
        if (!deletedLineIds.isEmpty()) {
            NBTTagList deleted = new NBTTagList();
            Set<UUID> uniqueDeleted = new HashSet<>();
            for (UUID id : deletedLineIds) {
                if (id == null || !uniqueDeleted.add(id) || submittedCloud.lineIds().contains(id))
                    throw dataError(null);
                deleted.appendTag(new net.minecraft.nbt.NBTTagString(id.toString()));
            }
            intent.setTag("deletedLines", deleted);
        }
        NBTTagList entries = new NBTTagList();
        Map<UUID, NBTTagCompound> pending = pendingRuntimeTags(owner);
        for (NBTTagCompound tag : lineTags) pending.remove(UUID.fromString(tag.getString("lineId")));
        for (NBTTagCompound tag : pending.values()) {
            NBTTagCompound entry = new NBTTagCompound();
            for (String key : tag.getKeySet()) entry.setTag(key, tag.getTag(key));
            entry.setLong("generation", Math.addExact(checkpointGenerations.getOrDefault(
                    UUID.fromString(entry.getString("lineId")), 0L), 1L));
            entries.appendTag(entry);
        }
        for (NBTTagCompound tag : lineTags) {
            NBTTagCompound entry = tag.copy();
            entry.setLong("generation", Math.addExact(checkpointGenerations.getOrDefault(
                    UUID.fromString(entry.getString("lineId")), 0L), 1L));
            entries.appendTag(entry);
        }
        intent.setTag("lines", entries);
        // The IO thread embeds cloudExisted/previousCloud from the ordered disk
        // state right before writing the intent, so replay validation is exact.
        persistence.submitIntent(owner, intent);
        runtimeDirtyLines.remove(owner);
        runtimeWarehouseDeltas.remove(owner);
        clouds.put(owner, submittedCloud);
        durableCloudBaselines.put(owner, submittedCloud.copyForRuntime());
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound entry = entries.getCompoundTagAt(i);
            UUID id = UUID.fromString(entry.getString("lineId"));
            // Unedited pending entries already have validated in-memory models.
            if (!pending.containsKey(id)) {
                lines.put(id, ProductionLineData.read(entry.copy()));
                runtimeUnmaterialized.remove(id);
            }
            guiLines.put(id, entry);
            checkpointGenerations.put(id, entry.getLong("generation"));
        }
        if (machineCacheTag != null) machineCaches.put(owner,
                mekanism.cloudworks.common.data.CloudworksMachineCache.read(machineCacheTag.copy()));
        if (machineCacheTag != null) guiMachines.put(owner, machineCacheTag);
        if (recycleTag != null) guiRecycle.put(owner, recycleTag);
        if (blueprintPending != null) {
            CloudworksPendingBlueprint pendingBlueprint = CloudworksPendingBlueprint.read(blueprintPending);
            if (pendingBlueprint == null) pendingBlueprints.remove(owner);
            else pendingBlueprints.put(owner, pendingBlueprint);
        }
        if (checkpointOnly) return;
        if (controlChange) changed(owner); else changedFromRuntime(owner);
    }

    private void replayPendingTransfer(UUID owner) {
        Path pending = pendingIntentFile(owner);
        if (!Files.exists(pending)) return;
        boolean ownBatch = IO_READS.get() == null;
        if (ownBatch) ioSink.beginBatch();
        try {
            NBTTagCompound intent = read(pending);
            applyTransferIntent(owner, intent, true, CloudworksPersistenceQueue.AbortPoint.NONE);
            if (ownBatch) ioSink.commitBatch();
        } catch (RuntimeException failure) {
            // The residue of THIS owner cannot replay: poison only its domain.
            failedOwners.add(owner);
            ownerFailureCauses.put(owner, failure);
            System.err.println("CLOUDWORKS-REPLAY FAILED owner=" + owner + ": " + failure);
            throw failure;
        } finally { if (ownBatch) ioSink.endBatch(); }
    }

    /**
     * Strict load-time replay; the durable delete happens inside.
     */
    private void applyTransferIntent(UUID owner, NBTTagCompound intent) {
        applyTransferIntent(owner, intent, true, CloudworksPersistenceQueue.AbortPoint.NONE);
    }

    /**
     * Applies one intent to the files in order, then deletes the intent. The
     * strict form compares the previous cloud snapshot (load-time replay); the
     * relaxed form relies on the single IO thread's total write order. The full
     * cloud tag is split into its cloud.dat core and the three warehouse
     * projections; each file is written idempotently: equal-to-next is skipped
     * (already applied), otherwise written (strict mode validated it equals the
     * previous state). Abort points stop between the real write steps and leave
     * the residue untouched.
     */
    private void applyTransferIntent(UUID owner, NBTTagCompound intent, boolean strict,
                                      CloudworksPersistenceQueue.AbortPoint abort) {
        DEBUG_INTENT_APPLIES.incrementAndGet();
        if (intent.getInteger("schema") == 3) return;
        intent = expandProjectionPatches(owner, intent);
        if (intent.getInteger("schema") == 2) {
            if (!intent.getBoolean("portJournalDurable")) portJournal.append(owner, intent);
            if (abort != CloudworksPersistenceQueue.AbortPoint.NONE
                    && abort != CloudworksPersistenceQueue.AbortPoint.AFTER_DELETE)
                throw new CloudworksPersistenceQueue.SimulatedCrash(abort);
            if (Files.exists(pendingIntentFile(owner))) deleteTransferIntent(pendingIntentFile(owner));
            if (abort == CloudworksPersistenceQueue.AbortPoint.AFTER_DELETE)
                throw new CloudworksPersistenceQueue.SimulatedCrash(abort);
            return;
        }
        if (intent.getInteger("schema") != 1 || !owner.toString().equals(intent.getString("owner"))
                || !intent.hasKey("cloud", 10) || !intent.hasKey("lines", 9))
            throw dataError(null);
        discardLegacyPendingFromIntent(intent);
        requireSchema(intent.getCompoundTag("cloud"), CloudworksData.SCHEMA_VERSION);
        ProjectionPlan plan = projectionPlan(intent);
        CloudworksData cloud = plan == null ? CloudworksData.read(intent.getCompoundTag("cloud")) : plan.cloud;
        if (plan == null && IO_READS.get() != null)
            IO_READS.get().projections.put(intent, new ProjectionPlan(cloud));
        if (!owner.equals(cloud.owner()) || new HashSet<>(cloud.lineIds()).size() != cloud.lineIds().size())
            throw dataError(null);
        Path cloudPath = cloudFile(owner);
        boolean cloudExisted = intent.getBoolean("cloudExisted");
        CloudworksData previous = cloudExisted && intent.hasKey("previousCloud", 10)
                ? CloudworksData.read(intent.getCompoundTag("previousCloud")) : null;
        if (Files.exists(cloudPath)) {
            if (strict && !intent.hasKey("previousFingerprints", 10)) {
                // Per-projection validation: a crash between the split writes
                // leaves a mixed state (some files new, some old) that must
                // still replay; each file is independently old-or-new.
                CloudworksData diskState = readDiskCloud(owner);
                NBTTagCompound receivingProjection = warehouseProjection(owner, intent, cloud, "receiving");
                NBTTagCompound transferProjection = warehouseProjection(owner, intent, cloud, "transfer");
                NBTTagCompound sendingProjection = warehouseProjection(owner, intent, cloud, "sending");
                if (!matchesProjection(diskState.writeCore(), cloud.writeCore(),
                        previous == null ? null : previous.writeCore())
                        || !matchesProjection(diskState.writeReceiving(), receivingProjection,
                        previous == null ? null : previous.writeReceiving())
                        || !matchesProjection(diskState.writeTransfer(), transferProjection,
                        previous == null ? null : previous.writeTransfer())
                        || !matchesProjection(diskState.writeSending(), sendingProjection,
                        previous == null ? null : previous.writeSending()))
                    throw dataError(null);
                if (intent.hasKey("recycle", 10) && !matchesRecycle(owner, intent)) throw dataError(null);
            }
        } else if (cloudExisted) throw dataError(null);
        // Upload intents: the transfer segment must pair with a machine-cache
        // projection and stay structurally parseable before anything is written.
        if (intent.hasKey("transfer", 10)) {
            NBTTagCompound transfer = intent.getCompoundTag("transfer");
            if (transfer.getInteger("schema") != 1 || transfer.getInteger("count") <= 0
                    || !intent.hasKey("machineCache", 10)) throw dataError(null);
            UUID.fromString(transfer.getString("transferId"));
            UUID.fromString(transfer.getString("nodeId"));
            if (new ItemStack(transfer.getCompoundTag("machineStack")).isEmpty()) throw dataError(null);
        }
        if (strict && intent.hasKey("previousFingerprints", 10)) validateProjectionFingerprints(owner, intent);
        if (strict && !intent.hasKey("previousFingerprints", 10)
                && intent.hasKey("machineCache", 10) && !matchesMachineCache(owner, intent))
            throw dataError(null);
        if (intent.hasKey("terminal", 10)) {
            CloudworksTerminalState terminal = CloudworksTerminalState.read(intent.getCompoundTag("terminal"));
            if (!owner.equals(terminal.owner())) throw dataError(null);
            Path file = terminalFile(terminal.terminalId());
            if (strict && !intent.hasKey("previousFingerprints", 10)) {
                if (Files.exists(file)) {
                    if (!matchesProjection(read(file), terminal.write(),
                            intent.hasKey("previousTerminal", 10) ? intent.getCompoundTag("previousTerminal") : null))
                        throw dataError(null);
                } else if (intent.hasKey("previousTerminal")) throw dataError(null);
            }
        }
        if (intent.hasKey("blueprintPending", 10)) {
            CloudworksPendingBlueprint pendingBlueprint = CloudworksPendingBlueprint.read(intent.getCompoundTag("blueprintPending"));
            if (pendingBlueprint != null && !cloud.lineIds().contains(pendingBlueprint.line)) throw dataError(null);
        }
        List<UUID> deletedLineIds = validateDeletedLineFiles(owner, intent, cloud, cloudPath);
        NBTTagList entries = intent.getTagList("lines", 10);
        Set<UUID> seen = new HashSet<>();
        // Validate every destination before replacing any file, including on replay.
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound entry = entries.getCompoundTagAt(i);
            requireSchema(entry, ProductionLineData.SCHEMA_VERSION);
            DEBUG_LINE_READS.incrementAndGet();
            ProductionLineData line = ProductionLineData.read(entry.copy());
            if (!owner.equals(line.owner()) || !cloud.lineIds().contains(line.lineId())
                    || !seen.add(line.lineId()) || entry.getLong("generation") <= 0)
                throw dataError(null);
            Path existing = lineFile(owner, line.lineId());
            if (!Files.exists(existing)) throw dataError(null);
            NBTTagCompound current = readView(existing);
            requireSchema(current, ProductionLineData.SCHEMA_VERSION);
            long generation = entry.getLong("generation");
            if (!owner.toString().equals(current.getString("owner"))
                    || !line.lineId().toString().equals(current.getString("lineId"))
                    || (current.getLong("generation") != generation - 1 && !current.equals(entry)))
                throw dataError(null);
        }
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound entry = entries.getCompoundTagAt(i);
            UUID id = UUID.fromString(entry.getString("lineId"));
            write(lineFile(owner, id), entry);
            if (abort == CloudworksPersistenceQueue.AbortPoint.AFTER_FIRST_FILE)
                throw new CloudworksPersistenceQueue.SimulatedCrash(abort);
        }
        // Upload projection: the deposited machines ride the same intent as the
        // node that claims them, so a crash can never separate the pair.
        if (intent.hasKey("machineCache", 10)) {
            writeIntentProjection(intent, "machineCache", machineCacheFile(owner), intent.getCompoundTag("machineCache"),
                    intent.hasKey("previousMachineCache", 10)
                            ? intent.getCompoundTag("previousMachineCache") : null, strict);
            if (abort == CloudworksPersistenceQueue.AbortPoint.AFTER_MACHINE_CACHE)
                throw new CloudworksPersistenceQueue.SimulatedCrash(abort);
        }
        if (intent.hasKey("terminal", 10)) {
            CloudworksTerminalState terminal = CloudworksTerminalState.read(intent.getCompoundTag("terminal"));
            writeIntentProjection(intent, "terminal", terminalFile(terminal.terminalId()), nextProjection(owner, intent, cloud, "terminal"),
                    intent.hasKey("previousTerminal", 10) ? intent.getCompoundTag("previousTerminal") : null, strict);
        }
        // Durable debit ledger: the entry materialises with the commit itself.
        // Placed before the intent delete so a crash in between replays both
        // (the append is idempotent per transferId).
        if (intent.hasKey("transfer", 10))
            appendSourceDebt(owner, intent.getCompoundTag("transfer"));
        writeIntentProjection(intent, "receiving", receivingFile(owner), nextProjection(owner, intent, cloud, "receiving"),
                previous == null ? null : previous.writeReceiving(), strict);
        writeIntentProjection(intent, "transfer", transferStoreFile(owner), nextProjection(owner, intent, cloud, "transfer"),
                previous == null ? null : previous.writeTransfer(), strict);
        writeIntentProjection(intent, "sending", sendingFile(owner), nextProjection(owner, intent, cloud, "sending"),
                previous == null ? null : previous.writeSending(), strict);
        if (abort == CloudworksPersistenceQueue.AbortPoint.AFTER_INVENTORIES)
            throw new CloudworksPersistenceQueue.SimulatedCrash(abort);
        writeIntentProjection(intent, "cloud", cloudPath, nextProjection(owner, intent, cloud, "cloud"),
                previous == null ? null : previous.writeCore(), strict);
        if (intent.hasKey("recycle", 10)) {
            writeIntentProjection(intent, "recycle", recycleFile(owner), intent.getCompoundTag("recycle"),
                    intent.hasKey("previousRecycle", 10) ? intent.getCompoundTag("previousRecycle") : null, strict);
        }
        if (intent.hasKey("blueprintPending", 10))
            writeIntentProjection(intent, "blueprintPending", blueprintPendingFile(owner),
                    nextProjection(owner, intent, cloud, "blueprintPending"), null, strict);
        if (abort == CloudworksPersistenceQueue.AbortPoint.AFTER_CLOUD)
            throw new CloudworksPersistenceQueue.SimulatedCrash(abort);
        deleteProductionLineFiles(owner, deletedLineIds);
        if (!deletedLineIds.isEmpty() && abort == CloudworksPersistenceQueue.AbortPoint.AFTER_LINE_DELETE)
            throw new CloudworksPersistenceQueue.SimulatedCrash(abort);
        deleteTransferIntent(pendingIntentFile(owner));
        if (abort == CloudworksPersistenceQueue.AbortPoint.AFTER_DELETE)
            throw new CloudworksPersistenceQueue.SimulatedCrash(abort);
    }

    private List<UUID> validateDeletedLineFiles(UUID owner, NBTTagCompound intent,
            CloudworksData nextCloud, Path cloudPath) {
        if (!intent.hasKey("deletedLines")) return Collections.emptyList();
        if (!intent.hasKey("deletedLines", 9)) throw dataError(null);
        NBTTagList entries = intent.getTagList("deletedLines", 8);
        if (entries.getTagType() != 8) throw dataError(null);
        List<UUID> ids = new ArrayList<>(entries.tagCount());
        Set<UUID> unique = new HashSet<>();
        for (int index = 0; index < entries.tagCount(); index++) {
            String value = entries.getStringTagAt(index);
            UUID id;
            try { id = UUID.fromString(value); }
            catch (IllegalArgumentException invalid) { throw dataError(invalid); }
            if (!id.toString().equalsIgnoreCase(value) || !unique.add(id) || nextCloud.lineIds().contains(id))
                throw dataError(null);
            Path file = lineFile(owner, id);
            if (Files.exists(file)) {
                NBTTagCompound line = readView(file);
                requireSchema(line, ProductionLineData.SCHEMA_VERSION);
                if (!owner.toString().equals(line.getString("owner"))
                        || !id.toString().equals(line.getString("lineId"))) throw dataError(null);
            } else {
                if (!Files.exists(cloudPath)) throw dataError(null);
                NBTTagCompound diskCore = readView(cloudPath);
                requireSchema(diskCore, CloudworksData.SCHEMA_VERSION);
                if (CloudworksData.read(diskCore).lineIds().contains(id)) throw dataError(null);
            }
            ids.add(id);
        }
        return ids;
    }

    private void deleteProductionLineFiles(UUID owner, List<UUID> lineIds) {
        for (UUID id : lineIds) {
            try {
                Files.deleteIfExists(lineFile(owner, id));
                Files.deleteIfExists(lineRuntimeFile(owner, id));
            } catch (IOException failure) {
                throw dataError(failure);
            }
        }
    }

    /**
     * One projection file of an intent application. Strict mode: disk equal to
     * next means already applied (crash resume) and is skipped; any other disk
     * content was validated against the previous state by the full compare.
     * Relaxed mode: skip only when the embedded previous already equals next.
     */
    private void writeProjection(Path file, NBTTagCompound next, NBTTagCompound previous, boolean strict) {
        if (Files.exists(file)) {
            if (strict) {
                if (!readView(file).equals(next)) write(file, next);
            } else if (previous == null || !previous.equals(next)) {
                write(file, next);
            }
        } else write(file, next);
    }

    private NBTTagCompound warehouseProjection(UUID owner, NBTTagCompound intent, CloudworksData cloud,
                                               String name) {
        if (!intent.hasKey("warehouseDelta", 10))
            return "receiving".equals(name) ? cloud.writeReceiving()
                    : "transfer".equals(name) ? cloud.writeTransfer() : cloud.writeSending();
        NBTTagCompound delta = intent.getCompoundTag("warehouseDelta");
        Path file = "receiving".equals(name) ? receivingFile(owner)
                : "transfer".equals(name) ? transferStoreFile(owner) : sendingFile(owner);
        NBTTagCompound base;
        if (Files.exists(file)) base = readView(file);
        else base = "receiving".equals(name) ? cloud.writeReceiving()
                : "transfer".equals(name) ? cloud.writeTransfer() : cloud.writeSending();
        NBTTagCompound warehouse = delta.hasKey(name, 10) ? delta.getCompoundTag(name) : new NBTTagCompound();
        String inventoryKey = name;
        CloudworksInventory inventory = new CloudworksInventory(Long.MAX_VALUE);
        if (base.hasKey(inventoryKey, 10))
            inventory = CloudworksInventory.read(base.getCompoundTag(inventoryKey));
        if (warehouse.hasKey("entries", 9)) {
            NBTTagList entries = warehouse.getTagList("entries", 10);
            for (int index = 0; index < entries.tagCount(); index++) {
                NBTTagCompound entry = entries.getCompoundTagAt(index);
                QIOResourceDescriptor descriptor = QIOResourceDescriptor.read(
                        entry.getCompoundTag("descriptor"));
                if (!descriptor.isResolved()) throw dataError(null);
                long target = entry.getLong("amount");
                long current = inventory.amount(descriptor);
                if (current > target) inventory.extract(descriptor, current - target);
                else if (target > current) inventory.insertIgnoringTypeCap(descriptor, target - current);
            }
        }
        NBTTagCompound result = "receiving".equals(name) ? cloud.writeReceiving()
                : "transfer".equals(name) ? cloud.writeTransfer() : cloud.writeSending();
        result.setTag(inventoryKey, inventory.write());
        if (!"sending".equals(name)) {
            int cursor = base.getInteger(name + "Cursor");
            if (warehouse.hasKey("cursor", 3)) cursor = warehouse.getInteger("cursor");
            result.setInteger(name + "Cursor", cursor);
        }
        return preservePortWatermark(file, result);
    }

    /**
     * Strict replay validation of the optional recycle projection.
     */
    private boolean matchesRecycle(UUID owner, NBTTagCompound intent) {
        NBTTagCompound next = intent.getCompoundTag("recycle");
        mekanism.cloudworks.common.data.CloudworksRecycleBin.read(next.copy());
        NBTTagCompound disk = Files.exists(recycleFile(owner))
                ? read(recycleFile(owner)) : new mekanism.cloudworks.common.data.CloudworksRecycleBin().write();
        return matchesProjection(disk, next,
                intent.hasKey("previousRecycle", 10) ? intent.getCompoundTag("previousRecycle") : null);
    }

    /**
     * Strict replay validation of the upload machine-cache projection.
     */
    private boolean matchesMachineCache(UUID owner, NBTTagCompound intent) {
        NBTTagCompound next = intent.getCompoundTag("machineCache");
        mekanism.cloudworks.common.data.CloudworksMachineCache.read(next.copy());
        NBTTagCompound disk = Files.exists(machineCacheFile(owner))
                ? read(machineCacheFile(owner))
                : new mekanism.cloudworks.common.data.CloudworksMachineCache().write();
        return matchesProjection(disk, next,
                intent.hasKey("previousMachineCache", 10)
                        ? intent.getCompoundTag("previousMachineCache") : null);
    }

    /**
     * Reconstructed full cloud record (core + split warehouses) as currently on disk; missing warehouses read as defaults.
     */
    private CloudworksData readDiskCloud(UUID owner) {
        NBTTagCompound core = read(cloudFile(owner));
        requireSchema(core, CloudworksData.SCHEMA_VERSION);
        CloudworksData merged = CloudworksData.read(core);
        merged.resetStagingInventories();
        applyWarehouse(receivingFile(owner), merged::applyReceiving);
        applyWarehouse(transferStoreFile(owner), merged::applyTransfer);
        applyWarehouse(sendingFile(owner), merged::applySending);
        return merged;
    }

    private NBTTagCompound fullCloudOnDisk(UUID owner) {
        return readDiskCloud(owner).write();
    }

    /**
     * Loads one warehouse file when present; missing files keep the fresh defaults.
     */
    private void applyWarehouse(Path file, java.util.function.Consumer<NBTTagCompound> applicator) {
        if (!Files.exists(file)) return;
        NBTTagCompound tag = read(file);
        requireSchema(tag, CloudworksData.INVENTORY_SCHEMA_VERSION);
        applicator.accept(tag);
    }

    /**
     * Persists the configuration half of one cloud record. The split warehouse
     * files are created alongside the first cloud.dat so the pair-exists
     * invariant stays structural; later configuration writes leave them alone.
     * Bounded ordered path: a live backlog receives the write as a queued
     * plain batch instead of stalling the server thread.
     */
    private void persistCloudCore(UUID owner, CloudworksData cloud) {
        // Core configuration and the split warehouse projections must cross the
        // same durable intent boundary. Directly writing the four files here
        // leaves a process-death window where cloud.dat and its warehouses come
        // from different states.
        publishTags(owner, cloud.write(), Collections.emptyList());
    }

    /** Clears only a stopped node's input/output cache and preserves the node. */
    public void clearNodeCache(UUID owner, UUID id, UUID nodeId) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.clearNodeCache(owner, id, nodeId);
            return;
        }
        synchronized (this) {
            ProductionLineData line = owned(owner, id);
            if (line == null || line.enabled()) throw error("line_active");
            MachineNodeData node = line.machines().stream().filter(candidate -> candidate.nodeId().equals(nodeId))
                    .findFirst().orElseThrow(() -> error("not_found"));
            CloudworksRecycleBin bin = recycleBin(owner);
            NBTTagCompound previousLine = line.write(), previousBin = bin.write();
            try {
                node.cancelOperation();
                long discarded = recycleIntoBin(bin, node.inputs(), node.outputs(), node.templates());
                node.inputs().clear();
                node.outputs().clear();
                node.templates().clear();
                node.setProgress(0, 1);
                node.setState(node.active() ? "WAITING_INPUT" : "OFF");
                publishTags(owner, clouds.get(owner).write(), Collections.singletonList(line.write()), bin.write());
                for (CloudworksLineEditSession edit : activeEdits.values()) if (edit.line.equals(id)) {
                    edit.baseline = structuralLine(line.write());
                }
                recycleBins.put(owner, bin);
                changed(owner);
                if (discarded > 0) pendingRecycleOverflow.add(owner);
            } catch (RuntimeException failure) {
                lines.put(id, ProductionLineData.read(previousLine));
                recycleBins.put(owner, CloudworksRecycleBin.read(previousBin));
                throw failure;
            }
        }
    }

    private void writeCheckpoint(ProductionLineData line) {
        flushRuntimePending(line.owner());
        CloudworksData cloud = clouds.get(line.owner());
        if (persistence != null && lines.containsKey(line.lineId())
                && cloud != null && cloud.lineIds().contains(line.lineId())) {
            publish0(line.owner(), cloud.write(), Collections.singletonList(line.write()),
                    null, null, null, null, false, true);
            lines.put(line.lineId(), line);
            clouds.put(line.owner(), cloud);
            return;
        }
        // Interactive ordered write: bounded drain, queue behind a live backlog
        // (FIFO under the manager lock keeps disk state monotonically fresh).
        // The generation counter only advances after the write or queued submit
        // succeeded — a busy refusal must not leave a generation gap that the
        // strict intent replay cannot validate.
        long generation = Math.addExact(checkpointGenerations.getOrDefault(line.lineId(), 0L), 1L);
        NBTTagCompound tag = line.write();
        tag.setLong("generation", generation);
        orderedWrite(line.owner(), lineFile(line.owner(), line.lineId()), tag);
        checkpointGenerations.put(line.lineId(), generation);
        runtimeUnmaterialized.remove(line.lineId());
        guiLines.put(line.lineId(), tag);
    }


    /**
     * Outcome of a conditional virtual snapshot commit.
     */
    public enum CommitStatus {COMMITTED, CONFLICT, EMPTY}

    /**
     * A parked upload acknowledgement waiting for the IO backlog to clear.
     */
    private static final class PendingUploadAck {
        final ICloudworksUploadSource source;
        final UUID owner;

        PendingUploadAck(ICloudworksUploadSource source, UUID owner) {
            this.source = source;
            this.owner = owner;
        }
    }

    public static final class VirtualSnapshot {
        private final UUID originManager;
        private final long worldEpoch;
        private final UUID owner;
        private final long revision;
        private final long sessionGeneration;
        private final long registryVersion;
        private final long layoutFingerprint;
        private final int recipeVersion;
        private final int[] upgradeSettings;
        private final UUID transaction;
        private final long symbolGeneration;
        /**
         * One-shot entropy for the worker's per-operation chance rolls.
         */
        private final long transactionSeed;
        private final CloudworksData cloud;
        private final Map<UUID, NBTTagCompound> lines;
        private final java.util.concurrent.atomic.AtomicBoolean consumed =
                new java.util.concurrent.atomic.AtomicBoolean();

        private VirtualSnapshot(UUID originManager, long worldEpoch, UUID owner, long revision,
                                long sessionGeneration, long registryVersion, long layoutFingerprint,
                                int recipeVersion, UUID transaction,
                                long symbolGeneration, long transactionSeed, CloudworksData cloud, Map<UUID, NBTTagCompound> lines,
                                boolean frozenPublication) {
            this.originManager = originManager;
            this.worldEpoch = worldEpoch;
            this.owner = owner;
            this.revision = revision;
            this.sessionGeneration = sessionGeneration;
            this.registryVersion = registryVersion;
            this.layoutFingerprint = layoutFingerprint;
            this.recipeVersion = recipeVersion;
            this.upgradeSettings = mekanism.cloudworks.common.upgrade.CloudworksUpgradeEffects.settings();
            this.transaction = transaction;
            this.symbolGeneration = symbolGeneration;
            this.transactionSeed = transactionSeed;
            this.cloud = cloud.copyForRuntime();
            this.lines = new LinkedHashMap<>();
            lines.forEach((id, tag) -> this.lines.put(id, frozenPublication ? tag : tag.copy()));
        }

        public UUID owner() {
            return owner;
        }

        /**
         * One-shot transaction id; binds capture, routing result and commit together.
         */
        public UUID transaction() {
            return transaction;
        }

        /**
         * Dictionary generation of the session symbol table at capture time.
         */
        public long symbolGeneration() {
            return symbolGeneration;
        }

        /**
         * One-shot entropy drawn at capture; workers derive per-node random streams from it.
         */
        public long transactionSeed() {
            return transactionSeed;
        }

        /**
         * True only while this manager instance still owns the same world session.
         */
        boolean boundTo(UUID manager, long epoch) {
            return originManager.equals(manager) && worldEpoch == epoch;
        }

        /**
         * Burns the transaction credential; only a commit that will publish may call this.
         */
        boolean tryConsume() {
            return consumed.compareAndSet(false, true);
        }

        public CloudworksData cloud() {
            return cloud.copyForRuntime();
        }

        public List<ProductionLineData> lines() {
            List<ProductionLineData> copies = new ArrayList<>(lines.size());
            for (NBTTagCompound tag : lines.values()) copies.add(ProductionLineData.read(tag.copy()));
            return copies;
        }

        public void visitNodeTags(java.util.function.Consumer<NBTTagCompound> visitor) {
            for (NBTTagCompound line : lines.values()) {
                NBTTagList nodes = line.getTagList("machines", 10);
                for (int index = 0; index < nodes.tagCount(); index++) visitor.accept(nodes.getCompoundTagAt(index));
            }
        }

        public void visitDescriptorTags(java.util.function.Consumer<NBTTagCompound> visitor) {
            for (NBTTagCompound line : lines.values()) visitDescriptorTree(line, visitor);
        }

        private static void visitDescriptorTree(net.minecraft.nbt.NBTBase value,
                java.util.function.Consumer<NBTTagCompound> visitor) {
            if (value instanceof NBTTagCompound tag) {
                if (tag.hasKey("descriptor", 10)) visitor.accept(tag.getCompoundTag("descriptor"));
                if (tag.hasKey("resourceFilter", 10)) visitor.accept(tag.getCompoundTag("resourceFilter"));
                for (String key : tag.getKeySet())
                    if (!key.equals("machine") && !key.equals("descriptor") && !key.equals("resourceFilter"))
                        visitDescriptorTree(tag.getTag(key), visitor);
            } else if (value instanceof NBTTagList list)
                for (int index = 0; index < list.tagCount(); index++) visitDescriptorTree(list.get(index), visitor);
        }

        public boolean hasHeatFuel() {
            boolean[] heat = new boolean[1];
            java.util.function.Predicate<NBTTagCompound> needsFuel = CloudworksNativeContracts.thermalFuelMatcher();
            visitNodeTags(tag -> {
                if (heat[0]) return;
                NBTTagCompound machine = tag.getCompoundTag("machine");
                heat[0] = needsFuel.test(machine);
            });
            return heat[0];
        }
    }
}
