package mekanism.cloudworks.common.service;

import mekanism.cloudworks.common.blueprint.CloudworksBlueprint;
import mekanism.cloudworks.common.blueprint.CloudworksPendingBlueprint;
import mekanism.cloudworks.common.network.CloudworksLineDraftCodec;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;

import mekanism.cloudworks.api.transfer.CloudworksUploadReservation;
import mekanism.cloudworks.api.transfer.CloudworksUploadResult;
import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.cloudworks.api.transfer.ICloudworksUploadSource;
import mekanism.cloudworks.common.data.*;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.upgrade.CloudworksMachineModuleRegistry;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetypes;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeStore;
import mekanism.cloudworks.common.special.CloudworksInfiniteResourceCardData;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.energy.IStrictEnergyHandler;
import mekanism.common.integration.energy.EnergyCompatUtils;
import mekanism.common.item.ItemBlockEnergyCube;
import mekanism.common.recipe.RecipeHandler;
import mekanism.common.tier.BaseTier;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;
import java.io.File;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import mekanism.api.inventory.IInventorySlot;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.transfer.CloudworksDepositRequest;
import mekanism.cloudworks.api.CloudworksEvent;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.cloudworks.api.transfer.CloudworksTransferRequest;
import mekanism.cloudworks.api.transfer.CloudworksTransferResult;
import mekanism.cloudworks.api.module.CloudworksUpgradeItems;
import mekanism.cloudworks.common.card.CloudworksCardEffect;
import mekanism.cloudworks.common.card.CloudworksCardEffects;
import mekanism.cloudworks.common.config.CloudworksConfig;
import mekanism.cloudworks.common.content.CloudworksHybridTank;
import mekanism.cloudworks.common.content.CloudworksPortBinding;
import mekanism.cloudworks.common.content.CloudworksPortIo;
import mekanism.cloudworks.common.content.CloudworksTerminalHost;
import mekanism.cloudworks.common.content.CloudworksTerminalSlots;
import mekanism.cloudworks.common.registries.CloudworksItems;
import mekanism.cloudworks.common.registry.CloudworksAuxiliaryNodeRegistry;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.common.registry.MachineKey;
import mekanism.cloudworks.common.runtime.CloudworksExternalEvent;
import mekanism.cloudworks.common.runtime.CloudworksRuntimeCheckpoint;
import mekanism.cloudworks.common.runtime.CloudworksRuntimeIngressJournal;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetype;
import mekanism.cloudworks.common.team.CloudworksTeamContext;
import mekanism.cloudworks.common.team.CloudworksTeamManager;
import mekanism.cloudworks.common.tile.prefab.TileEntityCloudworksPort;
import mekanism.cloudworks.common.tile.TileEntityCloudDashboard;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeEffects;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeLimits;
import mekanism.common.Mekanism;
import mekanism.common.Upgrade;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagString;

/**
 * World-scoped records. Unreadable records are never replaced with empty data.
 */
public final class CloudworksPlayerDataManager {
    public static final CloudworksPlayerDataManager INSTANCE = new CloudworksPlayerDataManager();
    public static final int PAGE_SIZE = 32;
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
    public static final AtomicLong DEBUG_LINE_READS
            = new AtomicLong();
    public static final AtomicLong DEBUG_INTENT_APPLIES
            = new AtomicLong();
    public static final AtomicLong DEBUG_FILE_WRITES =
            new AtomicLong();
    public static final AtomicLong DEBUG_BYTES_WRITTEN =
            new AtomicLong();
    public static final AtomicLong DEBUG_FILE_READS =
            new AtomicLong();
    public static final AtomicLong DEBUG_CACHED_FILE_READS = new AtomicLong();
    public static final AtomicLong DEBUG_CACHE_VERIFICATION_READS = new AtomicLong();
    public static final AtomicLong DEBUG_RUNTIME_LINE_DECODES = new AtomicLong();
    public static final AtomicLong DEBUG_PROJECTION_BUILDS =
            new AtomicLong();
    public static final AtomicLong DEBUG_PROJECTION_HASHES =
            new AtomicLong();
    private final CloudworksPlayerDataManager parent;
    private final Map<UUID, CloudworksPlayerDataManager> domains = new ConcurrentHashMap<>();
    private final Map<UUID, CloudworksPlayerDataManager> lineDomains = new ConcurrentHashMap<>();
    private final Map<UUID, CloudworksPlayerDataManager> terminalDomains = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> terminalOwners = new ConcurrentHashMap<>();
    private final Map<UUID, CloudworksData> cloudViews = new ConcurrentHashMap<>();
    private volatile boolean closing;
    private final Map<UUID, TerminalSession> terminalSessions = new HashMap<>();
    private final Map<UUID, CloudworksLineEditSession> activeEdits = new HashMap<>();
    private final Map<UUID, CloudworksPendingBlueprint> pendingBlueprints = new HashMap<>();
    private volatile int globalSimulatedTick;
    private final Map<UUID, CloudworksData> clouds = new LinkedHashMap<UUID, CloudworksData>() {
        @Override
        public CloudworksData put(UUID owner, CloudworksData cloud) {
            if (parent != null) parent.cloudViews.put(owner, cloud);
            return super.put(owner, cloud);
        }
    };
    private final Map<UUID, NBTTagCompound> guiLines = new HashMap<>(),
            guiMachines = new HashMap<>(), guiRecycle = new HashMap<>();
    private final Set<UUID> runtimeUnmaterialized = new HashSet<>();
    private final Map<UUID, mekanism.cloudworks.common.runtime.CloudworksRuntimeLineSnapshot> runtimeLineSnapshots = new HashMap<>();
    private final Map<UUID, ProductionLineData> lines = new LinkedHashMap<UUID, ProductionLineData>() {
        @Override
        public ProductionLineData put(UUID id, ProductionLineData line) {
            if (parent != null) parent.lineDomains.put(id, CloudworksPlayerDataManager.this);
            return super.put(id, line);
        }
    };
    /**
     * Owner-level machine caches; the single physical home of every machine.
     */
    private final Map<UUID, CloudworksMachineCache> machineCaches = new LinkedHashMap<>();
    private final Map<UUID, CloudworksSpecialNodeStore> specialNodeStores = new LinkedHashMap<>();
    /**
     * Owner-level recycle bins; receiver for deleted node/line resources.
     */
    private final Map<UUID, CloudworksRecycleBin> recycleBins = new LinkedHashMap<>();
    /**
     * Owners with recycle overflow events waiting for a chat+toast notice on the main thread.
     */
    private Set<UUID> pendingRecycleOverflow = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> revisions = new HashMap<>();
    private final Map<UUID, Long> commandRevisions = new HashMap<>();
    private final Map<UUID, Set<UUID>> runtimeDirtyLines = new LinkedHashMap<>();
    private final Map<UUID, CloudworksWarehouseDelta> runtimeWarehouseDeltas = new LinkedHashMap<>();
    private final Map<UUID, CloudworksData> durableCloudBaselines = new HashMap<>();
    private final Map<UUID, CloudworksRuntimeIngressJournal> runtimeIngress =
            new HashMap<>();
    private final Map<UUID, Long> checkpointGenerations = new HashMap<>();
    /**
     * Transfer ids whose intent was fully applied by the IO worker this session.
     */
    private final Set<UUID> durableUploadTransfers =
            ConcurrentHashMap.newKeySet();
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
    private ConcurrentMap<UUID, List<NBTTagCompound>> sourceDebts =
            new ConcurrentHashMap<>();
    private final Map<UUID, List<NBTTagCompound>> pendingSourceDebtWrites = new LinkedHashMap<>();
    /**
     * Owners whose durable batches failed; their operations refuse until reload (isolation). Lock-free set: the IO thread writes without the manager lock.
     */
    private Set<UUID> failedOwners = ConcurrentHashMap.newKeySet();
    private ConcurrentMap<UUID, Throwable> ownerFailureCauses = new ConcurrentHashMap<>();
    /**
     * IO-thread steps of the durable pipeline; no manager lock is taken here.
     */
    private final CloudworksPersistenceViews persistenceViews = new CloudworksPersistenceViews(128, 16L * 1024 * 1024);
    private final CloudworksPersistenceQueue.Sink ioSink = new CloudworksPersistenceQueue.Sink() {
        @Override
        public void beginBatch() {
            IO_READS.set(new IoReads(persistenceViews));
        }

        @Override
        public void commitBatch() {
            forcePendingProjections();
            IoReads cache = IO_READS.get();
            if (cache == null) return;
            for (Runnable acknowledgement : cache.acknowledgements) acknowledgement.run();
            cache.acknowledgements.clear();
            if (!cache.diskOnly)
                for (Map.Entry<Path, NBTTagCompound> entry : cache.tags.entrySet())
                    cache.shared.put(entry.getKey(), entry.getValue(), cache.fingerprints.get(entry.getKey()),
                            cache.fileDigests.get(entry.getKey()));
            cache.committed = true;
        }

        @Override
        public void endBatch() {
            IoReads cache = IO_READS.get();
            if (cache != null && !cache.committed) cache.shared.clear();
            IO_READS.remove();
        }

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
            } else {
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
                if (fileWrite.monitor == null)
                    write(fileWrite.path, preservePortWatermark(fileWrite.path, fileWrite.tag));
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
    private final Map<UUID, Map<QIOResourceDescriptor, List<Runnable>>> typeUnblockListeners
            = new LinkedHashMap<>();
    /**
     * Debit-recovery shortfalls observed; audit counter, diagnostics only.
     */
    private AtomicLong debugDebitRecoveryShortfalls =
            new AtomicLong();
    /**
     * Bounded server-thread drain budget for interactive terminal/source
     * admission. Structural commands run off-thread; physical boundaries may
     * wait briefly for their preceding durable batch.
     */
    private volatile long interactionDrainMillis = 500L;
    /**
     * Increases whenever the world session changes; old-session results are rejected.
     */
    private volatile long worldEpoch;
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
    private Object teamRecoveryLock = new Object();
    /**
     * Last applied recovery-machine drain batch per dissolved team (buffer-file mirror).
     */
    private final Map<UUID, Long> teamRecoveryBatches = new HashMap<>();
    /**
     * Recovery buffers whose contents were found without a trustworthy lastBatch ledger.
     */
    private final Set<UUID> recoveryLedgerInvalid;
    /**
     * One live recovery buffer per dissolved estate; separate tiles must never load stale copies.
     */
    private final Map<UUID, Object> teamRecoveryMounts = new HashMap<>();
    /**
     * Team identities known to the current world session; shared by all owner domains.
     */
    private final Set<UUID> teamOwnerIds;
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
        this.teamOwnerIds = ConcurrentHashMap.newKeySet();
        this.recoveryLedgerInvalid = ConcurrentHashMap.newKeySet();
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
        teamRecoveryLock = parent.teamRecoveryLock;
        teamOwnerIds = parent.teamOwnerIds;
        recoveryLedgerInvalid = parent.recoveryLedgerInvalid;
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

    /**
     * Lock only this owner; different owners never share a mutable state monitor.
     */
    public Object ownerLock(UUID owner) {
        return domain(owner);
    }

    long worldEpoch() {
        return parent == null ? worldEpoch : parent.worldEpoch;
    }

    boolean acceptsApiRequests(long epoch) {
        CloudworksPlayerDataManager manager = parent == null ? this : parent;
        return !manager.closing && manager.root != null && manager.worldEpoch == epoch;
    }

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
    private static long recycleIntoBin(CloudworksRecycleBin bin,
                                       List<CloudworksResourceStack>... inventories) {
        long discarded = 0;
        for (List<CloudworksResourceStack> inventory : inventories)
            for (CloudworksResourceStack stack : inventory)
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
                "state", "inputs", "outputs", "templates", "operation", "outputSequence", "thermalState",
                "executionBranches", "generationRemainders");
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
        CloudworksMachineCache.read(machineCacheTag.copy());
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
        if ("auxiliary".equals(transferTag.getString("kind"))) {
            CloudworksAuxiliaryNodeData claimed = null;
            for (CloudworksAuxiliaryNodeData node : line.auxiliaries())
                if (node.nodeId().equals(nodeId)) claimed = node;
            if (claimed == null || !transferId.equals(claimed.transferId())
                    || !sourceId.equals(claimed.sourceId()) || machineTotal != 0
                    || transferTag.getInteger("count") != 1) throw dataError(null);
            return;
        }
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
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            throw dataError(failure);
        }
    }

    private static void requireSchema(NBTTagCompound tag, int supported) {
        if (tag == null || tag.getInteger("schema") != supported) throw dataError(null);
    }

    private static final ThreadLocal<IoReads> IO_READS = new ThreadLocal<>();

    private static final class IoReads {
        final CloudworksPersistenceViews shared;
        boolean committed, diskOnly;
        final Map<Path, NBTTagCompound> tags = new HashMap<>();
        final Map<Path, byte[]> fingerprints = new HashMap<>();
        final Map<Path, byte[]> fileDigests = new HashMap<>();
        final Map<NBTTagCompound, ProjectionPlan> projections = new IdentityHashMap<>();
        final Set<Path> pendingForces = new LinkedHashSet<>();
        final List<Runnable> acknowledgements = new ArrayList<>();
        IoReads(CloudworksPersistenceViews shared) { this.shared = shared; }
    }

    private static final class ProjectionPlan {
        final CloudworksData cloud;
        final Map<String, Path> paths;
        final Map<String, NBTTagCompound> next = new LinkedHashMap<>();
        final Map<String, byte[]> fingerprints = new HashMap<>();
        NBTTagList lines;

        ProjectionPlan(CloudworksData cloud, Map<String, Path> paths) {
            this.cloud = cloud;
            this.paths = paths;
        }
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

    /**
     * Keep the intent/journal until every projection in its batch reaches the durability barrier.
     */
    private static void forcePendingProjections() {
        IoReads cache = IO_READS.get();
        if (cache == null) return;
        for (Path file : cache.pendingForces) {
            // A later projection in the same transaction may intentionally
            // remove the file (for example when a recovery source is
            // retired). Its absence already means there is nothing left to
            // force at the durability barrier.
            if (!Files.exists(file)) continue;
            try (FileChannel channel = FileChannel.open(
                    file, StandardOpenOption.WRITE)) {
                channel.force(true);
            } catch (IOException failure) {
                throw dataError(failure);
            }
        }
        cache.pendingForces.clear();
    }

    private static NBTTagCompound read(Path file) {
        NBTTagCompound tag = readView(file);
        return IO_READS.get() == null ? tag : tag.copy();
    }

    /**
     * Internal read-only consumers share a batch snapshot; mutable callers use read().
     */
    private static NBTTagCompound readView(Path file) {
        IoReads cache = IO_READS.get();
        if (cache != null && cache.tags.containsKey(file)) return cache.tags.get(file);
        if (cache != null && !cache.diskOnly) {
            CloudworksPersistenceViews.Entry entry = cache.shared.get(file);
            if (entry != null) {
                cache.tags.put(file, entry.tag);
                cache.fileDigests.put(file, entry.fileDigest);
                if (entry.fingerprint != null) cache.fingerprints.put(file, entry.fingerprint);
                DEBUG_CACHED_FILE_READS.incrementAndGet();
                return entry.tag;
            }
        }
        java.security.MessageDigest digest = CloudworksPersistenceViews.newDigest();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new java.security.DigestInputStream(Files.newInputStream(file), digest)))) {
            NBTTagCompound tag = CompressedStreamTools.read(input);
            while (input.read() != -1) { /* Include any trailing bytes in the file identity. */ }
            DEBUG_FILE_READS.incrementAndGet();
            if (tag == null) throw dataError(null);
            if (cache != null) {
                cache.tags.put(file, tag);
                cache.fileDigests.put(file, digest.digest());
            }
            return tag;
        } catch (IOException e) {
            throw dataError(e);
        }
    }

    private static byte[] diskFingerprint(Path file) {
        IoReads cache = IO_READS.get();
        if (cache == null) return CloudworksNbtFingerprint.of(readView(file));
        byte[] fingerprint = cache.fingerprints.get(file);
        if (fingerprint != null) return fingerprint;
        NBTTagCompound view = readView(file);
        fingerprint = cache.fingerprints.get(file);
        if (fingerprint == null) {
            fingerprint = CloudworksNbtFingerprint.of(view);
            cache.fingerprints.put(file, fingerprint);
        }
        return fingerprint;
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
            // Close the buffered stream before forcing or replacing its file.
            // These are the vanilla uncompressed NBT overloads.
            java.security.MessageDigest digest = CloudworksPersistenceViews.newDigest();
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                    new java.security.DigestOutputStream(Files.newOutputStream(temporary), digest)))) {
                CompressedStreamTools.write(tag, output);
            }
            long writtenBytes = Files.size(temporary);
            IoReads batch = IO_READS.get();
            boolean immediate = batch == null || file.getFileName().toString().endsWith(".pending.dat")
                    || file.getParent().getFileName().toString().equals("port-journal");
            if (immediate) {
                try (FileChannel channel = FileChannel.open(
                        temporary, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            }
            // Do not fall back to truncating the live file if atomic replacement is unavailable.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (!immediate) batch.pendingForces.add(file);
            DEBUG_FILE_WRITES.incrementAndGet();
            DEBUG_BYTES_WRITTEN.addAndGet(writtenBytes);
            IoReads cache = IO_READS.get();
            if (cache != null) {
                cache.tags.put(file, tag.copy());
                cache.fileDigests.put(file, digest.digest());
                cache.fingerprints.remove(file);
            }
        } catch (IOException e) {
            throw dataError(e);
        } finally {
            if (temporary != null) try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                Mekanism.logger.debug("Could not remove temporary Cloudworks data file {}",
                        temporary, ignored);
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
        if (worldDirectory == null)
            throw new IllegalArgumentException("Cloudworks world directory cannot be null");
        Path next = worldDirectory.toPath().toAbsolutePath().normalize().resolve("cloudworks");
        if (next.equals(root)) {
            if (recoveryRequired || closing)
                throw dataError(recoveryCause);
            return;
        }
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
        persistenceViews.clear();
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
        teamRecoveryMounts.clear();
        teamRecoveryBatches.clear();
        recoveryLedgerInvalid.clear();
        teamOwnerIds.clear();
        typeUnblockListeners.clear();
        symbolTables.clear();
        resourceIdCaches.clear();
        sessionGenerations.clear();
        machineCaches.clear();
        specialNodeStores.clear();
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
        root = next;
        loadTeamOwnerIds();
        portJournal = new CloudworksPortJournal(next, new CloudworksPortJournal.Storage() {
            @Override
            public NBTTagCompound read(Path file) {
                return CloudworksPlayerDataManager.read(file);
            }

            @Override
            public void write(Path file, NBTTagCompound tag) {
                CloudworksPlayerDataManager.write(file, tag);
            }

            @Override
            public void flush() {
                forcePendingProjections();
            }
        }, this::ownerDirectory);
        persistence = new CloudworksPersistenceQueue(ioCapacity, ioSink, "Cloudworks-IO");
    }

    public synchronized void close() {
        persistenceViews.clear();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        boolean interrupted = false;
        if (parent == null) {
            synchronized (domains) {
                closing = true;
            }
            for (CloudworksPlayerDataManager domain : domains.values()) {
                while (persistence != null && !persistence.dead() && !domain.pendingSourceDebtWrites.isEmpty()) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) {
                        Mekanism.logger.warn(
                                "Cloudworks shutdown deadline exceeded while flushing source debt");
                        break;
                    }
                    synchronized (domain) {
                        domain.flushSourceDebtWrites();
                    }
                    try {
                        persistence.drain(Math.max(1, Math.min(30_000,
                                TimeUnit.NANOSECONDS.toMillis(remaining))));
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                    }
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
                synchronized (domain) {
                    domain.close();
                }
            }
            recoveryRequired |= dead;
        }
        boolean crashed = persistence != null && persistence.dead();
        if (persistence != null) {
            while (!persistence.dead() && !pendingSourceDebtWrites.isEmpty()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    Mekanism.logger.warn(
                            "Cloudworks shutdown deadline exceeded while flushing source debt");
                    break;
                }
                flushSourceDebtWrites();
                try {
                    persistence.drain(Math.max(1, Math.min(30_000,
                            TimeUnit.NANOSECONDS.toMillis(remaining))));
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining > 0) try {
                persistence.drain(Math.max(1, Math.min(30_000,
                        TimeUnit.NANOSECONDS.toMillis(remaining))));
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
                // Owner data now lives below player/<uuid> or team/<uuid>.
                Set<UUID> scannedOwners = new HashSet<>();
                for (Path ownerRoot : Arrays.asList(root.resolve("player"), root.resolve("team"))) {
                    if (!Files.isDirectory(ownerRoot)) continue;
                    try (DirectoryStream<Path> owners = Files.newDirectoryStream(ownerRoot)) {
                        for (Path ownerDirectory : owners) {
                            if (!Files.isDirectory(ownerDirectory)) continue;
                            try {
                                UUID owner = UUID.fromString(ownerDirectory.getFileName().toString());
                                if (!scannedOwners.add(owner)
                                        || clouds.containsKey(owner) || failedOwners.contains(owner)) continue;
                                replayPendingTransfer(owner);
                                if (!failedOwners.contains(owner) && Files.exists(pendingIntentFile(owner)))
                                    deleteTransferIntent(pendingIntentFile(owner));
                            } catch (IllegalArgumentException ignored) {
                                Mekanism.logger.debug(
                                        "Skipping non-owner directory during shutdown recovery: {}",
                                        ownerDirectory, ignored);
                            }
                        }
                    } catch (IOException failure) {
                        throw dataError(failure);
                    }
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
        specialNodeStores.clear();
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
        persistenceViews.clear();
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
                synchronized (domain) {
                    domain.flushSourceDebtWrites();
                }
        } else synchronized (this) {
            flushSourceDebtWrites();
        }
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

    /**
     * Test hook for the bounded interactive drain budget.
     */
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
                        if (!topology.valid()) {
                            if (!"editor_transfer_channels_direct".equals(topology.errorKey()))
                                throw new IllegalArgumentException("Invalid persisted topology: " + topology.reason());
                            line.setEnabled(false);
                            if (Mekanism.logger != null)
                                Mekanism.logger.warn(
                                        "Stopped Cloudworks line {} with a forbidden transfer boundary connection", id);
                        }
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
                    if (Mekanism.logger != null)
                        Mekanism.logger.warn("Rejected Cloudworks pending blueprint {}", blueprintFile, failure);
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

    /**
     * Loads an existing cloud record without creating state for an unknown owner.
     */
    CloudworksData loadIfExists(UUID owner) {
        if (parent == null) return domain(owner).loadIfExists(owner);
        synchronized (this) {
            Objects.requireNonNull(owner);
            requireLoaded();
            if (recoveryRequired || failedOwners.contains(owner))
                throw dataError(ownerFailureCauses.getOrDefault(owner, recoveryCause));
            CloudworksData cached = clouds.get(owner);
            if (cached != null) return cached;
            return Files.exists(cloudFile(owner)) ? getOrCreate(owner) : null;
        }
    }

    public CloudworksSpecialNodeStore.Snapshot specialNodeSnapshot(UUID owner) {
        if (parent == null) return domain(owner).specialNodeSnapshot(owner);
        synchronized (this) {
            requireLoaded();
            requireEnabled(owner);
            return specialNodeStore(owner).load();
        }
    }

    public CloudworksSpecialNodeStore.AddResult addInfiniteResourceCard(
            UUID owner, CloudworksInfiniteResourceCardData card) {
        if (parent == null) return domain(owner).addInfiniteResourceCard(owner, card);
        synchronized (this) {
            requireLoaded();
            CloudworksData cloud = requireEnabled(owner).copyForRuntime();
            CloudworksSpecialNodeStore store = specialNodeStore(owner);
            CloudworksSpecialNodeStore.Change change = store.load().add(card);
            if (change.result == CloudworksSpecialNodeStore.AddResult.DUPLICATE) return change.result;
            store.requireWritable();
            publish0(owner, cloud.write(), Collections.emptyList(), null, null, null, null,
                    true, false, null, Collections.emptyList(), change.next);
            return change.result;
        }
    }

    private CloudworksSpecialNodeStore specialNodeStore(UUID owner) {
        return specialNodeStores.computeIfAbsent(owner, id -> new CloudworksSpecialNodeStore(
                specialNodeDirectory(id), CloudworksPlayerDataManager::read));
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
        if (removed > 0 && Mekanism.logger != null)
            Mekanism.logger.warn("Removed {} orphan Cloudworks blueprint nodes for owner {}", removed, owner);
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
            if (Mekanism.logger != null)
                Mekanism.logger.warn(
                        "Discarded legacy Cloudworks pending blueprint from recovery intent; removed {} waiting nodes", removed);
        }
    }

    private void cleanupUnindexedLineFiles(UUID owner, CloudworksData cloud) {
        Path production = ownerDirectory(owner).resolve("production");
        if (!Files.isDirectory(production)) return;
        Set<UUID> indexed = new HashSet<>(cloud.lineIds());
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(production, "*.dat")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                boolean runtime = name.endsWith(".runtime.dat");
                String uuidText = name.substring(0, name.length() - (runtime ? 12 : 4));
                UUID id;
                try {
                    id = UUID.fromString(uuidText);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (indexed.contains(id)) continue;
                if (Files.deleteIfExists(file)) removed++;
            }
        } catch (IOException | DirectoryIteratorException failure) {
            throw dataError(failure);
        }
        if (removed > 0 && Mekanism.logger != null)
            Mekanism.logger.warn("Removed {} unindexed Cloudworks production files for owner {}",
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
        synchronized (this) {
            return root != null && portMounts.get(id) == holder;
        }
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
                Path file = ownerDirectory(owner).resolve("ports").resolve(id + ".dat");
                if (Files.exists(file)) record = CloudworksPortRecord.read(read(file));
                else if (fresh) {
                    record = CloudworksPortRecord.empty(id, owner, input);
                    dirtyPorts.add(id);
                } else {
                    Mekanism.logger.warn(
                            "Cloudworks port record {} for owner {} is missing; recreating an empty buffer",
                            id, owner);
                    record = CloudworksPortRecord.empty(id, owner, input);
                    dirtyPorts.add(id);
                }
                ports.put(id, record);
            }
            if (!record.id.equals(id) || !record.owner.equals(owner) || record.input != input) throw dataError(null);
            portMounts.put(id, holder);
            return record;
        }
    }

    public void unmountPort(UUID owner, UUID id, Object holder) {
        if (parent == null) {
            domain(owner).unmountPort(owner, id, holder);
            return;
        }
        synchronized (this) {
            portMounts.remove(id, holder);
        }
    }

    public void savePort(UUID owner, UUID id, Object holder, CloudworksPortRecord record) {
        if (parent == null) {
            domain(owner).savePort(owner, id, holder, record);
            return;
        }
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
                if (holder instanceof CloudworksPortBinding binding) binding.flush();
        }
    }

    public void capturePort(UUID owner, UUID id, Object holder, CloudworksPortRecord record) {
        if (parent == null) {
            domain(owner).capturePort(owner, id, holder, record);
            return;
        }
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
                try {
                    publishPort(owner, operation);
                    dirtyPorts.remove(id);
                } catch (RuntimeException busy) {
                    return;
                }
            }
            NBTTagCompound maintenance = new NBTTagCompound();
            maintenance.setInteger("schema", 3);
            maintenance.setString("owner", owner.toString());
            try {
                portMaintenancePending = true;
                persistence.submitIntent(owner, maintenance);
            } catch (RuntimeException busy) {
                portMaintenancePending = false;
            }
        }
    }

    public int transferPort(UUID owner, UUID id, Object holder, List<IInventorySlot> slots,
                            List<CloudworksHybridTank> tanks, double energy,
                            DoubleConsumer setEnergy, boolean[] grace) {
        return transferPort(owner, id, holder, slots, tanks, energy,
                TileEntityCloudworksPort.MAX_ENERGY, setEnergy, grace);
    }

    public int transferPort(UUID owner, UUID id, Object holder, List<IInventorySlot> slots,
                            List<CloudworksHybridTank> tanks, double energy, double maxEnergy,
                            DoubleConsumer setEnergy, boolean[] grace) {
        if (!Double.isFinite(maxEnergy) || maxEnergy < 0) throw new IllegalArgumentException("Invalid port capacity");
        if (parent == null)
            return domain(owner).transferPort(owner, id, holder, slots, tanks, energy, maxEnergy, setEnergy, grace);
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
                    CloudworksPortIo.UploadPlan plan =
                            CloudworksPortIo.planUpload(slots, tanks,
                                    descriptor -> cloud.receiving().insertable(descriptor, Long.MAX_VALUE,
                                            cloud.effectiveWarehouseItemCap()), grace,
                                    cloud.receiving().room());
                    if (plan.pauses()) blocked = plan.pauseIndex;
                    for (Map.Entry<QIOResourceDescriptor, Long> entry : plan.fitting.entrySet()) {
                        resourceIds.put(entry.getKey(), resourceId(owner, entry.getKey()));
                        cloud.receiving().insert(entry.getKey(), entry.getValue(), cloud.effectiveWarehouseItemCap());
                        moved.put(entry.getKey(), entry.getValue());
                    }
                    CloudworksPortIo.applyDrains(slots, tanks, plan.takes);
                    double accepted = Math.min(energy, Math.max(0D, cloud.receivingEnergyCapacity() - previousEnergy));
                    nextPortEnergy -= accepted;
                    nextWarehouseEnergy += accepted;
                } else {
                    Map<QIOResourceDescriptor, Long> available = cloud.sending().snapshot();
                    Map<QIOResourceDescriptor, Long> request =
                            CloudworksPortIo.buildRequest(available, slots, tanks);
                    for (Map.Entry<QIOResourceDescriptor, Long> entry : request.entrySet()) {
                        resourceIds.put(entry.getKey(), resourceId(owner, entry.getKey()));
                        long amount = cloud.sending().extract(entry.getKey(), entry.getValue());
                        moved.put(entry.getKey(), amount);
                        CloudworksPortIo.insertPlanned(slots, tanks, entry.getKey(), amount);
                    }
                    double taken = Math.min(previousEnergy, Math.max(0D,
                            maxEnergy - energy));
                    nextPortEnergy += taken;
                    nextWarehouseEnergy -= taken;
                    boolean supported = available.keySet().stream().anyMatch(
                            CloudworksPortIo::isSupportedDownloadResource);
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
                                ? CloudworksExternalEvent.Kind.RECEIVING_RESOURCE
                                : CloudworksExternalEvent.Kind.SENDING_RESOURCE,
                        resourceIds.get(entry.getKey()), entry.getValue(), 0);
            }
            if (nextWarehouseEnergy != previousEnergy) {
                changedFromRuntime(owner);
                recordRuntimeIngress(owner, before.input
                                ? CloudworksExternalEvent.Kind.RECEIVING_ENERGY
                                : CloudworksExternalEvent.Kind.SENDING_ENERGY,
                        -1, 0, Math.abs(nextWarehouseEnergy - previousEnergy));
            }
            durableCloudBaselines.put(owner, cloud.copyForRuntime());
            return blocked;
        }
    }

    private void clearGuiSources() {
        runtimeUnmaterialized.clear();
        runtimeLineSnapshots.clear();
        guiLines.clear();
        guiMachines.clear();
        guiRecycle.clear();
    }

    /** Reader boundary: current immutable values are materialized once per publication. */
    private NBTTagCompound publishedLineTag(UUID id) {
        NBTTagCompound tag = guiLines.get(id);
        if (tag == null) {
            mekanism.cloudworks.common.runtime.CloudworksRuntimeLineSnapshot snapshot = runtimeLineSnapshots.get(id);
            if (snapshot != null) {
                tag = snapshot.write();
                guiLines.put(id, tag);
            }
        }
        return tag;
    }

    /**
     * Only copies the small line index. Frozen payloads are decoded after releasing the manager lock.
     */
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
                    parent == null ? globalSimulatedTick : parent.globalSimulatedTick,
                    specialNodeStore(owner).load(), runtimeLineSnapshots);
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

    /**
     * Optimistic GUI control version; progress and physical port movement do not invalidate a command.
     */
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
        CloudworksAPI.emit(new CloudworksEvent(owner, null,
                CloudworksEvent.Kind.CLOUD_CHANGED, revisions.get(owner), 1, ""));
    }

    private void changedFromRuntime(UUID owner) {
        revisions.put(owner, Math.addExact(revisions.get(owner), 1));
    }

    private void recordRuntimeIngress(UUID owner, CloudworksExternalEvent.Kind kind,
                                      int resource, long amount, double energy) {
        runtimeIngress.computeIfAbsent(owner, ignored ->
                        new CloudworksRuntimeIngressJournal())
                .record(revisions.get(owner), kind, resource, amount, energy);
    }

    public long runtimeExternalSequence(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.runtimeExternalSequence(owner);
        }
        synchronized (this) {
            CloudworksRuntimeIngressJournal journal = runtimeIngress.get(owner);
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
            CloudworksRuntimeIngressJournal journal = runtimeIngress.get(owner);
            return journal != null && journal.covers(previousRevision, current);
        }
    }

    public List<CloudworksExternalEvent> runtimeIngressAfter(
            UUID owner, long sequence) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.runtimeIngressAfter(owner, sequence);
        }
        synchronized (this) {
            CloudworksRuntimeIngressJournal journal = runtimeIngress.get(owner);
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
                NBTTagCompound published = publishedLineTag(id);
                if (published == null) throw new IllegalStateException("Missing Runtime line publication");
                lines.put(id, ProductionLineData.read(published.copy()));
                runtimeUnmaterialized.remove(id);
            }
            return lines.get(id);
        }
    }

    /**
     * Normal tick eligibility reads metadata only, not machine inventories.
     */
    public List<UUID> runtimeLineIds(CloudworksData cloud, CloudworksSessionPolicy sessions) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(cloud.owner());
            return target.runtimeLineIds(cloud, sessions);
        }
        synchronized (this) {
            List<UUID> selected = new ArrayList<>();
            for (UUID id : cloud.lineIds()) {
                mekanism.cloudworks.common.runtime.CloudworksRuntimeLineSnapshot snapshot = runtimeLineSnapshots.get(id);
                if (snapshot != null) {
                    if (snapshot.explicitRouting() && sessions.canRun(cloud, snapshot.owner(),
                            snapshot.enabled(), snapshot.runWhileOwnerOffline())) selected.add(id);
                    continue;
                }
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
    public Map<QIOResourceDescriptor, Long> sendingSnapshot(UUID owner) {
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
    public Map<QIOResourceDescriptor, Long> receivingSnapshot(UUID owner) {
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
    public Map<QIOResourceDescriptor, Long> transferSnapshot(UUID owner) {
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
            CloudworksMachineCache cache = machineCache(owner);
            if (typeIndex < 0 || typeIndex >= cache.typeCount()) throw error("not_found");
            Map<Integer, Map<NBTTagCompound, Long>> occupied = deriveOccupation(owner, cache);
            Map<NBTTagCompound, Long> byTag = occupied.getOrDefault(typeIndex, Collections.emptyMap());
            List<Map<String, Object>> view = new ArrayList<>();
            List<CloudworksMachineCache.Entry> entries = cache.entries();
            for (CloudworksMachineCache.Variant variant : entries.get(typeIndex).variants()) {
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
    public long receivingRoom(UUID owner, QIOResourceDescriptor descriptor) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.receivingRoom(owner, descriptor);
        }
        synchronized (this) {
            CloudworksData cloud = getOrCreate(owner);
            return cloud.receiving().insertable(descriptor, Long.MAX_VALUE, cloud.effectiveWarehouseItemCap());
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

    /**
     * 删线/卸机器溢出回池（内部路径——调用方在同一意图里已携带 cloud 投影）。
     */
    private void returnUpgradesToPool(CloudworksData cloud, int speedCount, int energyCount) {
        if (speedCount > 0) cloud.addUpgradePool(true, speedCount);
        if (energyCount > 0) cloud.addUpgradePool(false, energyCount);
    }

    private void refreshLineUpgrades(CloudworksData cloud, ProductionLineData line) {
        int speed = line.speedUpgrades(), energy = line.energyUpgrades();
        double storedEnergy = line.energy();
        line.recomputeUpgradeLimits();
        returnUpgradesToPool(cloud, speed - line.speedUpgrades(), energy - line.energyUpgrades());
        settleNodeCustomUpgrades(cloud, line,
                CloudworksUpgradeLimits.customAssignments(line.machines()));
        settleLineEnergy(cloud, line, storedEnergy);
    }

    /**
     * The line carrier backs node-owned assets; unused amounts always belong in the cloud.
     */
    private void settleNodeCustomUpgrades(CloudworksData cloud, ProductionLineData line,
                                          Map<ResourceLocation, Long> assigned) {
        Set<ResourceLocation> types = new LinkedHashSet<>();
        line.customUpgradePool().ids().forEach(types::add);
        types.addAll(assigned.keySet());
        for (ResourceLocation type : types) {
            long delta = assigned.getOrDefault(type, 0L) - line.customUpgradePool().count(type);
            if (delta > cloud.customUpgradePool().count(type)) throw error("pool_empty");
            cloud.customUpgradePool().add(type, -delta);
        }
        line.customUpgradePool().setAll(assigned);
    }

    private void settleLineEnergy(CloudworksData cloud, ProductionLineData line, double storedEnergy) {
        line.setEnergy(storedEnergy, line.maxEnergy());
        double excess = storedEnergy - line.energy();
        if (excess > 0 && !cloud.unlimitedEnergy()) {
            if (cloud.maxEnergy() - cloud.energy() < excess) throw error("energy_not_empty");
            cloud.setEnergy(cloud.energy() + excess, cloud.maxEnergy(), false);
        }
    }

    public void registerTypeUnblockListener(UUID owner, QIOResourceDescriptor descriptor,
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

    public void unregisterTypeUnblockListener(UUID owner, QIOResourceDescriptor descriptor,
                                              Runnable callback) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.unregisterTypeUnblockListener(owner, descriptor, callback);
            return;
        }
        synchronized (this) {
            Map<QIOResourceDescriptor, List<Runnable>> byType = typeUnblockListeners.get(owner);
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
            Iterator<Map.Entry<UUID, Map<QIOResourceDescriptor, List<Runnable>>>> ownerIterator
                    = typeUnblockListeners.entrySet().iterator();
            while (ownerIterator.hasNext()) {
                Map.Entry<UUID, Map<QIOResourceDescriptor, List<Runnable>>> ownerEntry = ownerIterator.next();
                CloudworksData cloud = clouds.get(ownerEntry.getKey());
                if (cloud == null) continue;
                Iterator<Map.Entry<QIOResourceDescriptor, List<Runnable>>> typeIterator
                        = ownerEntry.getValue().entrySet().iterator();
                while (typeIterator.hasNext()) {
                    Map.Entry<QIOResourceDescriptor, List<Runnable>> typeEntry = typeIterator.next();
                    if (cloud.receiving().insertable(typeEntry.getKey(), 1L,
                            cloud.effectiveWarehouseItemCap()) <= 0) continue;
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

    /**
     * Creates a durable recovery record before a player team disappears.
     * Team data remains in the normal owner directory until a recovery block
     * completes the transfer.
     */
    public void markTeamDissolved(UUID teamOwner, String teamId, short teamUid, UUID lastOwner) {
        if (parent == null) {
            domain(teamOwner).markTeamDissolved(teamOwner, teamId, teamUid, lastOwner);
            return;
        }
        synchronized (this) {
            if (root == null) return;
            Path playerOwner = root.resolve("player").resolve(teamOwner.toString());
            Path teamOwnerPath = root.resolve("team").resolve(teamOwner.toString());
            // A team is normally classified before its first write.  If a
            // same-session caller wrote it while the registry was unavailable,
            // finish the projection and move that owner directory once; this
            // is an identity correction, not migration of old world data.
            if (!Files.isDirectory(teamOwnerPath) && Files.isDirectory(playerOwner)) {
                if (!awaitPersistence(10_000)) throw error("busy");
                try {
                    Files.createDirectories(teamOwnerPath.getParent());
                    try {
                        Files.move(playerOwner, teamOwnerPath, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        Files.move(playerOwner, teamOwnerPath);
                    }
                } catch (IOException failure) {
                    throw dataError(failure);
                }
            }
            // The team registry may remove a dissolved team before this
            // recovery record is written. Preserve the owner kind explicitly
            // so all subsequent recovery access stays under team/<uuid>.
            teamOwnerIds.add(teamOwner);
            if (!recordEnabled(teamOwner) && !Files.exists(cloudFile(teamOwner))) return;
            synchronized (teamRecoveryLock) {
                NBTTagCompound rootTag = Files.exists(teamRecoveryFile()) ? read(teamRecoveryFile()) : new NBTTagCompound();
                rootTag.setInteger("schema", 1);
                NBTTagList entries = rootTag.getTagList("entries", 10);
                boolean exists = false;
                for (int i = 0; i < entries.tagCount(); i++) {
                    if (teamOwner.toString().equals(entries.getCompoundTagAt(i).getString("owner"))) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    entries.appendTag(recoveryEntry(teamOwner, teamId, teamUid, lastOwner, "DISSOLVED"));
                    rootTag.setTag("entries", entries);
                    write(teamRecoveryFile(), rootTag);
                }
            }
            if (Files.exists(cloudFile(teamOwner))) {
                CloudworksData cloud = getOrCreate(teamOwner);
                cloud.setEnabled(false);
                persistCloudCore(teamOwner, cloud);
            }
        }
    }

    public UUID findDissolvedTeam(UUID lastOwner) {
        if (parent == null) return domain(lastOwner).findDissolvedTeam(lastOwner);
        synchronized (teamRecoveryLock) {
            if (root == null || !Files.exists(teamRecoveryFile())) return null;
            NBTTagList entries = read(teamRecoveryFile()).getTagList("entries", 10);
            for (int i = 0; i < entries.tagCount(); i++) {
                NBTTagCompound entry = entries.getCompoundTagAt(i);
                if (!"DISSOLVED".equals(entry.getString("status"))
                        && !"RECOVERING".equals(entry.getString("status"))
                        || !entry.hasKey("lastOwner", 8)
                        || !lastOwner.toString().equals(entry.getString("lastOwner"))) continue;
                return UUID.fromString(entry.getString("owner"));
            }
            return null;
        }
    }

    public boolean hasCloudRecord(UUID owner) {
        if (parent == null ? cloudViews.containsKey(owner) : clouds.containsKey(owner)) return true;
        return root != null && Files.exists(cloudFile(owner));
    }

    /**
     * Main-thread capture only; the team recovery transaction does all disk work on its worker.
     */
    public void captureMountedTeamPorts(UUID owner) {
        CloudworksPlayerDataManager target = parent == null ? domains.get(owner) : this;
        if (target == null) return;
        List<Object> mounts;
        synchronized (target) {
            mounts = new ArrayList<>(target.portMounts.values());
        }
        for (Object mount : mounts)
            if (mount instanceof CloudworksPortBinding binding) binding.capture();
    }

    /**
     * Moves a dissolved team's cloud into the recovering player's cloud and
     * deletes the old owner directory only after all projections are empty.
     */
    // =====================================================================================
    // Team recovery machine drain (2026-10-18 v3): a take-only output port bound to the
    // dissolved team's cloud. The estate is drained piece by piece into a descriptor-generic
    // buffer (QIO storage semantics — every family), journal-first for crash safety.
    // =====================================================================================

    private static final long RECOVERY_BUFFER_CAPACITY = Long.MAX_VALUE / 2;

    private Path recoveryBufferFile(UUID teamOwner) {
        return ownerDirectory(teamOwner).resolve("recovery-buffer.dat");
    }

    private Path recoveryJournalFile(UUID teamOwner) {
        return ownerDirectory(teamOwner).resolve("recovery-journal.dat");
    }

    private long recoveryLastBatch(UUID teamOwner) {
        return teamRecoveryBatches.getOrDefault(teamOwner, 0L);
    }

    /**
     * Claims the estate's single live output buffer for one physical recovery block.
     */
    public boolean mountTeamRecovery(UUID teamOwner, Object holder) {
        if (parent == null) return domain(teamOwner).mountTeamRecovery(teamOwner, holder);
        synchronized (this) {
            if (root == null || holder == null) return false;
            Object mounted = teamRecoveryMounts.get(teamOwner);
            if (mounted != null && mounted != holder) return false;
            teamRecoveryMounts.put(teamOwner, holder);
            return true;
        }
    }

    public void unmountTeamRecovery(UUID teamOwner, Object holder) {
        if (parent == null) {
            CloudworksPlayerDataManager domain = domains.get(teamOwner);
            if (domain != null) domain.unmountTeamRecovery(teamOwner, holder);
            return;
        }
        synchronized (this) {
            teamRecoveryMounts.remove(teamOwner, holder);
        }
    }

    /**
     * Loads the persisted recovery buffer into {@code buffer} and replays any journal.
     */
    public void loadTeamRecoveryBuffer(UUID teamOwner, CloudworksInventory buffer) {
        if (parent == null) {
            domain(teamOwner).loadTeamRecoveryBuffer(teamOwner, buffer);
            return;
        }
        synchronized (this) {
            if (root == null) return;
            // Hydrate every source projection before the drain or completion probes inspect caches.
            getOrCreate(teamOwner);
            Path file = recoveryBufferFile(teamOwner);
            if (Files.exists(file)) {
                NBTTagCompound tag = read(file);
                NBTTagList entries = tag.getTagList("contents", 10);
                boolean hasLastBatch = tag.hasKey("lastBatch", 4);
                boolean invalidLedger = tag.getBoolean("lastBatchInvalid")
                        || !hasLastBatch && !entries.isEmpty();
                if (invalidLedger) {
                    recoveryLedgerInvalid.add(teamOwner);
                    if (Mekanism.logger != null)
                        Mekanism.logger.warn(
                                "Cloudworks recovery buffer {} has contents but no trustworthy lastBatch; journal replay is blocked",
                                file);
                } else {
                    recoveryLedgerInvalid.remove(teamOwner);
                    teamRecoveryBatches.put(teamOwner, hasLastBatch ? tag.getLong("lastBatch") : 0L);
                }
                for (int i = 0; i < entries.tagCount(); i++) {
                    NBTTagCompound entry = entries.getCompoundTagAt(i);
                    buffer.insertIgnoringTypeCap(
                            QIOResourceDescriptor.read(entry.getCompoundTag("descriptor")),
                            entry.getLong("amount"));
                }
            }
            replayTeamRecoveryJournal(teamOwner, buffer);
        }
    }

    /**
     * Persists the buffer file; the batch counter stays under PDM control.
     */
    public boolean saveTeamRecoveryBuffer(UUID teamOwner, CloudworksInventory buffer) {
        if (parent == null) {
            return domain(teamOwner).saveTeamRecoveryBuffer(teamOwner, buffer);
        }
        synchronized (this) {
            if (root == null || !drainInteraction()) return false;
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("schema", 1);
            if (recoveryLedgerInvalid.contains(teamOwner))
                tag.setBoolean("lastBatchInvalid", true);
            else
                tag.setLong("lastBatch", recoveryLastBatch(teamOwner));
            tag.setTag("contents", buffer.write().getTagList("contents", 10));
            // Recovery payload is a transaction boundary: do not acknowledge a take or
            // retire its journal until this replacement has reached disk.
            write(recoveryBufferFile(teamOwner), tag);
            return true;
        }
    }

    public String teamRecoveryStatus(UUID teamOwner) {
        if (parent == null) return domain(teamOwner).teamRecoveryStatus(teamOwner);
        synchronized (teamRecoveryLock) {
            TeamRecoveryRecord record = findTeamRecovery(teamOwner);
            return record == null ? "" : record.status;
        }
    }

    /**
     * True when every materializable estate piece is empty (energy handled separately).
     */
    public boolean teamRecoveryEstateDrained(UUID teamOwner) {
        if (parent == null) return domain(teamOwner).teamRecoveryEstateDrained(teamOwner);
        synchronized (this) {
            if (root == null || findTeamRecovery(teamOwner) == null) return true;
            getOrCreate(teamOwner);
            CloudworksData cloud = clouds.get(teamOwner);
            if (cloud != null) {
                for (CloudworksInventory warehouse : new CloudworksInventory[]{
                        cloud.receiving(), cloud.transfer(), cloud.sending()})
                    if (!warehouse.snapshot().isEmpty()) return false;
                if (cloud.upgradePool(true) > 0 || cloud.upgradePool(false) > 0) return false;
                for (ResourceLocation type : cloud.customUpgradePool().ids()) {
                    MachineModuleType upgrade =
                            CloudworksMachineModuleRegistry.type(type);
                    if (upgrade != null && upgrade.item() != null && !upgrade.item().isEmpty()) return false;
                    // Unresolvable custom items cannot materialize; they transfer as
                    // counts during the energy phase instead of blocking the drain.
                }
            }
            if (!recycleBin(teamOwner).entries().isEmpty()) return false;
            if (machineCache(teamOwner).typeCount() > 0) return false;
            if (!specialNodeStore(teamOwner).load().resources.isEmpty()) return false;
            for (ProductionLineData line : lines(teamOwner)) {
                for (MachineNodeData node : line.machines())
                    for (List<CloudworksResourceStack> bufferList : Arrays.asList(node.inputs(), node.outputs(), node.templates()))
                        if (!bufferList.isEmpty()) return false;
                for (CloudworksChannelNode channel : line.channels())
                    if (!channel.buffer().snapshot().isEmpty()) return false;
            }
            return portRecordsDrained(teamOwner);
        }
    }

    private boolean portRecordsDrained(UUID teamOwner) {
        // The in-memory records are fresher than the asynchronous orderedWrite queue:
        // a record already cleared in memory wins over its stale disk image.
        Set<UUID> clearedInMemory = new HashSet<>();
        synchronized (this) {
            for (CloudworksPortRecord port : ports.values())
                if (teamOwner.equals(port.owner)) {
                    if (!port.resources().isEmpty()) return false;
                    clearedInMemory.add(port.id);
                }
        }
        Path directory = ownerDirectory(teamOwner).resolve("ports");
        if (Files.isDirectory(directory)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.dat")) {
                for (Path file : files) {
                    CloudworksPortRecord port = CloudworksPortRecord.read(read(file));
                    if (teamOwner.equals(port.owner) && !clearedInMemory.contains(port.id)
                            && !port.resources().isEmpty()) return false;
                }
            } catch (IOException failure) {
                throw dataError(failure);
            }
        }
        return true;
    }

    /**
     * One bounded drain step. Journal-first: deltas are logged, sources are reduced and
     * persisted, the buffer file is rewritten with the new batch id, then the journal is
     * deleted. Returns true when anything moved.
     */
    public boolean drainTeamRecoveryBatch(UUID teamOwner, UUID recipient,
                                          CloudworksInventory buffer, int maxTypes) {
        if (parent == null) return domain(teamOwner).drainTeamRecoveryBatch(teamOwner, recipient, buffer, maxTypes);
        Objects.requireNonNull(recipient);
        synchronized (this) {
            requireLoaded();
            TeamRecoveryRecord record = findTeamRecovery(teamOwner);
            if (record == null || recipient.equals(teamOwner) || !recipient.equals(record.lastOwner)) return false;
            if ("DRAINED".equals(record.status)) return false;
            getOrCreate(teamOwner);
            if (!replayTeamRecoveryJournal(teamOwner, buffer)) return false;
            updateTeamRecoveryStatus(teamOwner, "RECOVERING");
            if (!drainInteraction()) return false;
            Map<QIOResourceDescriptor, Long> deltas = new LinkedHashMap<>();
            List<UUID> portIds = new ArrayList<>();
            String piece = nextDrainPiece(teamOwner, maxTypes, buffer, deltas, portIds);
            if (piece == null) return false;
            long batch = Math.addExact(recoveryLastBatch(teamOwner), 1);
            writeRecoveryJournal(teamOwner, batch, piece, deltas, portIds);
            Map<QIOResourceDescriptor, Long> consumed = applyRecoveryDeltas(teamOwner, piece, deltas, portIds);
            if (consumed.isEmpty()) {
                // Nothing actually moved (source drifted): drop the journal without
                // touching the buffer so the next batch re-collects fresh state.
                deleteFile(recoveryJournalFile(teamOwner));
                return false;
            }
            for (Map.Entry<QIOResourceDescriptor, Long> moved : consumed.entrySet())
                buffer.insertIgnoringTypeCap(moved.getKey(), moved.getValue());
            teamRecoveryBatches.put(teamOwner, batch);
            if (!saveTeamRecoveryBuffer(teamOwner, buffer)) return true;
            deleteFile(recoveryJournalFile(teamOwner));
            return true;
        }
    }

    /**
     * Chooses the next estate piece and collects its deltas (no mutation yet).
     */
    private String nextDrainPiece(UUID teamOwner, int maxTypes, CloudworksInventory buffer,
                                  Map<QIOResourceDescriptor, Long> deltas, List<UUID> portIds) {
        // A full descriptor (for example an unlimited water source) must not
        // keep the machine, card and other resource pieces from being visited.
        for (String piece : new String[]{"lines", "recycle", "warehouses", "ports", "machines", "upgrades", "specials"}) {
            deltas.clear();
            portIds.clear();
            switch (piece) {
                case "lines" -> collectLineBuffers(teamOwner, maxTypes, deltas);
                case "recycle" -> collectRecycleBin(teamOwner, deltas);
                case "warehouses" -> collectWarehouses(teamOwner, maxTypes, deltas);
                case "ports" -> collectPortRecords(teamOwner, deltas, portIds);
                case "machines" -> collectMachineCache(teamOwner, maxTypes, deltas);
                case "upgrades" -> collectUpgradePools(teamOwner, deltas);
                case "specials" -> collectSpecialNodes(teamOwner, deltas);
            }
            if (limitRecoveryDeltasToBufferRoom(buffer, piece, deltas)) return piece;
        }
        return null;
    }

    private void addDelta(Map<QIOResourceDescriptor, Long> deltas, QIOResourceDescriptor descriptor, long amount) {
        if (descriptor == null || amount <= 0) return;
        long current = deltas.getOrDefault(descriptor, 0L);
        long next = current >= RECOVERY_BUFFER_CAPACITY - amount
                ? RECOVERY_BUFFER_CAPACITY : current + amount;
        deltas.put(descriptor, next);
    }

    private boolean limitRecoveryDeltasToBufferRoom(
            CloudworksInventory buffer,
            String piece,
            Map<QIOResourceDescriptor, Long> deltas) {
        if (buffer.hasPerResourceCapacity()) {
            boolean atomic = "recycle".equals(piece) || "ports".equals(piece) || "upgrades".equals(piece);
            for (Map.Entry<QIOResourceDescriptor, Long> entry : deltas.entrySet()) {
                long accepted = Math.min(entry.getValue(), buffer.room(entry.getKey()));
                if (atomic && accepted != entry.getValue()) return false;
                entry.setValue(accepted);
            }
            deltas.entrySet().removeIf(entry -> entry.getValue() <= 0);
            return !deltas.isEmpty();
        }
        long room = buffer.room();
        if (room <= 0) return false;
        if ("recycle".equals(piece) || "ports".equals(piece) || "upgrades".equals(piece)) {
            long total = 0;
            for (long amount : deltas.values()) {
                if (amount > room - total) return false;
                total += amount;
            }
            return !deltas.isEmpty();
        }
        for (Map.Entry<QIOResourceDescriptor, Long> entry : deltas.entrySet()) {
            long accepted = Math.min(entry.getValue(), room);
            entry.setValue(accepted);
            room -= accepted;
        }
        deltas.entrySet().removeIf(entry -> entry.getValue() <= 0);
        return !deltas.isEmpty();
    }

    private void mergeRecoveryAmount(Map<QIOResourceDescriptor, Long> values,
                                     QIOResourceDescriptor descriptor, long amount) {
        if (descriptor == null || amount <= 0) return;
        long current = values.getOrDefault(descriptor, 0L);
        long next = current >= RECOVERY_BUFFER_CAPACITY - amount
                ? RECOVERY_BUFFER_CAPACITY : current + amount;
        values.put(descriptor, next);
    }

    private boolean collectLineBuffers(UUID teamOwner, int maxTypes, Map<QIOResourceDescriptor, Long> deltas) {
        for (ProductionLineData line : lines(teamOwner)) {
            for (MachineNodeData node : line.machines())
                for (List<CloudworksResourceStack> bufferList : Arrays.asList(node.inputs(), node.outputs(), node.templates()))
                    for (CloudworksResourceStack resource : bufferList) {
                        if (deltas.size() >= maxTypes) return !deltas.isEmpty();
                        addDelta(deltas, resource.descriptor(), resource.amount());
                    }
            for (CloudworksChannelNode channel : line.channels())
                for (Map.Entry<QIOResourceDescriptor, Long> resource : channel.buffer().snapshot().entrySet()) {
                    if (deltas.size() >= maxTypes) return !deltas.isEmpty();
                    addDelta(deltas, resource.getKey(), resource.getValue());
                }
        }
        return !deltas.isEmpty();
    }

    private boolean collectRecycleBin(UUID teamOwner, Map<QIOResourceDescriptor, Long> deltas) {
        for (Map.Entry<QIOResourceDescriptor, Long> resource : recycleBin(teamOwner).entries().entrySet())
            addDelta(deltas, resource.getKey(), resource.getValue());
        return !deltas.isEmpty();
    }

    private boolean collectWarehouses(UUID teamOwner, int maxTypes, Map<QIOResourceDescriptor, Long> deltas) {
        CloudworksData cloud = clouds.get(teamOwner);
        if (cloud == null) return false;
        for (CloudworksInventory warehouse : new CloudworksInventory[]{
                cloud.receiving(), cloud.transfer(), cloud.sending()}) {
            for (Map.Entry<QIOResourceDescriptor, Long> resource : warehouse.snapshot().entrySet()) {
                if (deltas.size() >= maxTypes) return true;
                addDelta(deltas, resource.getKey(), resource.getValue());
            }
            if (deltas.size() >= maxTypes) return true;
        }
        return !deltas.isEmpty();
    }

    private boolean collectPortRecords(UUID teamOwner, Map<QIOResourceDescriptor, Long> deltas, List<UUID> portIds) {
        Path directory = ownerDirectory(teamOwner).resolve("ports");
        Map<UUID, CloudworksPortRecord> records = new LinkedHashMap<>();
        if (Files.isDirectory(directory)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.dat")) {
                for (Path file : files) {
                    CloudworksPortRecord port = CloudworksPortRecord.read(read(file));
                    if (teamOwner.equals(port.owner)) records.put(port.id, port);
                }
            } catch (IOException failure) {
                throw dataError(failure);
            }
        }
        synchronized (this) {
            for (CloudworksPortRecord port : ports.values())
                if (teamOwner.equals(port.owner)) records.put(port.id, port);
        }
        // Port records are immutable snapshots, so one record is drained atomically
        // per batch (budget-immune); the journal carries its id.
        for (CloudworksPortRecord port : records.values()) {
            if (port.resources().isEmpty()) continue;
            portIds.add(port.id);
            for (Map.Entry<QIOResourceDescriptor, Long> resource : port.resources().entrySet())
                addDelta(deltas, resource.getKey(), resource.getValue());
            return true;
        }
        return false;
    }

    private boolean collectMachineCache(UUID teamOwner, int maxTypes, Map<QIOResourceDescriptor, Long> deltas) {
        CloudworksMachineCache cache = machineCache(teamOwner);
        for (CloudworksMachineCache.Entry entry : cache.entries()) {
            if (deltas.size() >= maxTypes) return true;
            for (CloudworksMachineCache.Variant variant : entry.variants()) {
                ItemStack stack = entry.template().copy();
                NBTTagCompound variantTag = variant.tag();
                stack.setTagCompound(variantTag.getKeySet().isEmpty() ? null : variantTag.copy());
                addDelta(deltas, QIOResourceCodecs.item(stack), variant.count());
            }
        }
        return !deltas.isEmpty();
    }

    private boolean collectUpgradePools(UUID teamOwner, Map<QIOResourceDescriptor, Long> deltas) {
        CloudworksData cloud = clouds.get(teamOwner);
        if (cloud == null) return false;
        // Lazy materialization: touching the native upgrade item stacks class-loads
        // Mekanism upgrade items (and their Baubles interfaces) — only build the
        // descriptor when there is actually something to drain.
        for (ProductionLineData line : lines(teamOwner)) {
            if (line.speedUpgrades() > 0)
                addDelta(deltas, upgradeItemDescriptor(Upgrade.SPEED), line.speedUpgrades());
            if (line.energyUpgrades() > 0)
                addDelta(deltas, upgradeItemDescriptor(Upgrade.ENERGY), line.energyUpgrades());
        }
        if (cloud.upgradePool(true) > 0)
            addDelta(deltas, upgradeItemDescriptor(Upgrade.SPEED), cloud.upgradePool(true));
        if (cloud.upgradePool(false) > 0)
            addDelta(deltas, upgradeItemDescriptor(Upgrade.ENERGY), cloud.upgradePool(false));
        for (ResourceLocation type : cloud.customUpgradePool().ids()) {
            MachineModuleType upgrade =
                    CloudworksMachineModuleRegistry.type(type);
            if (upgrade != null && upgrade.item() != null && !upgrade.item().isEmpty())
                addDelta(deltas, QIOResourceCodecs.item(upgrade.item()),
                        cloud.customUpgradePool().count(type));
        }
        return !deltas.isEmpty();
    }

    private QIOResourceDescriptor upgradeItemDescriptor(Upgrade type) {
        ItemStack stack = CloudworksUpgradeItems.currentStack(type);
        return stack == null || stack.isEmpty() ? null
                : QIOResourceCodecs.item(stack);
    }

    private QIOResourceDescriptor recoveryCardDescriptor(CloudworksSpecialNodeStore.Entry entry) {
        ItemStack card = CloudworksInfiniteResourceCardData.fromEntry(entry)
                .toItemStack(CloudworksItems.InfiniteResourceCard);
        return QIOResourceCodecs.item(card);
    }

    private boolean collectSpecialNodes(UUID teamOwner, Map<QIOResourceDescriptor, Long> deltas) {
        for (CloudworksSpecialNodeStore.Entry entry : specialNodeStore(teamOwner).load().resources.values())
            addDelta(deltas, recoveryCardDescriptor(entry), 1);
        return !deltas.isEmpty();
    }

    /**
     * Applies already-journaled deltas: mutates the estate piece, persists it, and
     * returns what was actually consumed — the buffer only ever receives that.
     */
    private Map<QIOResourceDescriptor, Long> applyRecoveryDeltas(UUID teamOwner, String piece,
                                                                 Map<QIOResourceDescriptor, Long> deltas, List<UUID> portIds) {
        Map<QIOResourceDescriptor, Long> consumed = new LinkedHashMap<>();
        switch (piece) {
            case "lines" -> {
                Map<QIOResourceDescriptor, Long> remaining = new LinkedHashMap<>(deltas);
                for (ProductionLineData line : lines(teamOwner)) {
                    boolean touched = false;
                    for (MachineNodeData node : line.machines())
                        for (List<CloudworksResourceStack> bufferList : Arrays.asList(node.inputs(), node.outputs(), node.templates())) {
                            touched |= consumeStackList(bufferList, remaining, consumed);
                            bufferList.removeIf(stack -> stack.amount() <= 0);
                        }
                    for (CloudworksChannelNode channel : line.channels())
                        for (Map.Entry<QIOResourceDescriptor, Long> delta : remaining.entrySet()) {
                            if (delta.getValue() <= 0) continue;
                            long taken = channel.buffer().extract(delta.getKey(), delta.getValue());
                            if (taken > 0) {
                                delta.setValue(delta.getValue() - taken);
                                mergeRecoveryAmount(consumed, delta.getKey(), taken);
                                touched = true;
                            }
                        }
                    if (touched) writeCheckpoint(line);
                }
            }
            case "recycle" -> {
                CloudworksRecycleBin bin = recycleBin(teamOwner);
                for (Map.Entry<QIOResourceDescriptor, Long> drained : bin.drain().entrySet())
                    if (drained.getValue() > 0) consumed.put(drained.getKey(), drained.getValue());
                bin.clear();
                persistRecycleBin(teamOwner, bin);
            }
            case "warehouses" -> {
                CloudworksData cloud = getOrCreate(teamOwner);
                Map<QIOResourceDescriptor, Long> remaining = new LinkedHashMap<>(deltas);
                boolean receiving = false, transfer = false, sending = false;
                for (Map.Entry<QIOResourceDescriptor, Long> delta : remaining.entrySet()) {
                    if (delta.getValue() <= 0) continue;
                    long taken = cloud.receiving().extract(delta.getKey(), delta.getValue());
                    if (taken > 0) {
                        delta.setValue(delta.getValue() - taken);
                        mergeRecoveryAmount(consumed, delta.getKey(), taken);
                        receiving = true;
                    }
                    if (delta.getValue() <= 0) continue;
                    taken = cloud.transfer().extract(delta.getKey(), delta.getValue());
                    if (taken > 0) {
                        delta.setValue(delta.getValue() - taken);
                        mergeRecoveryAmount(consumed, delta.getKey(), taken);
                        transfer = true;
                    }
                    if (delta.getValue() <= 0) continue;
                    taken = cloud.sending().extract(delta.getKey(), delta.getValue());
                    if (taken > 0) {
                        delta.setValue(delta.getValue() - taken);
                        mergeRecoveryAmount(consumed, delta.getKey(), taken);
                        sending = true;
                    }
                }
                if (receiving) orderedWrite(teamOwner, receivingFile(teamOwner), cloud.writeReceiving());
                if (transfer) orderedWrite(teamOwner, transferStoreFile(teamOwner), cloud.writeTransfer());
                if (sending) orderedWrite(teamOwner, sendingFile(teamOwner), cloud.writeSending());
            }
            case "ports" -> {
                // Records may live only in memory yet (orderedWrite drains
                // asynchronously), so resolve each journaled id from disk ∪ memory.
                Path directory = ownerDirectory(teamOwner).resolve("ports");
                Map<UUID, CloudworksPortRecord> records = new LinkedHashMap<>();
                if (Files.isDirectory(directory)) {
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.dat")) {
                        for (Path file : files) {
                            CloudworksPortRecord port = CloudworksPortRecord.read(read(file));
                            if (teamOwner.equals(port.owner)) records.put(port.id, port);
                        }
                    } catch (IOException failure) {
                        throw dataError(failure);
                    }
                }
                synchronized (this) {
                    for (CloudworksPortRecord port : ports.values())
                        if (teamOwner.equals(port.owner)) records.put(port.id, port);
                }
                for (UUID portId : portIds) {
                    CloudworksPortRecord record = records.get(portId);
                    if (record == null) continue;
                    for (Map.Entry<QIOResourceDescriptor, Long> resource : record.resources().entrySet())
                        mergeRecoveryAmount(consumed, resource.getKey(), resource.getValue());
                    NBTTagCompound tag = record.write();
                    tag.setTag("items", new NBTTagList());
                    tag.setTag("fluids", new NBTTagList());
                    tag.setTag("gases", new NBTTagList());
                    orderedWrite(teamOwner, directory.resolve(portId + ".dat"), tag);
                    // Never read the file back — refresh the in-memory record from
                    // the cleared tag itself.
                    synchronized (this) {
                        if (ports.containsKey(portId)) ports.put(portId, CloudworksPortRecord.read(tag));
                    }
                }
            }
            case "machines" -> {
                CloudworksMachineCache cache = machineCache(teamOwner);
                Map<QIOResourceDescriptor, Long> remaining = new LinkedHashMap<>(deltas);
                for (CloudworksMachineCache.Entry entry : cache.entries()) {
                    for (CloudworksMachineCache.Variant variant : entry.variants()) {
                        ItemStack stack = entry.template().copy();
                        NBTTagCompound variantTag = variant.tag();
                        stack.setTagCompound(variantTag.getKeySet().isEmpty() ? null : variantTag.copy());
                        QIOResourceDescriptor descriptor = QIOResourceCodecs.item(stack);
                        long quota = remaining.getOrDefault(descriptor, 0L);
                        long taken = cache.extractVariant(stack, quota);
                        if (taken > 0) {
                            remaining.put(descriptor, quota - taken);
                            mergeRecoveryAmount(consumed, descriptor, taken);
                        }
                    }
                }
                persistMachineCache(teamOwner, cache);
            }
            case "upgrades" -> {
                // Wholesale for lines/native pools (collect always covers them whole);
                // custom types subtract individually so unresolvable items are preserved.
                CloudworksData cloud = getOrCreate(teamOwner);
                QIOResourceDescriptor speed = upgradeItemDescriptor(Upgrade.SPEED);
                QIOResourceDescriptor energy = upgradeItemDescriptor(Upgrade.ENERGY);
                if (speed != null && deltas.containsKey(speed)) {
                    long lineSpeed = 0;
                    for (ProductionLineData line : lines(teamOwner)) {
                        lineSpeed += line.speedUpgrades();
                        line.setUpgrades(0, line.energyUpgrades());
                        writeCheckpoint(line);
                    }
                    long poolSpeed = cloud.upgradePool(true);
                    cloud.addUpgradePool(true, -(int) Math.min(Integer.MAX_VALUE, poolSpeed));
                    mergeRecoveryAmount(consumed, speed, lineSpeed + poolSpeed);
                }
                if (energy != null && deltas.containsKey(energy)) {
                    long lineEnergy = 0;
                    for (ProductionLineData line : lines(teamOwner)) {
                        lineEnergy += line.energyUpgrades();
                        line.setUpgrades(line.speedUpgrades(), 0);
                        writeCheckpoint(line);
                    }
                    long poolEnergy = cloud.upgradePool(false);
                    cloud.addUpgradePool(false, -(int) Math.min(Integer.MAX_VALUE, poolEnergy));
                    mergeRecoveryAmount(consumed, energy, lineEnergy + poolEnergy);
                }
                for (ResourceLocation type : cloud.customUpgradePool().ids()) {
                    MachineModuleType upgrade =
                            CloudworksMachineModuleRegistry.type(type);
                    if (upgrade != null && upgrade.item() != null && !upgrade.item().isEmpty()) {
                        QIOResourceDescriptor descriptor = QIOResourceCodecs.item(upgrade.item());
                        if (deltas.containsKey(descriptor)) {
                            long count = cloud.customUpgradePool().count(type);
                            cloud.customUpgradePool().add(type, -count);
                            mergeRecoveryAmount(consumed, descriptor, count);
                        }
                    }
                }
                persistCloudCore(teamOwner, cloud);
            }
            case "specials" -> {
                CloudworksSpecialNodeStore store = specialNodeStore(teamOwner);
                CloudworksSpecialNodeStore.Snapshot snapshot = store.load();
                CloudworksSpecialNodeStore.Snapshot next = CloudworksSpecialNodeStore.Snapshot.empty();
                for (CloudworksSpecialNodeStore.Entry entry : snapshot.resources.values()) {
                    QIOResourceDescriptor cardDescriptor = recoveryCardDescriptor(entry);
                    if (!deltas.containsKey(cardDescriptor))
                        next = next.add(CloudworksInfiniteResourceCardData.fromEntry(entry)).next;
                    else mergeRecoveryAmount(consumed, cardDescriptor, 1L);
                }
                store.publish(next);
                orderedWrite(teamOwner, specialNodeDirectory(teamOwner).resolve(CloudworksSpecialNodeStore.NODE_FILE), next.nodesTag());
                orderedWrite(teamOwner, specialNodeDirectory(teamOwner).resolve(CloudworksSpecialNodeStore.RESOURCE_FILE), next.resourcesTag());
            }
            default -> throw error("team_recovery");
        }
        return consumed;
    }

    private boolean consumeStackList(List<CloudworksResourceStack> bufferList, Map<QIOResourceDescriptor, Long> deltas,
                                     Map<QIOResourceDescriptor, Long> consumed) {
        boolean touched = false;
        for (CloudworksResourceStack resource : bufferList) {
            Long amount = deltas.get(resource.descriptor());
            if (amount != null && amount > 0 && resource.amount() > 0) {
                // The dissolved team's lines are disabled — stale reservations are
                // force-released so the estate drains completely.
                if (resource.reserved() > 0) resource.release(resource.reserved());
                long before = resource.amount();
                resource.consume(Math.min(amount, before));
                long taken = before - resource.amount();
                deltas.put(resource.descriptor(), amount - taken);
                mergeRecoveryAmount(consumed, resource.descriptor(), taken);
                touched = true;
            }
        }
        return touched;
    }

    private void writeRecoveryJournal(UUID teamOwner, long batch, String piece,
                                      Map<QIOResourceDescriptor, Long> deltas, List<UUID> portIds) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", 1);
        tag.setLong("batch", batch);
        tag.setString("piece", piece);
        if (portIds != null && !portIds.isEmpty()) {
            NBTTagList ids = new NBTTagList();
            for (UUID portId : portIds) ids.appendTag(new NBTTagString(portId.toString()));
            tag.setTag("portIds", ids);
        }
        NBTTagList entries = new NBTTagList();
        for (Map.Entry<QIOResourceDescriptor, Long> delta : deltas.entrySet()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setTag("descriptor", delta.getKey().write());
            entry.setLong("amount", delta.getValue());
            entries.appendTag(entry);
        }
        tag.setTag("deltas", entries);
        write(recoveryJournalFile(teamOwner), tag);
    }

    /**
     * Idempotent journal replay: the buffer merges by batch id; source consumption only
     * takes what the estate still holds.
     */
    private boolean replayTeamRecoveryJournal(UUID teamOwner, CloudworksInventory buffer) {
        Path file = recoveryJournalFile(teamOwner);
        if (!Files.exists(file)) return true;
        if (recoveryLedgerInvalid.contains(teamOwner)) return false;
        NBTTagCompound tag = read(file);
        long batch = tag.getLong("batch");
        String piece = tag.getString("piece");
        List<UUID> portIds = new ArrayList<>();
        NBTTagList ids = tag.getTagList("portIds", 8);
        for (int i = 0; i < ids.tagCount(); i++) portIds.add(UUID.fromString(ids.getStringTagAt(i)));
        NBTTagList entries = tag.getTagList("deltas", 10);
        Map<QIOResourceDescriptor, Long> deltas = new LinkedHashMap<>();
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound entry = entries.getCompoundTagAt(i);
            deltas.put(QIOResourceDescriptor.read(entry.getCompoundTag("descriptor")),
                    entry.getLong("amount"));
        }
        if (deltas.isEmpty()) {
            deleteFile(file);
            return true;
        }
        boolean alreadyApplied = recoveryLastBatch(teamOwner) == batch;
        if (!alreadyApplied) {
            long sharedRoom = buffer.room();
            for (Map.Entry<QIOResourceDescriptor, Long> delta : deltas.entrySet()) {
                long room = buffer.hasPerResourceCapacity() ? buffer.room(delta.getKey()) : sharedRoom;
                if (delta.getValue() > room) {
                    if (Mekanism.logger != null)
                        Mekanism.logger.warn(
                                "Cloudworks recovery journal batch {} cannot fit buffer for {} (needed {}, room {})",
                                batch, teamOwner, delta.getValue(), room);
                    return false;
                }
                if (!buffer.hasPerResourceCapacity()) sharedRoom -= delta.getValue();
            }
            for (Map.Entry<QIOResourceDescriptor, Long> delta : deltas.entrySet())
                buffer.insertIgnoringTypeCap(delta.getKey(), delta.getValue());
            teamRecoveryBatches.put(teamOwner, batch);
        }
        Map<QIOResourceDescriptor, Long> stillPresent = new LinkedHashMap<>();
        collectPresent(teamOwner, piece, deltas, stillPresent);
        applyRecoveryDeltas(teamOwner, piece, stillPresent, portIds);
        if (!saveTeamRecoveryBuffer(teamOwner, buffer)) return false;
        deleteFile(file);
        return true;
    }

    /**
     * Re-collects which journaled deltas the estate still holds (post-crash partial states).
     */
    private void collectPresent(UUID teamOwner, String piece, Map<QIOResourceDescriptor, Long> deltas,
                                Map<QIOResourceDescriptor, Long> present) {
        Map<QIOResourceDescriptor, Long> probe = new LinkedHashMap<>();
        switch (piece) {
            case "lines" -> collectLineBuffers(teamOwner, Integer.MAX_VALUE, probe);
            case "recycle" -> collectRecycleBin(teamOwner, probe);
            case "warehouses" -> collectWarehouses(teamOwner, Integer.MAX_VALUE, probe);
            // Port record clearing is idempotent per journaled id — no probe needed.
            case "ports" -> present.putAll(deltas);
            case "machines" -> collectMachineCache(teamOwner, Integer.MAX_VALUE, probe);
            case "upgrades" -> collectUpgradePools(teamOwner, probe);
            case "specials" -> collectSpecialNodes(teamOwner, probe);
            default -> {
            }
        }
        if (piece.equals("ports")) return;
        for (Map.Entry<QIOResourceDescriptor, Long> delta : deltas.entrySet()) {
            long held = probe.getOrDefault(delta.getKey(), 0L);
            if (held >= delta.getValue()) present.put(delta.getKey(), delta.getValue());
            else if (held > 0) present.put(delta.getKey(), held);
        }
    }

    /**
     * Returns the amount of energy still held by the dissolved estate. The
     * recovery block owns the destination battery; this method never touches
     * the recipient cloud.
     */
    public double teamRecoveryEnergyRemaining(UUID teamOwner) {
        if (parent == null) return domain(teamOwner).teamRecoveryEnergyRemaining(teamOwner);
        synchronized (this) {
            if (root == null || findTeamRecovery(teamOwner) == null) return 0D;
            CloudworksData source = getOrCreate(teamOwner);
            double total = 0D;
            for (CloudworksPortRecord port : recoveryPortRecords(teamOwner).values())
                total = addEnergy(total, port.energy());
            total = addEnergy(total, source.receivingEnergy());
            total = addEnergy(total, source.sendingEnergy());
            total = addEnergy(total, source.energy());
            for (ProductionLineData line : lines(teamOwner))
                total = addEnergy(total, line.energy());
            return total;
        }
    }

    /**
     * Pulls energy from the estate into the recovery block's battery. Sources
     * are consumed in a stable order and persisted as part of the same tick.
     */
    public double pullTeamRecoveryEnergy(UUID teamOwner, double room) {
        if (parent == null) return domain(teamOwner).pullTeamRecoveryEnergy(teamOwner, room);
        if (!Double.isFinite(room) || room <= 0D) return 0D;
        synchronized (this) {
            requireLoaded();
            TeamRecoveryRecord record = findTeamRecovery(teamOwner);
            if (record == null || "DRAINED".equals(record.status) || !drainInteraction()) return 0D;
            CloudworksData source = getOrCreate(teamOwner);
            double remaining = room, moved = 0D;
            Path directory = ownerDirectory(teamOwner).resolve("ports");
            for (Map.Entry<UUID, CloudworksPortRecord> entry : recoveryPortRecords(teamOwner).entrySet()) {
                if (remaining <= 0D) break;
                CloudworksPortRecord port = entry.getValue();
                double taken = Math.min(remaining, port.energy());
                if (taken <= 0D) continue;
                CloudworksPortRecord cleared = withPortEnergy(port, port.energy() - taken);
                ports.put(entry.getKey(), cleared);
                // Recovery is the sole writer for this detached record now;
                // commit it immediately so the cleanup sweep cannot race a
                // queued port projection and recreate the team directory.
                write(directory.resolve(entry.getKey() + ".dat"), cleared.write());
                remaining -= taken;
                moved += taken;
            }
            // Transfer is a resource-only warehouse in the current model; the
            // only warehouse energy pools are the receiving and sending relays.
            if (remaining > 0D && (source.receivingEnergy() > 0D || source.sendingEnergy() > 0D)) {
                double receiving = source.receivingEnergy();
                double sending = source.sendingEnergy();
                double taken = Math.min(remaining, receiving);
                receiving -= taken;
                remaining -= taken;
                moved += taken;
                if (remaining > 0D) {
                    taken = Math.min(remaining, sending);
                    sending -= taken;
                    remaining -= taken;
                    moved += taken;
                }
                source.setWarehouseEnergy(receiving, sending);
                persistCloudCore(teamOwner, source);
            }
            if (remaining > 0D && source.energy() > 0D) {
                double taken = Math.min(remaining, source.energy());
                source.setEnergy(source.energy() - taken, source.maxEnergy(), source.unlimitedEnergy());
                remaining -= taken;
                moved += taken;
                persistCloudCore(teamOwner, source);
            }
            if (remaining > 0D) {
                for (ProductionLineData line : lines(teamOwner)) {
                    if (remaining <= 0D || line.energy() <= 0D) continue;
                    double taken = Math.min(remaining, line.energy());
                    line.setEnergy(line.energy() - taken, line.maxEnergy());
                    remaining -= taken;
                    moved += taken;
                    writeCheckpoint(line);
                }
            }
            return moved;
        }
    }

    private double addEnergy(double current, double amount) {
        if (!Double.isFinite(amount) || amount <= 0D) return current;
        return current >= Double.MAX_VALUE - amount ? Double.MAX_VALUE : current + amount;
    }

    private CloudworksPortRecord withPortEnergy(CloudworksPortRecord port, double energy) {
        NBTTagCompound tag = port.write();
        tag.setDouble("energy", Math.max(0D, energy));
        return CloudworksPortRecord.read(tag);
    }

    private Map<UUID, CloudworksPortRecord> recoveryPortRecords(UUID teamOwner) {
        Path directory = ownerDirectory(teamOwner).resolve("ports");
        Map<UUID, CloudworksPortRecord> records = new LinkedHashMap<>();
        if (Files.isDirectory(directory)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.dat")) {
                for (Path file : files) {
                    CloudworksPortRecord port = CloudworksPortRecord.read(read(file));
                    if (teamOwner.equals(port.owner)) records.put(port.id, port);
                }
            } catch (IOException failure) {
                throw dataError(failure);
            }
        }
        for (CloudworksPortRecord port : ports.values())
            if (teamOwner.equals(port.owner)) records.put(port.id, port);
        return records;
    }

    /**
     * Final cleanup after the buffer is empty and energies are merged.
     */
    public void finishTeamRecovery(UUID teamOwner, UUID recipient) {
        if (parent == null) {
            CloudworksPlayerDataManager team = domain(teamOwner);
            CloudworksPlayerDataManager target = domain(recipient);
            CloudworksPlayerDataManager first = teamOwner.toString().compareTo(recipient.toString()) <= 0 ? team : target;
            CloudworksPlayerDataManager second = first == team ? target : team;
            synchronized (first) {
                synchronized (second) {
                    team.finishTeamRecovery0(teamOwner, recipient, target);
                }
            }
            return;
        }
        synchronized (this) {
            finishTeamRecovery0(teamOwner, recipient, parent.domain(recipient));
        }
    }

    private void finishTeamRecovery0(UUID teamOwner, UUID recipient,
                                     CloudworksPlayerDataManager targetDomain) {
        synchronized (this) {
            requireLoaded();
            TeamRecoveryRecord record = findTeamRecovery(teamOwner);
            if (record == null) return;
            if (record.lastOwner == null || !record.lastOwner.equals(recipient)
                    || !("RECOVERING".equals(record.status) || "DRAINED".equals(record.status)))
                throw error("team_recovery");
            Path bufferFile = recoveryBufferFile(teamOwner);
            if (Files.exists(recoveryJournalFile(teamOwner))) throw error("busy");
            if (Files.exists(bufferFile)
                    && !read(bufferFile).getTagList("contents", 10).isEmpty()) throw error("busy");
            if (!teamRecoveryEstateDrained(teamOwner) || teamRecoveryEnergyRemaining(teamOwner) > 1.0E-9D)
                throw error("busy");
            CloudworksData source = getOrCreate(teamOwner);
            List<UUID> deleted = new ArrayList<>(source.lineIds());
            source.lineIds().clear();
            CloudworksRecycleBin bin = recycleBin(teamOwner);
            bin.clear();
            source.addUpgradePool(true, -source.upgradePool(true));
            source.addUpgradePool(false, -source.upgradePool(false));
            CloudworksData target = targetDomain.getOrCreate(recipient);
            boolean targetChanged = false;
            for (ResourceLocation type : source.customUpgradePool().ids()) {
                MachineModuleType upgrade =
                        CloudworksMachineModuleRegistry.type(type);
                long count = source.customUpgradePool().count(type);
                if (count > 0 && (upgrade == null || upgrade.item() == null || upgrade.item().isEmpty())) {
                    target.customUpgradePool().add(type, count);
                    targetChanged = true;
                }
            }
            source.customUpgradePool().setAll(Collections.emptyMap());
            source.setEnabled(false);
            if (targetChanged) targetDomain.persistCloudCore(recipient, target);
            publish0(teamOwner, source.write(), Collections.emptyList(), bin.write(),
                    null, null, null, true, false, null, deleted, null);
            if (!drainInteraction() || !awaitPersistence(10_000)) throw error("busy");
            updateTeamRecoveryStatus(teamOwner, "DRAINED");
            retireRecoveredTeam(teamOwner);
            deleteTeamRecoveryDirectory(teamOwner);
            removeTeamRecovery(teamOwner);
            deleteTeamRecoveryIntent(teamOwner);
            // A queued projection can finish between the first directory
            // sweep and the recovery-index cleanup. Wait for that final
            // worker turn and sweep once more so no stale owner directory is
            // recreated after the recovery block reports completion.
            if (!awaitPersistence(10_000)) throw error("busy");
            deleteTeamRecoveryDirectory(teamOwner);
            teamOwnerIds.remove(teamOwner);
            teamRecoveryBatches.remove(teamOwner);
            try {
                CloudworksTeamManager.recoveryCompleted(teamOwner);
            } catch (RuntimeException registryUnavailable) {
                // The team registry may not be loaded (bare environments). The lazy
                // recoverDissolutions pass on the next team transaction finishes the
                // team-record cleanup once it observes the owner directory is gone.
                if (Mekanism.logger != null)
                    Mekanism.logger.warn("Cloudworks team recovery completion deferred for {}: {}",
                            teamOwner, registryUnavailable.toString());
            }
        }
    }

    private void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            throw dataError(failure);
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
                    CloudworksConfig.settings().maxLines)) throw error("limit");
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
            CloudworksRecycleBin bin = recycleBin(owner);
            NBTTagCompound previousBin = bin.write();
            NBTTagCompound previousLine = line.write();
            CloudworksData cloud = clouds.get(owner);
            int index = cloud.lineIds().indexOf(id);
            if (index < 0) return false;
            CloudworksData previousCloud = cloud.copyForRuntime();
            try {
                long discarded = 0;
                for (MachineNodeData node : line.machines()) {
                    node.cancelOperation();
                    discarded += recycleIntoBin(bin, node.inputs(), node.outputs(), node.templates());
                }
                for (CloudworksChannelNode channel : line.channels()) {
                    if (channel.special()) continue;
                    for (Map.Entry<QIOResourceDescriptor, Long> entry
                            : channel.buffer().snapshot().entrySet())
                        discarded += bin.deposit(entry.getKey(), entry.getValue());
                }
                boolean binChanged = !bin.write().equals(previousBin);
                cloud.lineIds().remove(index);
                // 升级层: 删线时已安装升级全部回池（同一意图携带 cloud 投影）
                returnUpgradesToPool(cloud, line.speedUpgrades(), line.energyUpgrades());
                for (ResourceLocation type : line.customUpgradePool().ids())
                    cloud.customUpgradePool().add(type, line.customUpgradePool().count(type));
                CloudworksPendingBlueprint pendingBlueprint = pendingBlueprints.get(owner);
                publish0(owner, cloud.write(), Collections.emptyList(), binChanged ? bin.write() : null,
                        null, null, null, true, false,
                        pendingBlueprint != null && pendingBlueprint.line.equals(id) ? CloudworksPendingBlueprint.empty() : null,
                        Collections.singletonList(id));
                if (discarded > 0) pendingRecycleOverflow.add(owner);
                lines.remove(id);
                if (parent != null) parent.lineDomains.remove(id, this);
                runtimeUnmaterialized.remove(id);
                guiLines.remove(id);
                runtimeLineSnapshots.remove(id);
                checkpointGenerations.remove(id);
                return true;
            } catch (RuntimeException failure) {
                clouds.put(owner, previousCloud);
                recycleBins.put(owner, CloudworksRecycleBin.read(previousBin));
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
                if (!validation.valid()) throw error(validation.errorKey());
                if (line.machines().isEmpty()) throw error("invalid_line");
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

    /**
     * Single owner-domain transaction for every numeric expansion and one-time environment unlock.
     */
    public CloudworksCardEffect.Result applyCardEffect(UUID owner,
                                                                                       CloudworksCardEffect effect) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(effect, "effect");
        if (parent == null) return domain(owner).applyCardEffect(owner, effect);
        synchronized (this) {
            CloudworksData previous = getOrCreate(owner);
            CloudworksData candidate = previous.copyForRuntime();
            CloudworksCardEffect.Result result =
                    effect.apply(candidate, CloudworksConfig.settings());
            if (!result.applied()) return result;
            try {
                publishTags(owner, candidate.write(), Collections.emptyList());
                drainInteraction();
                if (recoveryRequired(owner)) throw dataError(recoveryCause(owner));
                return result;
            } catch (RuntimeException failure) {
                clouds.put(owner, previous);
                throw failure;
            }
        }
    }

    /**
     * Existing server APIs retain their numeric refusal codes; item use receives the typed result above.
     */
    private int legacyCardResult(UUID owner, CloudworksCardEffect effect) {
        if (parent == null) return domain(owner).legacyCardResult(owner, effect);
        synchronized (this) {
            try {
                requireEnabled(owner);
            } catch (RuntimeException unavailable) {
                return -1;
            }
            return applyCardEffect(owner, effect).legacyValue();
        }
    }

    /**
     * One-time item unlock; does not enable offline processing on individual lines.
     */
    public int unlockOfflineProcessing(UUID owner) {
        return legacyCardResult(owner, CloudworksCardEffects.OFFLINE);
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
            if (enabled && !CloudworksConfig.settings().offlineProcessingEnabled)
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

    /**
     * A leased physical terminal. Pending durable projections lock all slot access.
     */
    public static final class TerminalSession {
        private final UUID owner;
        private final CloudworksTerminalHost host;
        private CloudworksTerminalState pending;
        private boolean pendingApplied;
        private boolean closed;

        private TerminalSession(UUID owner, CloudworksTerminalHost host) {
            this.owner = owner;
            this.host = host;
        }
    }

    public TerminalSession openTerminal(UUID owner,
                                        CloudworksTerminalHost host) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            UUID previousOwner = terminalOwners.get(host.terminalId());
            if (host.allowsOwnerChange() && previousOwner != null && !previousOwner.equals(owner)) {
                CloudworksPlayerDataManager previous = domain(previousOwner);
                CloudworksPlayerDataManager first = previousOwner.toString().compareTo(owner.toString()) < 0 ? previous : target;
                CloudworksPlayerDataManager second = first == previous ? target : previous;
                synchronized (first) {
                    synchronized (second) {
                        if (previous.terminalSessions.containsKey(host.terminalId()) || !previous.drainInteraction())
                            throw error("busy");
                        return target.openTerminal(owner, host);
                    }
                }
            }
            return target.openTerminal(owner, host);
        }
        synchronized (this) {
            if (host instanceof TileEntityCloudDashboard dashboard
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
                if (!owner.equals(state.owner())) {
                    if (!host.allowsOwnerChange()) throw error("owner");
                    if (!state.transferId().equals(host.appliedTerminalTransfer())) {
                        state.apply(host);
                        host.persistIdentity();
                        if (!host.identityDurable()) throw error("busy");
                    }
                    terminalPublish(session, CloudworksTerminalState.capture(owner, host), null, null);
                } else if (!state.transferId().equals(host.appliedTerminalTransfer())) session.pending = state;
            } else {
                terminalPublish(session, CloudworksTerminalState.capture(owner, host), null, null);
            }
            terminalSessions.put(host.terminalId(), session);
            parent.terminalDomains.put(host.terminalId(), this);
            parent.terminalOwners.put(host.terminalId(), owner);
            return session;
        }
    }

    /**
     * Poll only; server tick never waits for disk.
     */
    public boolean terminalReady(TerminalSession session) {
        if (session == null) return false;
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(session.owner);
            return target.terminalReady(session);
        }
        synchronized (this) {
            if (session == null || session.closed || terminalSessions.get(session.host.terminalId()) != session
                    || !session.host.available() || recoveryRequired(session.owner)) return false;
            if (session.host instanceof TileEntityCloudDashboard dashboard
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
        if (!CloudworksConfig.settings().terminalItemUploadsEnabled)
            throw error("terminal_uploads_disabled");
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
        if (!CloudworksConfig.settings().terminalItemUploadsEnabled)
            throw error("terminal_uploads_disabled");
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
        if (!CloudworksConfig.settings().terminalItemUploadsEnabled)
            throw error("terminal_uploads_disabled");
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(session.owner);
            target.processTerminalUpgradeInput(session);
            return;
        }
        synchronized (this) {
            if (!terminalReady(session)) return;
            CloudworksTerminalState before = CloudworksTerminalState.capture(session.owner, session.host);
            ItemStack upgrades = before.stack(3);
            CloudworksData cloud = CloudworksData.read(requireEnabled(session.owner).write());
            ResourceLocation customId =
                    CloudworksMachineModuleRegistry.typeForItem(upgrades);
            if (customId != null) {
                // 8.5-B: a registered custom-type item uploads into the custom pool.
                cloud.customUpgradePool().add(customId, upgrades.getCount());
            } else {
                Upgrade type = CloudworksUpgradeItems.local().typeOf(upgrades);
                if (type == null) throw error("upgrade");
                cloud.addUpgradePool(type == Upgrade.SPEED, upgrades.getCount());
            }
            terminalPublish(session, before.withSlot(3, ItemStack.EMPTY), cloud, null);
        }
    }

    private void terminalPublish(TerminalSession session, CloudworksTerminalState state,
                                 CloudworksData cloud, CloudworksMachineCache cache) {
        if (session.host instanceof TileEntityCloudDashboard) {
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
        if (!CloudworksConfig.settings().terminalItemUploadsEnabled)
            throw error("terminal_uploads_disabled");
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
            if (!upgrades.isEmpty()) {
                CloudworksData cloud = CloudworksData.read(requireEnabled(session.owner).write());
                ResourceLocation customId =
                        CloudworksMachineModuleRegistry.typeForItem(upgrades);
                if (customId != null) {
                    cloud.customUpgradePool().add(customId, upgrades.getCount());
                } else {
                    Upgrade type = CloudworksUpgradeItems.local().typeOf(upgrades);
                    if (type == null) throw error("upgrade");
                    cloud.addUpgradePool(type == Upgrade.SPEED, upgrades.getCount());
                }
                terminalPublish(session, before.withSlot(3, ItemStack.EMPTY), cloud, null);
            }
        }
    }

    private static int terminalRoom(ItemStack current, ItemStack incoming) {
        if (current.isEmpty()) return incoming.getMaxStackSize();
        if (!ItemStack.areItemsEqual(current, incoming) || !ItemStack.areItemStackTagsEqual(current, incoming))
            return 0;
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
            ItemStack stack = CloudworksUpgradeItems.local().stack(
                    speed ? Upgrade.SPEED : Upgrade.ENERGY);
            if (stack.isEmpty()) throw error("upgrade");
            CloudworksTerminalState before = CloudworksTerminalState.capture(session.owner, session.host);
            int wanted = Math.min(cloud.upgradePool(speed), Math.min(shift ? stack.getMaxStackSize() : 1,
                    terminalRoom(before.stack(4), stack)));
            if (wanted == 0) return;
            stack.setCount(wanted);
            cloud.addUpgradePool(speed, -wanted);
            terminalPublish(session, before.withSlot(4, terminalMerge(before.stack(4), stack)), cloud, null);
        }
    }

    /**
     * Withdraws custom-type upgrades from the cloud pool as bound items (8.5-B).
     */
    public void terminalDownloadCustomUpgrade(TerminalSession session, String typeId, boolean shift) {
        if (session == null) return;
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(session.owner);
            target.terminalDownloadCustomUpgrade(session, typeId, shift);
            return;
        }
        synchronized (this) {
            if (!terminalReady(session)) return;
            ResourceLocation id;
            try {
                id = new ResourceLocation(typeId);
            } catch (Exception invalid) {
                throw error("upgrade");
            }
            MachineModuleType type =
                    CloudworksMachineModuleRegistry.type(id);
            if (type == null || type.slot() != MachineModuleType.Slot.CUSTOM)
                throw error("upgrade");
            ItemStack stack = type.item();
            if (stack.isEmpty()) throw error("upgrade");
            CloudworksData cloud = CloudworksData.read(requireEnabled(session.owner).write());
            CloudworksTerminalState before = CloudworksTerminalState.capture(session.owner, session.host);
            int wanted = (int) Math.min(cloud.customUpgradePool().count(id),
                    Math.min(shift ? stack.getMaxStackSize() : 1L, terminalRoom(before.stack(4), stack)));
            if (wanted == 0) return;
            stack.setCount(wanted);
            cloud.customUpgradePool().add(id, -wanted);
            terminalPublish(session, before.withSlot(4, terminalMerge(before.stack(4), stack)), cloud, null);
        }
    }

    /**
     * Old ownerless dashboards retain the journal owner instead of being claimed by the next visitor.
     */
    public void claimDashboard(TileEntityCloudDashboard dashboard,
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

    public void detachTerminal(CloudworksTerminalHost host) {
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
        ProjectionPlan plan = projectionPlan(intent);
        if (plan != null) return plan.paths;
        Map<String, Path> paths = new LinkedHashMap<>();
        paths.put("cloud", cloudFile(owner));
        paths.put("receiving", receivingFile(owner));
        paths.put("transfer", transferStoreFile(owner));
        paths.put("sending", sendingFile(owner));
        if (intent.hasKey("recycle", 10)) paths.put("recycle", recycleFile(owner));
        if (intent.hasKey("machineCache", 10)) paths.put("machineCache", machineCacheFile(owner));
        if (intent.hasKey("blueprintPending", 10)) paths.put("blueprintPending", blueprintPendingFile(owner));
        if (intent.hasKey("specialNodeDefinitions", 10))
            paths.put("specialNodeDefinitions", specialNodeDirectory(owner).resolve(CloudworksSpecialNodeStore.NODE_FILE));
        if (intent.hasKey("infiniteResources", 10))
            paths.put("infiniteResources", specialNodeDirectory(owner).resolve(CloudworksSpecialNodeStore.RESOURCE_FILE));
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

    private static void applyCore(CloudworksData cloud, NBTTagCompound core) {
        cloud.applyCore(core);
    }

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
        if (batch != null) batch.projections.put(intent, new ProjectionPlan(cloud, projectionPaths(owner, intent)));
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
        IoReads cache = IO_READS.get();
        if (cache != null && intent.hasKey("nextFingerprints", 10))
            cache.fingerprints.put(file, intent.getCompoundTag("nextFingerprints").getByteArray(key).clone());
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
        return legacyCardResult(owner, CloudworksCardEffects.LINE_EXPANSION);
    }

    /**
     * Consumable expansion card (节点扩容卡): raises the owner's per-line machine
     * node ceiling by {@code amount}, clamped to the configured server limit.
     * Returns the new ceiling, -1 when unavailable, or -2 at the limit.
     */
    public int expandNodeLimit(UUID owner, int amount) {
        return legacyCardResult(owner, CloudworksCardEffects.NODE_EXPANSION.withAmount(amount));
    }

    public int increaseWorkRate(UUID owner) {
        return legacyCardResult(owner, CloudworksCardEffects.WORK_RATE);
    }

    public int increaseLineBufferMultiplier(UUID owner, UUID lineId) {
        if (parent == null) return domain(owner).increaseLineBufferMultiplier(owner, lineId);
        synchronized (this) {
            ProductionLineData line = owned(owner, lineId);
            if (line == null || line.enabled()) return -1;
            int maximum = CloudworksConfig.settings().maxLineBufferMultiplier;
            if (line.nodeBufferMultiplier() >= maximum) return -2;
            ProductionLineData previous = ProductionLineData.read(line.write());
            line.setNodeBufferMultiplier(line.nodeBufferMultiplier() + 1);
            try {
                publishTags(owner, requireEnabled(owner).write(), Collections.singletonList(line.write()));
                drainInteraction();
                return line.nodeBufferMultiplier();
            } catch (RuntimeException failure) {
                lines.put(lineId, previous);
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

    /**
     * Mirrors the overworld day cycle in the cloud clock's 1..24000 domain.
     */
    public synchronized int syncSimulatedClock(long worldTime) {
        globalSimulatedTick = (int) Math.floorMod(worldTime, 24000L) + 1;
        return globalSimulatedTick;
    }

    public int simulatedTick() {
        return globalSimulatedTick;
    }

    public CloudworksGenerationContext generationContext(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.generationContext(owner);
        }
        synchronized (this) {
            requireEnabled(owner);
            return new CloudworksGenerationContext(0,
                    parent == null ? globalSimulatedTick : parent.globalSimulatedTick,
                    0, false);
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
            return uploadMachines(source, lineId, stack, count, 0, 0);
        }
    }

    /**
     * Coordinate form with the new node's editor placement (GUI supplies it later).
     */
    public CloudworksUploadResult uploadMachines(ICloudworksUploadSource source,
                                                 UUID lineId, ItemStack stack, int count, int x, int z) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(Objects.requireNonNull(source, "source").owner());
            return target.uploadMachines(source, lineId, stack, count, x, z);
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
            if (line.machines().size() + line.auxiliaries().size() >= Math.min(cloud.maxMachineNodes(),
                    CloudworksConfig.settings().maxMachineNodes))
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.NODE_LIMIT);
            if (count > CloudworksConfig.settings().maxMachinesPerNode)
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.COUNT_INVALID);
            boolean auxiliaryUpload = CloudworksAuxiliaryNodeRegistry.byItem(stack) != null;
            if (auxiliaryUpload && count != 1)
                return CloudworksUploadResult.refused(CloudworksUploadResult.Refusal.COUNT_INVALID);
            if (!CloudworksRegistries.isRegistered(stack) && !auxiliaryUpload)
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
            CloudworksMachineCache cache = machineCache(owner);
            NBTTagCompound previousCache = cache.write();
            CloudworksData previousCloud = cloud.copyForRuntime();
            UUID nodeId = UUID.randomUUID();
            try {
                ItemStack machineStack = reservation.machineStack();
                machineStack.setCount(count);
                cache.deposit(machineStack, count);
                if (auxiliaryUpload) {
                    CloudworksAuxiliaryNodeData node = new CloudworksAuxiliaryNodeData(nodeId, machineStack, x, z);
                    node.setActive(true);
                    node.setUploadOrigin(transferId, sourceId);
                    line.auxiliaries().add(node);
                } else {
                    MachineNodeData node = new MachineNodeData(nodeId, machineStack, x, z);
                    node.setMachineCount(count);
                    node.setActive(true);
                    node.setUploadOrigin(transferId, sourceId);
                    line.machines().add(node);
                }
                refreshLineUpgrades(cloud, line);
                NBTTagCompound transfer = new NBTTagCompound();
                transfer.setInteger("schema", 1);
                transfer.setString("transferId", transferId.toString());
                transfer.setString("sourceId", sourceId.toString());
                transfer.setString("lineId", lineId.toString());
                transfer.setString("nodeId", nodeId.toString());
                if (auxiliaryUpload) transfer.setString("kind", "auxiliary");
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
                machineCaches.put(owner, CloudworksMachineCache.read(previousCache));
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
            if (count > CloudworksConfig.settings().maxMachinesPerNode - target.machineCount())
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
            CloudworksMachineCache cache = machineCache(owner);
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
                machineCaches.put(owner, CloudworksMachineCache.read(previousCache));
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
            for (CloudworksPlayerDataManager target : domains.values())
                total += target.completePendingUploadAcknowledgements();
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
                        if (!Objects.equals(
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
                        Mekanism.logger.warn(
                                "Cloudworks debit recovery shortfall transfer={} player={} missing={}",
                                entry.getString("transferId"), player, missing);
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
    public void applySourceDebts(EntityPlayerMP player) {
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
        Path production = ownerDirectory(owner).resolve("production");
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
        } catch (IOException | DirectoryIteratorException failure) {
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
            CloudworksRecycleBin bin = recycleBin(owner);
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
                recycleBins.put(owner, CloudworksRecycleBin.read(previousBin));
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
                throw error(validation.errorKey());
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
    /**
     * B5 edit guard: nodes frozen by a missing mod keep byte-identical data; the
     * only permitted change is removal. Links may not be added to or modified on
     * a frozen endpoint (deleting the node removes its links via removeNode).
     */
    static void refuseFrozenNodeEdits(NBTTagCompound previous, ProductionLineData line) {
        Set<UUID> frozen = new HashSet<>();
        for (MachineNodeData node : line.machines())
            if (node.frozenByMissingMod()) frozen.add(node.nodeId());
        if (frozen.isEmpty()) return;
        NBTTagList previousMachines = previous.getTagList("machines", 10);
        Map<UUID, NBTTagCompound> before = new HashMap<>();
        for (int index = 0; index < previousMachines.tagCount(); index++) {
            NBTTagCompound tag = previousMachines.getCompoundTagAt(index);
            before.put(UUID.fromString(tag.getString("nodeId")), tag);
        }
        for (MachineNodeData node : line.machines())
            if (node.frozenByMissingMod()) {
                NBTTagCompound snapshot = before.get(node.nodeId());
                if (snapshot == null || !snapshot.equals(node.write()))
                    throw error("node_frozen");
            }
        NBTTagList previousLinks = previous.getTagList("links", 10);
        Map<UUID, NBTTagCompound> beforeLinks = new HashMap<>();
        for (int index = 0; index < previousLinks.tagCount(); index++) {
            NBTTagCompound tag = previousLinks.getCompoundTagAt(index);
            beforeLinks.put(UUID.fromString(tag.getString("linkId")), tag);
        }
        for (ProductionLineLink link : line.links())
            if (frozen.contains(link.sourceNode()) || frozen.contains(link.targetNode())) {
                NBTTagCompound snapshot = beforeLinks.get(link.linkId());
                if (snapshot == null || !snapshot.equals(link.write()))
                    throw error("node_frozen");
            }
    }

    public void edit(UUID owner, UUID id, Consumer<ProductionLineData> edit) {
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
                refuseFrozenNodeEdits(previous, line);
                boolean cancelledBlueprint = cancelMissingPending(owner, line);
                CloudworksConfig.Settings settings =
                        CloudworksConfig.settings();
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
                settleNodeCustomUpgrades(cloud, line,
                        CloudworksUpgradeLimits.customAssignments(line.machines()));
                double storedEnergy = line.energy();
                line.recomputeUpgradeLimits();
                settleLineEnergy(cloud, line, storedEnergy);
                CloudworksTopologyValidator.Result valid = CloudworksTopologyValidator.validate(line);
                if (!valid.valid()) throw error(valid.errorKey());
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
                    List<CloudworksModuleEvents.Event> lifecycleEvents = CloudworksModuleEvents.candidateChanges(
                            ProductionLineData.read(previous), line, new CloudworksGenerationContext(0,
                                    parent == null ? globalSimulatedTick : parent.globalSimulatedTick, 0, false));
                    writeCheckpoint(line);
                    CloudworksModuleEvents.enqueue(lifecycleEvents);
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
        if (!CloudworksTerminalSlots.acceptsEnergy(stack)) {
            return null;
        }
        if (CloudworksAPI.isInfiniteEnergyItem(stack)) {
            return new TerminalEnergyPayload(1D, true);
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
            CloudworksLineDraft initial = CloudworksLineConfiguration.capture(line);
            initial.enabled = running;
            initial.maxNodes = Math.min(requireEnabled(owner).maxMachineNodes(),
                    CloudworksConfig.settings().maxMachineNodes);
            CloudworksLineEditSession session = new CloudworksLineEditSession(owner, id, running, structuralLine(line.write()), initial);
            CloudworksPendingBlueprint waiting = pendingBlueprints.get(owner);
            if (waiting != null && waiting.line.equals(id)) session.pending = waiting;
            activeEdits.put(session.token, session);
            return session;
        }
    }

    public void updateLineDraft(CloudworksLineEditSession session, CloudworksLineDraft draft) {
        if (parent == null) {
            domain(session.owner).updateLineDraft(session, draft);
            return;
        }
        synchronized (this) {
            requireEdit(session);
            CloudworksLineDraft next = CloudworksLineDraftCodec.copy(draft);
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
                if (!cancel && Arrays.equals(CloudworksLineDraftCodec.encode(next), CloudworksLineDraftCodec.encode(unchanged)))
                    return;
                if (cancel) commitLineEdit0(session, next, false);
                session.current = next;
            } catch (RuntimeException failure) {
                session.pending = previous;
                throw failure;
            }
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

    public CloudworksLineDraft blueprintExportDraft(UUID owner, UUID lineId, CloudworksLineEditSession session) {
        if (parent == null) return domain(owner).blueprintExportDraft(owner, lineId, session);
        synchronized (this) {
            ProductionLineData line = owned(owner, lineId);
            if (line == null) throw error("not_found");
            boolean editing = session != null && owner.equals(session.owner) && lineId.equals(session.line);
            if (editing) requireEdit(session);
            CloudworksLineDraft draft = editing ? session.snapshot() : CloudworksLineConfiguration.capture(line);
            CloudworksPendingBlueprint pending = editing ? session.pending : pendingBlueprints.get(owner);
            return pending != null && lineId.equals(pending.line) ? pending.exportDraft(draft) : draft;
        }
    }

    public void precheckBlueprint(CloudworksLineEditSession session, CloudworksBlueprint blueprint) {
        if (parent == null) {
            domain(session.owner).precheckBlueprint(session, blueprint);
            return;
        }
        synchronized (this) {
            requireEdit(session);
            blueprint.validate();
            CloudworksSpecialNodeStore.Snapshot specialNodes = specialNodeStore(session.owner).load();
            for (CloudworksLineDraft.Channel channel : blueprint.draft().channels) {
                if (!CloudworksSpecialNodeArchetypes.INFINITE_RESOURCES.toString()
                        .equals(channel.archetypeId)) continue;
                if (channel.resources.size() != 1
                        || !specialNodes.resources.containsKey(channel.resources.get(0).descriptor))
                    throw error("special_node_resource_unavailable");
            }
            CloudworksLineDraft combined = CloudworksLineDraftCodec.copy(session.current);
            combined.channels.addAll(blueprint.draft().channels);
            validateSpecialNodePermissions(session.owner, session.line, combined, false);
            if (session.pending != null || pendingBlueprints.get(session.owner) != null)
                throw error("blueprint_pending");
            int maximum = Math.min(requireEnabled(session.owner).maxMachineNodes(),
                    CloudworksConfig.settings().maxMachineNodes);
            if ((long) session.current.machines.size() + blueprint.draft().machines.size() > maximum)
                throw error("node_limit");
        }
    }

    public void placeBlueprint(CloudworksLineEditSession session, CloudworksBlueprint blueprint,
                               int anchorX, int anchorY) {
        if (parent == null) {
            domain(session.owner).placeBlueprint(session, blueprint, anchorX, anchorY);
            return;
        }
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
                node.active = machine.active;
                node.recipeType = machine.recipeType;
                node.oreRules.addAll(machine.oreRules);
                node.fusionInjectionRate = machine.fusionInjectionRate;
                if (enough) node.assignedCustom.putAll(machine.assignedCustom);
                node.waiting = waiting == null ? null : waiting.id;
                node.waitingMechanism = enough ? "" : CloudworksNodeMechanisms.activeId(machine);
                node.required = enough ? 0 : machine.count;
                node.missing = node.required;
                next.machines.add(node);
            }
            for (CloudworksLineDraft.Channel channel : placed.channels) {
                channel.waiting = waiting == null ? null : waiting.id;
                next.channels.add(channel);
            }
            next.links.addAll(placed.links);
            for (QIOResourceDescriptor marker : placed.markers)
                if (next.markers.size() < 25 && !next.markers.contains(marker)) next.markers.add(marker);
            next.explicitRouting |= placed.explicitRouting || !placed.channels.isEmpty();
            next.receivingFlow = placed.receivingFlow;
            next.sendingFlow = placed.sendingFlow;
            CloudworksPendingBlueprint previous = session.pending;
            session.pending = waiting;
            try {
                if (enough) coverBlueprintUpgrades(next);
                normalizeUpgradeLimits(next);
                validateEditorDraft(session, next);
                // 9.x portability: adopt the blueprint's extension carrier before the
                // commit re-reads the live line; the merge is target-wins and bounded.
                NBTTagCompound extensionCarrier = blueprint.extensionsCarrier();
                if (!extensionCarrier.getKeySet().isEmpty()) {
                    ProductionLineData live = owned(session.owner, session.line);
                    if (live != null) live.extensions().mergeFrom(extensionCarrier);
                }
                commitLineEdit0(session, next, false);
                session.current = next;
            } catch (RuntimeException failure) {
                session.pending = previous;
                throw failure;
            }
        }
    }

    public static final class BlueprintRequirement {
        public final ItemStack stack;
        public final long required, missing;

        public BlueprintRequirement(ItemStack stack, long required, long missing) {
            this.stack = stack.copy();
            this.required = required;
            this.missing = missing;
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
        List<BlueprintRequirement> result = new ArrayList<>(CloudworksBlueprintShortages.requirements(
                group, session.current, cache, reservationLines(session.owner, cache)));
        CloudworksData cloud = requireEnabled(session.owner);
        ProductionLineData line = owned(session.owner, session.line);
        Map<ResourceLocation, Long> assigned = CloudworksUpgradeLimits
                .draftCustomAssignments(session.current.machines);
        for (Map.Entry<ResourceLocation, Long> entry : CloudworksUpgradeLimits
                .draftCustomAssignments(group.machines).entrySet()) {
            MachineModuleType type =
                    CloudworksMachineModuleRegistry.type(entry.getKey());
            if (type == null || type.slot() != MachineModuleType.Slot.CUSTOM)
                throw error("node_upgrade_invalid");
            long available = CloudworksUpgradeLimits.availableCustom(
                    cloud.customUpgradePool().count(entry.getKey()), line.customUpgradePool().count(entry.getKey()),
                    assigned.getOrDefault(entry.getKey(), 0L));
            result.add(new BlueprintRequirement(type.item(), entry.getValue(), Math.max(0, entry.getValue() - available)));
        }
        return result;
    }

    private static void coverBlueprintUpgrades(CloudworksLineDraft draft) {
        // Native per-node assignments retired (axis-priority batch 3c); custom
        // module coverage is computed through the custom requirement loop.
    }

    public boolean fillBlueprint(CloudworksLineEditSession session, boolean cancel) {
        if (parent == null) return domain(session.owner).fillBlueprint(session, cancel);
        synchronized (this) {
            requireEdit(session);
            CloudworksPendingBlueprint waiting = session.pending;
            if (waiting == null) throw error("not_found");
            if (!cancel && blueprintRequirements(session).stream().anyMatch(requirement -> requirement.missing > 0))
                return false;
            CloudworksLineDraft next = CloudworksLineDraftCodec.copy(session.current);
            if (cancel) CloudworksDraftGeometry.remove(next, waiting.nodes());
            else {
                Map<UUID, CloudworksLineDraft.Machine> desired = new HashMap<>();
                waiting.placed().machines.forEach(machine -> desired.put(machine.id, machine));
                for (CloudworksLineDraft.Machine machine : next.machines)
                    if (waiting.id.equals(machine.waiting)) {
                        CloudworksLineDraft.Machine original = desired.get(machine.id);
                        if (original == null) throw error("editor");
                        machine.count = original.count;
                        machine.active = original.active;
                        machine.waiting = null;
                        machine.waitingMechanism = "";
                        machine.assignedCustom.clear();
                        machine.assignedCustom.putAll(original.assignedCustom);
                        machine.required = machine.missing = 0;
                    }
                for (CloudworksLineDraft.Channel channel : next.channels)
                    if (waiting.id.equals(channel.waiting)) channel.waiting = null;
            }
            session.pending = null;
            try {
                if (!cancel) coverBlueprintUpgrades(next);
                normalizeUpgradeLimits(next);
                validateEditorDraft(session, next);
                commitLineEdit0(session, next, false);
                session.current = next;
            } catch (RuntimeException failure) {
                session.pending = waiting;
                throw failure;
            }
            return true;
        }
    }

    private void requireEdit(CloudworksLineEditSession session) {
        if (session == null || activeEdits.get(session.token) != session) throw error("stale");
        ProductionLineData line = owned(session.owner, session.line);
        if (line == null || line.enabled() || !session.baseline.equals(structuralLine(line.write())))
            throw error("stale");
    }

    private boolean cancelMissingPending(UUID owner, ProductionLineData line) {
        CloudworksPendingBlueprint pending = pendingBlueprints.get(owner);
        if (pending == null || !pending.line.equals(line.lineId())) return false;
        Set<UUID> present = new HashSet<>();
        line.machines().forEach(node -> present.add(node.nodeId()));
        line.channels().forEach(node -> present.add(node.id()));
        line.auxiliaries().forEach(node -> present.add(node.nodeId()));
        Set<UUID> waiting = pending.nodes();
        if (present.containsAll(waiting)) return false;
        line.machines().removeIf(node -> waiting.contains(node.nodeId()));
        line.channels().removeIf(node -> waiting.contains(node.id()));
        line.auxiliaries().removeIf(node -> waiting.contains(node.nodeId()));
        line.links().removeIf(link -> waiting.contains(link.sourceNode()) || waiting.contains(link.targetNode()));
        return true;
    }

    private static Set<UUID> nodeIds(CloudworksLineDraft draft) {
        Set<UUID> ids = new HashSet<>();
        draft.machines.forEach(machine -> ids.add(machine.id));
        draft.channels.forEach(channel -> ids.add(channel.id));
        draft.auxiliaries.forEach(auxiliary -> ids.add(auxiliary.id));
        return ids;
    }

    private static void normalizeUpgradeLimits(CloudworksLineDraft draft) {
        CloudworksUpgradeLimits.normalizeDraft(draft);
        draft.maxSpeed = CloudworksUpgradeLimits.draftCapacity(
                Upgrade.SPEED, draft.machines);
        draft.maxEnergy = CloudworksUpgradeLimits.draftCapacity(
                Upgrade.ENERGY, draft.machines);
        draft.speed = Math.min(draft.speed, draft.maxSpeed);
        draft.energy = Math.min(draft.energy, draft.maxEnergy);
    }

    private void validateEditorDraft(CloudworksLineEditSession session, CloudworksLineDraft draft) {
        requireEdit(session);
        if (draft == null || !session.line.equals(draft.line)) throw error("editor");
        if (draft.nodeBufferMultiplier < 1
                || draft.nodeBufferMultiplier > CloudworksConfig.settings().maxLineBufferMultiplier)
            throw error("line_capacity_max");
        checkedName(draft.name);
        CloudworksData cloud = requireEnabled(session.owner);
        ProductionLineData existing = owned(session.owner, session.line);
        int limit = Math.min(cloud.maxMachineNodes(), CloudworksConfig.settings().maxMachineNodes);
        if (draft.machines.size() + draft.auxiliaries.size()
                > Math.max(limit, existing.machines().size() + existing.auxiliaries().size()))
            throw error("node_limit");
        Set<UUID> ids = new HashSet<>(), waitingIds = session.pending == null ? Collections.emptySet() : session.pending.nodes();
        Map<UUID, CloudworksLineDraft.Machine> before = new HashMap<>();
        session.current.machines.forEach(machine -> before.put(machine.id, machine));
        Map<UUID, MachineNodeData> persistedNodes = new HashMap<>();
        existing.machines().forEach(machine -> persistedNodes.put(machine.nodeId(), machine));
        Map<UUID, CloudworksLineDraft.Machine> pendingMachines = new HashMap<>();
        if (session.pending != null)
            session.pending.placed().machines.forEach(machine -> pendingMachines.put(machine.id, machine));
        Map<MachineKey, Long> claims = new HashMap<>();
        CloudworksMachineCache cache = machineCache(session.owner);
        for (CloudworksLineDraft.Machine machine : draft.machines) {
            CloudworksBlueprint.coordinate(machine.x);
            CloudworksBlueprint.coordinate(machine.y);
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
                machine.waitingMechanism = CloudworksNodeMechanisms.activeId(desired);
            } else {
                machine.required = machine.missing = 0;
                machine.waitingMechanism = "";
            }
            CloudworksLineDraft.Machine original = before.get(machine.id);
            if (mekanism.cloudworks.common.recipe.CloudworksFusionNodeContracts.isController(machine.type))
                mekanism.cloudworks.common.recipe.CloudworksFusionNodeContracts.validateInjection(machine.fusionInjectionRate);
            else if (machine.fusionInjectionRate != 2) throw error("fusion_injection_invalid");
            if (original == null || !original.oreRules.equals(machine.oreRules)) validateOreRules(machine);
            if (!CloudworksRegistries.acceptsRecipeType(machine.type, machine.recipeType)
                    && (original == null || !Objects.equals(original.recipeType, machine.recipeType)))
                throw error("recipe_type");
            if (original != null && !CloudworksRegistries.identityKey(original.type).equals(
                    CloudworksRegistries.identityKey(machine.type))) throw error("machine");
            long maximum = CloudworksConfig.settings().maxMachinesPerNode;
            if (machine.count > maximum && (original == null || machine.count > original.count))
                throw error("machine_count_limit");
            for (Map.Entry<String, Long> entry : machine.assignedCustom.entrySet()) {
                ResourceLocation customId;
                try {
                    customId = new ResourceLocation(entry.getKey());
                } catch (Exception invalid) {
                    throw error("node_upgrade_invalid");
                }
                long previous = original == null ? 0 : original.assignedCustom(entry.getKey());
                if (entry.getValue() <= 0 || entry.getValue() > CloudworksUpgradeLimits
                        .draftCustomCapacity(machine, customId) && entry.getValue() > previous)
                    throw error("node_upgrade_invalid");
            }
            CloudworksNodeMechanisms.validateChange(persistedNodes.get(machine.id), machine, draft.links);
            int type = cache.typeIndex(machine.type);
            if (machine.count > 0) {
                if (type < 0) throw error("machine_unavailable");
                claims.merge(CloudworksRegistries.identityKey(machine.type), machine.count, Math::addExact);
            }
        }
        for (CloudworksLineDraft.Channel channel : draft.channels) {
            CloudworksBlueprint.coordinate(channel.x);
            CloudworksBlueprint.coordinate(channel.y);
            if (channel.id == null || !ids.add(channel.id) || channel.mode == null) throw error("editor");
            if (channel.archetypeId == null) channel.archetypeId = "";
            if (!channel.archetypeId.isEmpty()) {
                CloudworksSpecialNodeArchetype archetype =
                        CloudworksSpecialNodeArchetypes.get(channel.archetypeId);
                if (archetype == null || archetype.source() != channel.mode.input())
                    throw error("special_node_invalid");
                // 9.1 deepening: the archetype's configuration validator runs on the
                // manager lock at commit; a refusal rejects the whole edit.
                try {
                    archetype.validateConfig(channel.config);
                } catch (RuntimeException refused) {
                    throw error("special_node_invalid");
                }
                validateSpecialNodeChannel(session.owner, channel, false);
            }
            if (waitingIds.contains(channel.id) != (channel.waiting != null)
                    || channel.waiting != null && (session.pending == null || !channel.waiting.equals(session.pending.id)))
                throw error("editor");
            Set<QIOResourceDescriptor> resources = new HashSet<>();
            long sum = 0;
            for (CloudworksLineDraft.Resource resource : channel.resources) {
                if (resource.amount <= 0 || resource.descriptor == null || !resource.descriptor.isResolved()
                        || !resources.add(resource.descriptor)) throw error("editor");
                sum = Math.addExact(sum, resource.amount);
            }
        }
        for (CloudworksLineDraft.Auxiliary auxiliary : draft.auxiliaries) {
            CloudworksBlueprint.coordinate(auxiliary.x);
            CloudworksBlueprint.coordinate(auxiliary.y);
            if (auxiliary.id == null || !ids.add(auxiliary.id)
                    || CloudworksAuxiliaryNodeRegistry.byItem(auxiliary.type) == null
                    || auxiliary.range < 0 || auxiliary.range > 1_000_000)
                throw error("auxiliary_node_invalid");
            Set<UUID> targets = new HashSet<>();
            for (UUID target : auxiliary.targets)
                if (target == null || !targets.add(target) || !draft.machines.stream().anyMatch(machine -> target.equals(machine.id)))
                    throw error("auxiliary_node_invalid");
            Set<QIOResourceDescriptor> resources = new HashSet<>();
            long total = 0;
            for (CloudworksLineDraft.Resource resource : auxiliary.resources) {
                if (resource.descriptor == null || !resource.descriptor.isResolved()
                        || resource.amount <= 0 || !resources.add(resource.descriptor))
                    throw error("auxiliary_node_invalid");
                total = Math.addExact(total, resource.amount);
            }
            if (total > 10_000) throw error("auxiliary_node_invalid");
            int type = cache.typeIndex(auxiliary.type);
            if (type < 0) throw error("machine_unavailable");
            claims.merge(CloudworksRegistries.identityKey(auxiliary.type), 1L, Math::addExact);
        }
        validateSpecialNodePermissions(session.owner, session.line, draft, false);
        Set<UUID> wires = new HashSet<>();
        for (CloudworksLineDraft.Wire wire : draft.links)
            if (!wires.add(wire.id) || !ids.contains(wire.source) || !ids.contains(wire.target)
                    || !Double.isFinite(wire.ratio) || wire.ratio < 0 || wire.ratio > 1
                    || wire.filters.stream().anyMatch(resource -> resource == null || !resource.isResolved())
                    || new HashSet<>(wire.filters).size() != wire.filters.size())
                throw error("editor_link_data_invalid");
        if (draft.markers.size() > 25 || new HashSet<>(draft.markers).size() != draft.markers.size()
                || draft.markers.stream().anyMatch(resource -> resource == null || !resource.isResolved()))
            throw error("editor");
        for (ProductionLineData line : reservationLines(session.owner, cache)) {
            if (line.lineId().equals(session.line)) continue;
            for (MachineNodeData machine : line.machines()) {
                int type = cache.typeIndex(machine.machine());
                if (type >= 0)
                    claims.merge(CloudworksRegistries.identityKey(machine.machine()), machine.machineCount(), Math::addExact);
            }
            for (CloudworksAuxiliaryNodeData auxiliary : line.auxiliaries()) {
                int type = cache.typeIndex(auxiliary.type());
                if (type >= 0) claims.merge(CloudworksRegistries.identityKey(auxiliary.type()), 1L, Math::addExact);
            }
        }
        Map<MachineKey, Long> holdings = CloudworksBlueprintShortages.holdings(cache);
        for (Map.Entry<MachineKey, Long> claim : claims.entrySet())
            if (claim.getValue() > holdings.getOrDefault(claim.getKey(), 0L)) throw error("machine_unavailable");
        CloudworksTopologyValidator.Result topology = validateEditorTopology(session.owner, draft);
        if (!topology.valid()) throw error(topology.errorKey());
        int maxSpeed = CloudworksUpgradeLimits.draftCapacity(
                Upgrade.SPEED, draft.machines);
        int maxEnergy = CloudworksUpgradeLimits.draftCapacity(
                Upgrade.ENERGY, draft.machines);
        if (draft.speed < 0 || draft.energy < 0 || draft.speed > Math.max(maxSpeed, existing.speedUpgrades())
                || draft.energy > Math.max(maxEnergy, existing.energyUpgrades())
                || draft.speed - existing.speedUpgrades() > cloud.upgradePool(true)
                || draft.energy - existing.energyUpgrades() > cloud.upgradePool(false)) throw error("pool_empty");
        for (Map.Entry<ResourceLocation, Long> entry : CloudworksUpgradeLimits
                .draftCustomAssignments(draft.machines).entrySet())
            if (entry.getValue() > existing.customUpgradePool().count(entry.getKey())
                    && entry.getValue() - existing.customUpgradePool().count(entry.getKey())
                    > cloud.customUpgradePool().count(entry.getKey())) throw error("pool_empty");
        draft.maxNodes = limit;
        draft.maxMachinesPerNode = CloudworksConfig.settings().maxMachinesPerNode;
        draft.maxSpeed = maxSpeed;
        draft.maxEnergy = maxEnergy;
    }

    private void validateSpecialNodeChannel(UUID owner, CloudworksLineDraft.Channel channel,
                                            boolean requireResource) {
        if (CloudworksNodeEnvironment.source(channel.archetypeId)) {
            if (channel.mode != CloudworksChannelNode.Mode.RECEIVE_INPUT || !channel.resources.isEmpty()
                    || !channel.config.isEmpty()) throw error("special_node_invalid");
            return;
        }
        if (!CloudworksSpecialNodeArchetypes.INFINITE_RESOURCES.toString().equals(channel.archetypeId))
            throw error("special_node_invalid");
        if (channel.mode != CloudworksChannelNode.Mode.RECEIVE_INPUT) throw error("special_node_invalid");
        CloudworksSpecialNodeStore.Snapshot specialNodes = specialNodeStore(owner).load();
        if (!specialNodes.available) throw error("special_node_resource_unavailable");
        if (channel.resources.size() > 1) throw error("special_node_resource_count");
        if (channel.resources.isEmpty()) {
            if (requireResource) throw error("special_resource_required");
            return;
        }
        CloudworksLineDraft.Resource selected = channel.resources.get(0);
        CloudworksSpecialNodeStore.Entry entry = specialNodes.resources.get(selected.descriptor);
        if (entry == null) throw error("special_node_resource_unavailable");
        // Source manifests store the selection only. Rates always come from the owner's resource file.
        selected.amount = 1;
    }

    private void validateSpecialNodePermissions(UUID owner, UUID lineId, CloudworksLineDraft draft,
                                                boolean requireSelected) {
        CloudworksSpecialNodeStore.Snapshot specialNodes = specialNodeStore(owner).load();
        Map<QIOResourceDescriptor, Long> counts = new HashMap<>();
        Map<String, Long> environmentCounts = new HashMap<>();
        for (CloudworksLineDraft.Channel channel : draft.channels) {
            if (channel.archetypeId.isEmpty()) continue;
            validateSpecialNodeChannel(owner, channel, requireSelected);
            if (CloudworksNodeEnvironment.source(channel.archetypeId)) {
                environmentCounts.merge(channel.archetypeId, 1L, Math::addExact);
                continue;
            }
            if (!channel.resources.isEmpty())
                counts.merge(channel.resources.get(0).descriptor, 1L, Math::addExact);
        }
        for (ProductionLineData other : lines(owner)) {
            if (other.lineId().equals(lineId)) continue;
            for (CloudworksChannelNode channel : other.channels()) {
                if (CloudworksNodeEnvironment.source(channel.archetypeId()))
                    environmentCounts.merge(channel.archetypeId(), 1L, Math::addExact);
                else if (CloudworksSpecialNodeArchetypes.INFINITE_RESOURCES.toString().equals(channel.archetypeId()))
                    for (QIOResourceDescriptor resource : channel.resources().keySet())
                        counts.merge(resource, 1L, Math::addExact);
            }
        }
        for (Map.Entry<String, Long> environment : environmentCounts.entrySet())
            if (environment.getValue() > CloudworksNodeEnvironment.quota(
                    requireEnabled(owner), environment.getKey())) throw error("special_node_limit");
        for (Map.Entry<QIOResourceDescriptor, Long> count : counts.entrySet()) {
            CloudworksSpecialNodeStore.Entry entry = specialNodes.resources.get(count.getKey());
            if (entry == null) continue;
            if (!entry.rules.unlimitedPlacement && count.getValue() > entry.rules.nodeLimit)
                throw error("special_node_limit");
        }
    }

    private static CloudworksTopologyValidator.Result validateEditorTopology(UUID owner, CloudworksLineDraft draft) {
        ProductionLineData line = new ProductionLineData(draft.line, owner);
        line.setExplicitRouting(draft.explicitRouting || !draft.channels.isEmpty());
        for (CloudworksLineDraft.Machine value : draft.machines) {
            MachineNodeData node = new MachineNodeData(value.id, value.type, value.x, value.y);
            node.setMachineCount(value.count);
            node.setActive(value.active);
            node.setRecipeType(value.recipeType);
            node.setOreRules(value.oreRules);
            node.setFusionInjectionRate(value.fusionInjectionRate);
            CloudworksNodeMechanisms.applyAssignments(value, node);
            node.setWaitingBlueprint(value.waiting);
            node.setWaitingCount(value.required);
            node.setWaitingMechanism(value.waitingMechanism);
            line.machines().add(node);
        }
        for (CloudworksLineDraft.Channel value : draft.channels) {
            CloudworksChannelNode node = new CloudworksChannelNode(value.id, value.mode, Long.MAX_VALUE, value.archetypeId);
            node.setPosition(value.x, value.y);
            node.setEnabled(value.active);
            node.setWaitingBlueprint(value.waiting);
            node.setConfig(value.config);
            for (CloudworksLineDraft.Resource resource : value.resources)
                node.setResource(resource.descriptor, resource.amount);
            line.channels().add(node);
        }
        for (CloudworksLineDraft.Auxiliary value : draft.auxiliaries) {
            CloudworksAuxiliaryNodeData node =
                    new CloudworksAuxiliaryNodeData(
                            value.id, value.type, value.x, value.y);
            node.setRange(value.range);
            node.setActive(value.active);
            node.setLinkedTargets(value.targets);
            for (CloudworksLineDraft.Resource resource : value.resources)
                node.input().insertIgnoringTypeCap(resource.descriptor, resource.amount);
            line.auxiliaries().add(node);
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

    private static void validateOreRules(CloudworksLineDraft.Machine value) {
        if (!value.oreRules.isEmpty() && !mekanism.cloudworks.common.recipe.CloudworksOredictionificator.isMachine(value.type))
            throw new IllegalArgumentException("cloudworks.error.ore_rule_invalid");
        mekanism.cloudworks.common.recipe.CloudworksOredictionificator.validate(value.oreRules);
    }

    /**
     * Unexpected container closure discards only the local edit, never replays its initial configuration.
     */
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
                               CloudworksLineDraft draft) {
        if (session == null) return;
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(session.owner);
            target.commitLineEdit(session, draft);
            return;
        }
        synchronized (this) {
            commitLineEdit0(session, draft, true);
        }
    }

    private void commitLineEdit0(CloudworksLineEditSession session, CloudworksLineDraft draft, boolean finish) {
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
                && !CloudworksConfig.settings().offlineProcessingEnabled)
            throw error("offline_disabled");
        CloudworksRecycleBin bin = CloudworksRecycleBin.read(recycleBin(owner).write());
        int nodeLimit = Math.min(cloud.maxMachineNodes(),
                CloudworksConfig.settings().maxMachineNodes);
        if (draft.machines.size() + draft.auxiliaries.size()
                > Math.max(nodeLimit, current.machines().size() + current.auxiliaries().size()))
            throw error("node_limit");
        settleNodeCustomUpgrades(cloud, next,
                CloudworksUpgradeLimits.draftCustomAssignments(draft.machines));
        CloudworksMachineCache cache = machineCache(owner);
        Map<UUID, MachineNodeData> oldMachines = new HashMap<>();
        for (MachineNodeData machine : next.machines()) oldMachines.put(machine.nodeId(), machine);
        Map<UUID, CloudworksChannelNode> oldChannels = new HashMap<>();
        for (CloudworksChannelNode channel : next.channels())
            oldChannels.put(channel.id(), channel);
        Map<UUID, CloudworksAuxiliaryNodeData> oldAuxiliaries = new HashMap<>();
        for (CloudworksAuxiliaryNodeData auxiliary : next.auxiliaries())
            oldAuxiliaries.put(auxiliary.nodeId(), auxiliary);
        Map<UUID, ProductionLineLink> oldLinks = new HashMap<>();
        for (ProductionLineLink link : next.links()) oldLinks.put(link.linkId(), link);
        next.machines().clear();
        next.channels().clear();
        next.auxiliaries().clear();
        next.links().clear();
        Set<UUID> ids = new HashSet<>();
        Map<Integer, Long> wanted = new HashMap<>();
        for (CloudworksLineDraft.Machine value : draft.machines) {
            if (!ids.add(value.id) || value.count < 0 || value.count == 0 && value.waiting == null
                    || oldChannels.containsKey(value.id)) throw error("editor");
            int type = cache.typeIndex(value.type);
            if (value.count > 0 && type < 0 || !CloudworksRegistries.isRegistered(value.type)) throw error("machine");
            MachineNodeData node = oldMachines.remove(value.id);
            if (value.count > CloudworksConfig.settings().maxMachinesPerNode
                    && (node == null || value.count > node.machineCount())) throw error("machine_count_limit");
            if (node != null && !CloudworksRegistries.identityKey(node.machine())
                    .equals(CloudworksRegistries.identityKey(value.type))) throw error("machine");
            if (node == null) {
                node = new MachineNodeData(value.id, value.type, value.x, value.y);
                node.setPooledClaim(true);
            } else if (node.machineCount() != value.count) {
                node.cancelOperation();
                node.setProgress(0, 1);
                node.setPooledClaim(true);
            }
            CloudworksThermalPlan previousThermal = node.capabilities().thermal();
            node.setMachineCount(value.count);
            node.setWaitingBlueprint(value.waiting);
            node.setWaitingCount(value.required);
            node.setWaitingMechanism(value.waitingMechanism);
            node.setPosition(value.x, value.y);
            node.setActive(value.active && value.count > 0);
            node.setRecipeType(value.recipeType);
            node.setOreRules(value.oreRules);
            node.setFusionInjectionRate(value.fusionInjectionRate);
            boolean customChanged = node.customUpgrades().size() != value.assignedCustom.size();
            if (!customChanged) for (Map.Entry<String, Long> entry : value.assignedCustom.entrySet())
                if (node.customUpgrades().count(new ResourceLocation(entry.getKey())) != entry.getValue()) {
                    customChanged = true;
                    break;
                }
            if (customChanged) {
                node.cancelOperation();
                node.setProgress(0, 1);
                Map<ResourceLocation, Long> converted = new LinkedHashMap<>();
                for (Map.Entry<String, Long> entry : value.assignedCustom.entrySet())
                    converted.put(new ResourceLocation(entry.getKey()), entry.getValue());
                node.customUpgrades().setAll(converted);
            }
            CloudworksThermalPlan nextThermal = node.capabilities().thermal();
            boolean sameFusionReservoirs = mekanism.cloudworks.common.recipe.CloudworksFusionNodeContracts.isController(node.machine())
                    && previousThermal != null && nextThermal != null
                    && previousThermal.kind == CloudworksThermalPlan.Kind.FUSION && nextThermal.kind == CloudworksThermalPlan.Kind.FUSION
                    && previousThermal.capacity == nextThermal.capacity && previousThermal.plasmaCapacity == nextThermal.plasmaCapacity;
            if (nextThermal != previousThermal && !sameFusionReservoirs) node.setThermalState(null);
            next.machines().add(node);
            if (value.count > 0) wanted.merge(type, value.count, Math::addExact);
        }
        for (CloudworksLineDraft.Auxiliary value : draft.auxiliaries) {
            int type = cache.typeIndex(value.type);
            if (type < 0) throw error("machine_unavailable");
            wanted.merge(type, 1L, Math::addExact);
        }
        for (ProductionLineData other : lines(owner)) {
            if (other.lineId().equals(next.lineId())) continue;
            for (MachineNodeData node : other.machines()) {
                int type = cache.typeIndex(node.machine());
                if (type >= 0) wanted.merge(type, node.machineCount(), Math::addExact);
            }
            for (CloudworksAuxiliaryNodeData auxiliary : other.auxiliaries()) {
                int type = cache.typeIndex(auxiliary.type());
                if (type >= 0) wanted.merge(type, 1L, Math::addExact);
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
        for (CloudworksLineDraft.Channel value : draft.channels) {
            if (!ids.add(value.id) || current.machines().stream().anyMatch(n -> n.nodeId().equals(value.id)))
                throw error("editor");
            CloudworksChannelNode node = oldChannels.remove(value.id);
            if (node != null && (node.mode() != value.mode
                    || !Objects.equals(node.archetypeId(), value.archetypeId))) throw error("editor");
            if (node == null) node = new CloudworksChannelNode(
                    value.id, value.mode, Long.MAX_VALUE, value.archetypeId);
            if (node.special())
                for (QIOResourceDescriptor resource : node.buffer().snapshot().keySet())
                    node.buffer().extract(resource, Long.MAX_VALUE);
            for (QIOResourceDescriptor descriptor : new ArrayList<>(node.resources().keySet()))
                node.setResource(descriptor, 0);
            Set<QIOResourceDescriptor> resources = new HashSet<>();
            for (CloudworksLineDraft.Resource resource : value.resources) {
                if (!resources.add(resource.descriptor) || resource.amount <= 0) throw error("editor");
                node.setResource(resource.descriptor, resource.amount);
            }
            node.setPosition(value.x, value.y);
            node.setEnabled(value.active);
            node.setWaitingBlueprint(value.waiting);
            next.channels().add(node);
        }
        for (CloudworksChannelNode removed : oldChannels.values()) {
            if (removed.special()) continue;
            for (Map.Entry<QIOResourceDescriptor, Long> resource : removed.buffer().snapshot().entrySet())
                discarded |= bin.deposit(resource.getKey(), resource.getValue()) > 0;
        }
        for (CloudworksLineDraft.Auxiliary value : draft.auxiliaries) {
            if (!ids.add(value.id)) throw error("editor");
            if (CloudworksAuxiliaryNodeRegistry.byItem(value.type) == null)
                throw error("auxiliary_node_invalid");
            CloudworksAuxiliaryNodeData node = oldAuxiliaries.remove(value.id);
            if (node == null) node = new CloudworksAuxiliaryNodeData(
                    value.id, value.type, value.x, value.y);
            else if (!ItemStack.areItemsEqual(node.type(), value.type)
                    || !ItemStack.areItemStackTagsEqual(node.type(), value.type))
                throw error("auxiliary_node_invalid");
            for (QIOResourceDescriptor resource : new ArrayList<>(node.input().snapshot().keySet()))
                node.input().extract(resource, Long.MAX_VALUE);
            node.setPosition(value.x, value.y);
            node.setRange(value.range);
            node.setActive(value.active);
            node.setLinkedTargets(value.targets);
            for (CloudworksLineDraft.Resource resource : value.resources)
                node.input().insertIgnoringTypeCap(resource.descriptor, resource.amount);
            next.auxiliaries().add(node);
        }
        for (CloudworksAuxiliaryNodeData removed : oldAuxiliaries.values())
            for (Map.Entry<QIOResourceDescriptor, Long> resource : removed.input().snapshot().entrySet())
                discarded |= bin.deposit(resource.getKey(), resource.getValue()) > 0;
        Set<UUID> linkIds = new HashSet<>();
        Set<String> endpoints = new HashSet<>();
        for (CloudworksLineDraft.Wire value : draft.links) {
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
        next.setNodeBufferMultiplier(draft.nodeBufferMultiplier);
        next.setUpgrades(draft.speed, draft.energy);
        settleLineEnergy(cloud, next, current.energy());
        next.setEnergyFlows(draft.receivingFlow, draft.sendingFlow);
        next.setRunWhileOwnerOffline(draft.runWhileOwnerOffline);
        next.setExplicitRouting(draft.explicitRouting || !next.channels().isEmpty());
        next.dynamicResourceDisplayList().clear();
        for (QIOResourceDescriptor marker : draft.markers) {
            if (!marker.isResolved()) throw error("editor");
            next.dynamicResourceDisplayList().add(marker.write().toString());
        }
        if (finish) {
            validateSpecialNodePermissions(owner, session.line, draft, true);
            CloudworksTopologyValidator.Result topology = CloudworksTopologyValidator.validate(next);
            if (!topology.valid()) throw error(topology.errorKey());
            if (draft.enabled && next.machines().isEmpty()) throw error("editor_active_line_requires_machine");
        }
        next.setEnabled(finish && draft.enabled);
        CloudworksPendingBlueprint durablePending = pendingBlueprints.get(owner);
        NBTTagCompound pendingWrite;
        if (session.pending != null) pendingWrite = session.pending.write();
        else if (durablePending != null && !durablePending.line.equals(session.line))
            pendingWrite = durablePending.write();
        else pendingWrite = CloudworksPendingBlueprint.empty();
        publish0(owner, cloud.write(), Collections.singletonList(next.write()), bin.write(), null, null, null,
                true, false, pendingWrite);
        recycleBins.put(owner, bin);
        if (discarded) pendingRecycleOverflow.add(owner);
        if (finish) activeEdits.remove(session.token);
        else session.baseline = structuralLine(next.write());
    }

    /**
     * Explicit recovery atomically clears a node and moves resources to recycle.
     */
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
                for (CloudworksLineEditSession edit : activeEdits.values())
                    if (edit.line.equals(id)) {
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
        // Already validated worker publications need no main-thread node decode.
        // Keep one ordered intent per owner, including any pending warehouse delta.
        Map<UUID, Map<UUID, NBTTagCompound>> frozen = new LinkedHashMap<>();
        Set<UUID> savedRuntimeLines = new HashSet<>();
        for (Map.Entry<UUID, Set<UUID>> dirtyOwner : runtimeDirtyLines.entrySet()) {
            UUID owner = dirtyOwner.getKey();
            if (skipFailedOwners && failedOwners.contains(owner)) continue;
            Map<UUID, NBTTagCompound> ownerLines = new LinkedHashMap<>();
            for (UUID id : dirtyOwner.getValue()) {
                NBTTagCompound published;
                if (runtimeUnmaterialized.contains(id)) published = publishedLineTag(id);
                else {
                    ProductionLineData materialized = lines.get(id);
                    published = materialized == null ? null : materialized.write();
                }
                if (published == null) continue;
                ownerLines.put(id, published);
                savedRuntimeLines.add(id);
            }
            if (!ownerLines.isEmpty()) frozen.put(owner, ownerLines);
        }
        for (Map.Entry<UUID, Map<UUID, NBTTagCompound>> entry : frozen.entrySet()) {
            UUID owner = entry.getKey();
            Map<UUID, NBTTagCompound> pending = pendingRuntimeTags(owner);
            pending.putAll(entry.getValue());
            CloudworksWarehouseDelta changes = runtimeWarehouseDeltas.get(owner);
            persistRuntimeTags(owner, clouds.get(owner).writeCore(), pending,
                    changes == null ? new CloudworksWarehouseDelta().write() : changes.write());
        }
        for (ProductionLineData line : lines.values())
            if (!runtimeUnmaterialized.contains(line.lineId()) && !savedRuntimeLines.contains(line.lineId())
                    && (!skipFailedOwners || !failedOwners.contains(line.owner()))) writeCheckpoint(line);
        for (CloudworksData cloud : clouds.values()) {
            if (skipFailedOwners && failedOwners.contains(cloud.owner())) continue;
            portJournal.compact(cloud.owner());
            orderedWrite(cloud.owner(), receivingFile(cloud.owner()), cloud.writeReceiving());
            orderedWrite(cloud.owner(), transferStoreFile(cloud.owner()), cloud.writeTransfer());
            orderedWrite(cloud.owner(), sendingFile(cloud.owner()), cloud.writeSending());
            orderedWrite(cloud.owner(), cloudFile(cloud.owner()), cloud.writeCore());
            for (CloudworksPortRecord port : ports.values())
                if (port.owner.equals(cloud.owner()))
                    orderedWrite(cloud.owner(), ownerDirectory(cloud.owner()).resolve("ports")
                            .resolve(port.id + ".dat"), port.write());
        }
    }

    private void requireLoaded() {
        if (root == null) throw error("unavailable");
    }

    /**
     * Resolves owner-scoped storage without allowing player and team UUIDs to
     * share a directory. Owner data is written only below the new split roots.
     */
    private Path ownerDirectory(UUID owner) {
        requireLoaded();
        boolean team = teamOwnerIds.contains(owner);
        try {
            if (!team && CloudworksTeamContext.registry().loaded()
                    && CloudworksTeamContext.registry().hasTeam(owner)) {
                teamOwnerIds.add(owner);
                team = true;
            }
        } catch (RuntimeException ignored) {
            // Bare unit-test environments may not load the team registry.
        }
        if (!team && Files.isDirectory(root.resolve("team").resolve(owner.toString()))) {
            teamOwnerIds.add(owner);
            team = true;
        }
        Path kindRoot = root.resolve(team ? "team" : "player");
        Path target = kindRoot.resolve(owner.toString()).toAbsolutePath().normalize();
        if (!target.startsWith(root.toAbsolutePath().normalize())) throw dataError(null);
        try {
            Files.createDirectories(kindRoot);
        } catch (IOException failure) {
            throw dataError(failure);
        }
        return target;
    }

    /**
     * Restores team classification for dissolved estates before any owner
     * path is resolved after a restart. The team registry can remove a team
     * while its recovery record remains active, so that index is authoritative.
     */
    private void loadTeamOwnerIds() {
        if (root == null || !Files.exists(teamRecoveryFile())) return;
        try {
            NBTTagList entries = read(teamRecoveryFile()).getTagList("entries", 10);
            for (int i = 0; i < entries.tagCount(); i++) {
                String value = entries.getCompoundTagAt(i).getString("owner");
                if (value.isEmpty()) continue;
                try {
                    teamOwnerIds.add(UUID.fromString(value));
                } catch (IllegalArgumentException ignored) {
                    Mekanism.logger.debug(
                            "Skipping invalid team recovery owner {}", value, ignored);
                }
            }
        } catch (RuntimeException failure) {
            throw dataError(failure);
        }
    }

    private Path cloudFile(UUID owner) {
        return ownerDirectory(owner).resolve("cloud.dat");
    }

    private Path receivingFile(UUID owner) {
        return ownerDirectory(owner).resolve("receiving.dat");
    }

    private Path transferStoreFile(UUID owner) {
        return ownerDirectory(owner).resolve("transfer.dat");
    }

    private Path sendingFile(UUID owner) {
        return ownerDirectory(owner).resolve("sending.dat");
    }

    private Path lineFile(UUID owner, UUID id) {
        return ownerDirectory(owner).resolve("production").resolve(id + ".dat");
    }

    private Path lineRuntimeFile(UUID owner, UUID id) {
        return ownerDirectory(owner).resolve("production").resolve(id + ".runtime.dat");
    }

    private Path pendingIntentFile(UUID owner) {
        return ownerDirectory(owner).resolve("transfer.pending.dat");
    }

    private Path terminalFile(UUID id) {
        return root.resolve("terminals").resolve(id + ".dat");
    }

    private Path machineCacheFile(UUID owner) {
        return ownerDirectory(owner).resolve("machine-cache.dat");
    }

    private Path specialNodeDirectory(UUID owner) {
        return ownerDirectory(owner).resolve("special_nodes");
    }

    private Path blueprintPendingFile(UUID owner) {
        return ownerDirectory(owner).resolve("blueprint-pending.dat");
    }

    private Path sourceDebtsFile(UUID owner) {
        return ownerDirectory(owner).resolve("source-debits.dat");
    }

    /**
     * Ledger files live per DEBIT PLAYER under a global directory, decoupled from cloud enablement.
     */
    private Path debitLedgerFile(UUID player) {
        return root.resolve("debits").resolve(player.toString() + ".dat");
    }

    private Path recycleFile(UUID owner) {
        return ownerDirectory(owner).resolve("recycle.dat");
    }

    private Path teamRecoveryFile() {
        return root.resolve("team-recovery.dat");
    }

    private Path teamRecoveryIntentFile(UUID owner) {
        return ownerDirectory(owner).resolve("team-recovery.pending.dat");
    }

    private static NBTTagCompound recoveryEntry(UUID owner, String teamId, short teamUid,
                                                UUID lastOwner, String status) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("owner", owner.toString());
        tag.setString("teamId", teamId == null ? "" : teamId);
        tag.setShort("teamUid", teamUid);
        if (lastOwner != null) tag.setString("lastOwner", lastOwner.toString());
        tag.setString("status", status);
        return tag;
    }

    private TeamRecoveryRecord findTeamRecovery(UUID owner) {
        synchronized (teamRecoveryLock) {
            if (root == null || !Files.exists(teamRecoveryFile())) return null;
            NBTTagList entries = read(teamRecoveryFile()).getTagList("entries", 10);
            for (int i = 0; i < entries.tagCount(); i++) {
                NBTTagCompound tag = entries.getCompoundTagAt(i);
                if (!owner.toString().equals(tag.getString("owner"))) continue;
                UUID last = null;
                if (tag.hasKey("lastOwner", 8) && !tag.getString("lastOwner").isEmpty())
                    last = UUID.fromString(tag.getString("lastOwner"));
                return new TeamRecoveryRecord(owner, tag.getString("teamId"),
                        tag.getShort("teamUid"), last, tag.getString("status"));
            }
            return null;
        }
    }

    private void removeTeamRecovery(UUID owner) {
        synchronized (teamRecoveryLock) {
            if (root == null || !Files.exists(teamRecoveryFile())) return;
            NBTTagCompound rootTag = read(teamRecoveryFile());
            NBTTagList source = rootTag.getTagList("entries", 10), next = new NBTTagList();
            for (int i = 0; i < source.tagCount(); i++)
                if (!owner.toString().equals(source.getCompoundTagAt(i).getString("owner")))
                    next.appendTag(source.getCompoundTagAt(i));
            rootTag.setTag("entries", next);
            write(teamRecoveryFile(), rootTag);
        }
    }

    private void updateTeamRecoveryStatus(UUID owner, String status) {
        synchronized (teamRecoveryLock) {
            if (root == null || !Files.exists(teamRecoveryFile())) throw error("team_recovery");
            NBTTagCompound rootTag = read(teamRecoveryFile());
            NBTTagList entries = rootTag.getTagList("entries", 10);
            boolean found = false;
            for (int i = 0; i < entries.tagCount(); i++) {
                NBTTagCompound entry = entries.getCompoundTagAt(i);
                if (owner.toString().equals(entry.getString("owner"))) {
                    entry.setString("status", status);
                    found = true;
                    break;
                }
            }
            if (!found) throw error("team_recovery");
            write(teamRecoveryFile(), rootTag);
        }
    }

    private void deleteTeamRecoveryIntent(UUID owner) {
        try {
            Files.deleteIfExists(teamRecoveryIntentFile(owner));
        } catch (IOException failure) {
            throw dataError(failure);
        }
    }

    private void retireRecoveredTeam(UUID owner) {
        for (ProductionLineData line : new ArrayList<>(lines.values())) {
            if (!owner.equals(line.owner())) continue;
            UUID id = line.lineId();
            lines.remove(id);
            guiLines.remove(id);
            runtimeLineSnapshots.remove(id);
            runtimeUnmaterialized.remove(id);
            checkpointGenerations.remove(id);
            if (parent != null) parent.lineDomains.remove(id, this);
        }
        for (CloudworksPortRecord port : new ArrayList<>(ports.values())) {
            if (!owner.equals(port.owner)) continue;
            ports.remove(port.id);
            portMounts.remove(port.id);
            dirtyPorts.remove(port.id);
        }
        activeEdits.values().removeIf(edit -> owner.equals(edit.owner));
        pendingBlueprints.remove(owner);
        terminalSessions.values().removeIf(session -> {
            if (!owner.equals(session.owner)) return false;
            session.closed = true;
            return true;
        });
        clouds.remove(owner);
        machineCaches.remove(owner);
        recycleBins.remove(owner);
        specialNodeStores.remove(owner);
        guiMachines.remove(owner);
        guiRecycle.remove(owner);
        runtimeDirtyLines.remove(owner);
        runtimeWarehouseDeltas.remove(owner);
        durableCloudBaselines.remove(owner);
        runtimeIngress.remove(owner);
        typeUnblockListeners.remove(owner);
        if (parent != null) parent.cloudViews.remove(owner);
    }

    private void deleteTeamRecoveryDirectory(UUID owner) {
        Path ownerDirectory = ownerDirectory(owner).toAbsolutePath().normalize();
        Path storageRoot = root.toAbsolutePath().normalize();
        if (!ownerDirectory.startsWith(storageRoot) || !Files.isDirectory(ownerDirectory)) return;
        try (Stream<Path> paths = Files.walk(ownerDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    throw dataError(failure);
                }
            });
        } catch (IOException failure) {
            throw dataError(failure);
        }
    }

    private static final class TeamRecoveryRecord {
        final UUID owner, lastOwner;
        final String teamId, status;
        final short teamUid;

        TeamRecoveryRecord(UUID owner, String teamId, short teamUid, UUID lastOwner, String status) {
            this.owner = owner;
            this.teamId = teamId;
            this.teamUid = teamUid;
            this.lastOwner = lastOwner;
            this.status = status;
        }
    }

    /**
     * Owner-level machine cache; missing files start empty (path is the identity).
     */
    public CloudworksMachineCache machineCache(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.machineCache(owner);
        }
        synchronized (this) {
            Objects.requireNonNull(owner);
            return machineCaches.computeIfAbsent(owner, ignored -> new CloudworksMachineCache());
        }
    }

    /**
     * Owner-level recycle bin; missing files start empty.
     */
    public CloudworksRecycleBin recycleBin(UUID owner) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            return target.recycleBin(owner);
        }
        synchronized (this) {
            Objects.requireNonNull(owner);
            return recycleBins.computeIfAbsent(owner, ignored -> new CloudworksRecycleBin());
        }
    }

    /**
     * Trusted reward entry. Validates and publishes a detached copy, never a physical inventory debit.
     */
    public void depositAssets(UUID owner, CloudworksDepositRequest request) {
        if (parent == null) {
            domain(owner).depositAssets(owner, request);
            return;
        }
        synchronized (this) {
            CloudworksData existing = loadIfExists(owner);
            if (existing == null || !existing.enabled()) throw error("disabled");
            existing = requireEnabled(owner);
            if (request.kind() == CloudworksDepositRequest.Kind.MACHINE) {
                ItemStack stack = request.item();
                if (!CloudworksRegistries.isRegistered(stack)) throw error("machine_unregistered");
                CloudworksMachineCache cache = CloudworksMachineCache.read(machineCache(owner).write());
                CloudworksMachineCache.Entry old = cache.findType(stack);
                Math.addExact(old == null ? 0 : old.count(), request.amount());
                cache.deposit(stack, request.amount());
                persistMachineCache(owner, cache);
                machineCaches.put(owner, cache);
                changed(owner);
                return;
            }
            ResourceLocation typeId = request.upgrade();
            if (request.kind() == CloudworksDepositRequest.Kind.UPGRADE_ITEM) {
                ItemStack item = request.item();
                typeId = CloudworksMachineModuleRegistry.typeForItem(item);
                if (typeId == null) {
                    Upgrade nativeType = CloudworksUpgradeItems.local().typeOf(item);
                    if (nativeType == null) throw error("upgrade");
                    typeId = nativeType == Upgrade.SPEED
                            ? MachineModuleType.SPEED_ID : MachineModuleType.ENERGY_ID;
                }
            }
            CloudworksData cloud = existing.copyForRuntime();
            if (MachineModuleType.SPEED_ID.equals(typeId)
                    || MachineModuleType.ENERGY_ID.equals(typeId)) {
                cloud.addUpgradePool(MachineModuleType.SPEED_ID.equals(typeId),
                        Math.toIntExact(request.amount()));
            } else {
                MachineModuleType type =
                        CloudworksMachineModuleRegistry.type(typeId);
                if (type == null || type.slot() != MachineModuleType.Slot.CUSTOM)
                    throw error("upgrade");
                cloud.customUpgradePool().add(typeId, request.amount());
            }
            publishTags(owner, cloud.write(), Collections.emptyList());
        }
    }

    /**
     * Performs only the cloud-side half of a trusted external transfer.
     */
    public CloudworksTransferResult transferAssets(UUID owner,
                                                                           CloudworksTransferRequest request) {
        if (parent == null) return domain(owner).transferAssets(owner, request);
        synchronized (this) {
            CloudworksData cloud = requireEnabled(owner).copyForRuntime();
            if (request.direction() == CloudworksTransferRequest.Direction.UPLOAD) {
                switch (request.kind()) {
                    case MACHINE: {
                        ItemStack stack = request.machine();
                        if (!CloudworksRegistries.isRegistered(stack)) throw error("machine_unregistered");
                        CloudworksMachineCache cache = CloudworksMachineCache.read(machineCache(owner).write());
                        cache.deposit(stack, request.amount());
                        persistMachineCache(owner, cache);
                        machineCaches.put(owner, cache);
                        changed(owner);
                        return CloudworksTransferResult.accepted(request.amount(),
                                Collections.emptyList());
                    }
                    case UPGRADE: {
                        addUpgradeTypeToCloud(cloud, request.upgrade(), request.amount());
                        publishTags(owner, cloud.write(), Collections.emptyList());
                        return CloudworksTransferResult.accepted(request.amount(),
                                Collections.emptyList());
                    }
                    case RESOURCE: {
                        CloudworksInventory inventory = transferInventory(cloud, request.warehouse());
                        long accepted = inventory.insert(request.resource(), request.amount(),
                                cloud.effectiveWarehouseItemCap());
                        if (accepted <= 0)
                            return transferRefused(CloudworksTransferResult.Status.CAPACITY,
                                    "cloudworks.error.capacity");
                        publishTags(owner, cloud.write(), Collections.emptyList());
                        return CloudworksTransferResult.accepted(accepted,
                                Collections.emptyList());
                    }
                    default:
                        throw error("deposit_item");
                }
            }
            switch (request.kind()) {
                case MACHINE: {
                    CloudworksMachineCache cache = CloudworksMachineCache.read(machineCache(owner).write());
                    int type = cache.typeIndex(request.machine());
                    if (type < 0)
                        return transferRefused(CloudworksTransferResult.Status.INSUFFICIENT,
                                "cloudworks.error.not_found");
                    NBTTagCompound target = CloudworksMachineCache.findVariant(cache.entries().get(type),
                            request.machine()) == null ? null
                            : CloudworksMachineCache.findVariant(cache.entries().get(type), request.machine()).tag();
                    if (target == null)
                        return transferRefused(CloudworksTransferResult.Status.INSUFFICIENT,
                                "cloudworks.error.not_found");
                    Map<NBTTagCompound, Long> claimed = deriveOccupation(owner, cache).getOrDefault(type, Collections.emptyMap());
                    long wanted = Math.min(request.amount(), MAX_WITHDRAW_PER_CALL);
                    List<ItemStack> result = cache.withdraw(type, wanted, variant ->
                            variant.tag().equals(target) ? (int) Math.min(Integer.MAX_VALUE,
                                    Math.max(0L, variant.count() - claimed.getOrDefault(target, 0L))) : 0);
                    if (result.isEmpty())
                        return transferRefused(CloudworksTransferResult.Status.INSUFFICIENT,
                                "cloudworks.error.not_found");
                    persistMachineCache(owner, cache);
                    machineCaches.put(owner, cache);
                    changed(owner);
                    return CloudworksTransferResult.accepted(
                            result.stream().mapToLong(ItemStack::getCount).sum(), result);
                }
                case UPGRADE: {
                    long taken = takeUpgradeTypeFromCloud(cloud, request.upgrade(), request.amount());
                    if (taken <= 0)
                        return transferRefused(CloudworksTransferResult.Status.INSUFFICIENT,
                                "cloudworks.error.not_found");
                    publishTags(owner, cloud.write(), Collections.emptyList());
                    return CloudworksTransferResult.accepted(taken, Collections.emptyList());
                }
                case RESOURCE: {
                    CloudworksInventory inventory = transferInventory(cloud, request.warehouse());
                    long taken = inventory.extract(request.resource(), request.amount());
                    if (taken <= 0)
                        return transferRefused(CloudworksTransferResult.Status.INSUFFICIENT,
                                "cloudworks.error.not_found");
                    publishTags(owner, cloud.write(), Collections.emptyList());
                    return CloudworksTransferResult.accepted(taken, Collections.emptyList());
                }
                default:
                    throw error("deposit_item");
            }
        }
    }

    private static CloudworksTransferResult transferRefused(
            CloudworksTransferResult.Status status, String error) {
        return CloudworksTransferResult.refused(status, error);
    }

    private static CloudworksInventory transferInventory(CloudworksData cloud,
                                                         CloudworksTransferRequest.Warehouse warehouse) {
        switch (warehouse) {
            case RECEIVING:
                return cloud.receiving();
            case TRANSFER:
                return cloud.transfer();
            case SENDING:
                return cloud.sending();
            default:
                throw new IllegalArgumentException("Invalid warehouse");
        }
    }

    private static void addUpgradeTypeToCloud(CloudworksData cloud, ResourceLocation id, long amount) {
        if (MachineModuleType.SPEED_ID.equals(id) || MachineModuleType.ENERGY_ID.equals(id)) {
            cloud.addUpgradePool(MachineModuleType.SPEED_ID.equals(id), Math.toIntExact(amount));
            return;
        }
        MachineModuleType type = CloudworksMachineModuleRegistry.type(id);
        if (type == null || type.slot() != MachineModuleType.Slot.CUSTOM) throw error("upgrade");
        cloud.customUpgradePool().add(id, amount);
    }

    private static long takeUpgradeTypeFromCloud(CloudworksData cloud, ResourceLocation id, long amount) {
        if (MachineModuleType.SPEED_ID.equals(id) || MachineModuleType.ENERGY_ID.equals(id)) {
            boolean speed = MachineModuleType.SPEED_ID.equals(id);
            int taken = Math.min(cloud.upgradePool(speed), Math.toIntExact(Math.min(amount, Integer.MAX_VALUE)));
            if (taken > 0) cloud.addUpgradePool(speed, -taken);
            return taken;
        }
        MachineModuleType type = CloudworksMachineModuleRegistry.type(id);
        if (type == null || type.slot() != MachineModuleType.Slot.CUSTOM) throw error("upgrade");
        long taken = Math.min(amount, cloud.customUpgradePool().count(id));
        if (taken > 0) cloud.customUpgradePool().add(id, -taken);
        return taken;
    }

    /**
     * Deposits machines into the cache; single-file ordered write.
     */
    public void depositMachines(UUID owner, ItemStack stack) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(owner);
            target.depositMachines(owner, stack);
            return;
        }
        synchronized (this) {
            requireEnabled(owner);
            CloudworksMachineCache cache = machineCache(owner);
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
            CloudworksMachineCache cache = machineCache(owner);
            if (typeIndex < 0 || typeIndex >= cache.typeCount()) throw error("not_found");
            List<CloudworksMachineCache.Entry> entries = cache.entries();
            List<CloudworksMachineCache.Variant> variants = entries.get(typeIndex).variants();
            if (variantIndex < 0 || variantIndex >= variants.size()) throw error("not_found");
            NBTTagCompound targetTag = variants.get(variantIndex).tag();
            long wanted = Math.min(Math.max(0L, amount), MAX_WITHDRAW_PER_CALL);
            if (wanted == 0) return Collections.emptyList();
            Map<Integer, Map<NBTTagCompound, Long>> occupied = deriveOccupation(owner, cache);
            Map<NBTTagCompound, Long> byTag = occupied.getOrDefault(typeIndex, Collections.emptyMap());
            List<ItemStack> withdrawn = cache.withdraw(typeIndex, wanted, variant -> {
                if (!Objects.equals(variant.tag(), targetTag)) return 0;
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
            CloudworksMachineCache cache = machineCache(owner);
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
            CloudworksMachineCache cache = machineCache(owner);
            Map<Integer, Map<NBTTagCompound, Long>> occupied = deriveOccupation(owner, cache);
            List<Map<String, Object>> view = new ArrayList<>();
            List<CloudworksMachineCache.Entry> entries = cache.entries();
            Map<MachineKey, Map<UUID, Long>> lineClaims = new HashMap<>();
            for (ProductionLineData line : lines(owner)) {
                for (MachineNodeData node : line.machines()) {
                    MachineKey key = CloudworksRegistries.identityKey(node.machine());
                    lineClaims.computeIfAbsent(key, ignored -> new HashMap<>())
                            .merge(line.lineId(), node.machineCount(), Math::addExact);
                }
            }
            for (int index = 0; index < entries.size(); index++) {
                CloudworksMachineCache.Entry entry = entries.get(index);
                MachineKey entryKey =
                        CloudworksRegistries.identityKey(entry.template());
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
                    long lineClaim = lineClaims.getOrDefault(entryKey, Collections.emptyMap())
                            .getOrDefault(line.lineId(), 0L);
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
                                                                     CloudworksMachineCache cache) {
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
                Map<MachineKey, Long> present = new HashMap<>();
                for (MachineNodeData node : projection.machines())
                    present.merge(CloudworksRegistries.identityKey(node.machine()), node.machineCount(), Math::addExact);
                Map<MachineKey, Long> original = new HashMap<>();
                for (MachineNodeData node : baseline.machines())
                    original.merge(CloudworksRegistries.identityKey(node.machine()), node.machineCount(), Math::addExact);
                for (Map.Entry<MachineKey, Long> type : original.entrySet()) {
                    long extra = type.getValue() - present.getOrDefault(type.getKey(), 0L);
                    if (extra <= 0) continue;
                    MachineNodeData node = new MachineNodeData(UUID.randomUUID(), type.getKey().displayStack(), 0, 0);
                    node.setMachineCount(extra);
                    node.setPooledClaim(true);
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
        CloudworksMachineCache cache = machineCache(owner);
        Map<Integer, Map<NBTTagCompound, Long>> occupied = deriveOccupation(owner, cache);
        boolean changed = false;
        for (ProductionLineData line : lines(owner)) {
            for (MachineNodeData node : line.machines()) {
                if (node.pooledClaim()) continue;
                int index = cache.typeIndex(node.machine());
                CloudworksMachineCache.Entry entry = cache.findType(node.machine());
                CloudworksMachineCache.Variant variant = null;
                if (entry != null) variant = CloudworksMachineCache.findVariant(
                        entry, node.machine());
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
        CloudworksMachineCache cache;
        if (Files.exists(file)) {
            NBTTagCompound tag = read(file);
            cache = CloudworksMachineCache.read(tag);
        } else cache = new CloudworksMachineCache();
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
        CloudworksRecycleBin bin;
        if (Files.exists(file)) {
            NBTTagCompound tag = read(file);
            bin = CloudworksRecycleBin.read(tag);
        } else bin = new CloudworksRecycleBin();
        recycleBins.put(owner, bin);
    }

    private void persistMachineCache(UUID owner, CloudworksMachineCache cache) {
        // Upload intents project machine-cache.dat too; this ordered write can
        // never be clobbered by a queued batch's (older) projection.
        NBTTagCompound tag = cache.write();
        orderedWrite(owner, machineCacheFile(owner), tag);
        guiMachines.put(owner, tag);
    }

    private void persistRecycleBin(UUID owner, CloudworksRecycleBin bin) {
        NBTTagCompound tag = bin.write();
        orderedWrite(owner, recycleFile(owner), tag);
        guiRecycle.put(owner, tag);
    }

    /**
     * Owners with pending recycle overflow notices; drained by the server tick service.
     */
    public synchronized Set<UUID> drainRecycleOverflowNotices() {
        if (pendingRecycleOverflow.isEmpty()) return Collections.emptySet();
        Set<UUID> drained = new LinkedHashSet<>(pendingRecycleOverflow);
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
            CloudworksRecycleBin bin = recycleBin(owner);
            if (bin.typeCount() == 0) return 0L;
            CloudworksData previousCloud = cloud.copyForRuntime();
            NBTTagCompound previousBin = bin.write();
            try {
                Map<QIOResourceDescriptor, Long> drained = bin.drain();
                long total = 0;
                CloudworksInventory destination = cloud.recycleToTransfer() ? cloud.transfer() : cloud.sending();
                for (Map.Entry<QIOResourceDescriptor, Long> entry : drained.entrySet()) {
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
                recycleBins.put(owner, CloudworksRecycleBin.read(previousBin));
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
            CloudworksRecycleBin bin = recycleBin(owner);
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

    /**
     * A cloud without running lines still owns warehouse energy routing.
     */
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
                NBTTagCompound tag = publishedLineTag(id);
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
                    CloudworksTransactionSeeds.next(), cloud, baselines, true, specialNodeStore(owner).load());
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
                                                CloudworksRuntimeCheckpoint checkpoint) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(origin.owner);
            return target.commitRuntimeCheckpoint(origin, expectedRevision, checkpoint);
        }
        synchronized (this) {
            return commitRuntimeCheckpoint(origin, expectedRevision, checkpoint, true);
        }
    }

    public CommitStatus commitRuntimeCheckpoint(VirtualSnapshot origin, long expectedRevision,
                                                CloudworksRuntimeCheckpoint checkpoint, boolean durable) {
        if (parent == null) {
            CloudworksPlayerDataManager target = domain(origin.owner);
            return target.commitRuntimeCheckpoint(origin, expectedRevision, checkpoint, durable);
        }
        synchronized (this) {
            UUID owner = origin.owner;
            if (checkpoint.accepted() || !runtimeCurrent(origin, expectedRevision)) return CommitStatus.CONFLICT;
            if (!owner.equals(checkpoint.cloud().owner()) || !clouds.containsKey(owner))
                return CommitStatus.CONFLICT;
            List<CloudworksExternalEvent> ingress =
                    runtimeIngressAfter(owner, checkpoint.appliedSequence());
            if (ingress == null) return CommitStatus.CONFLICT;
            checkpoint.mergeIngress(ingress, symbols(owner));
            List<UUID> updated = checkpoint.lineIds();
            List<mekanism.cloudworks.common.runtime.CloudworksRuntimeLineSnapshot> snapshots = checkpoint.lineSnapshots();
            Map<UUID, java.util.function.Supplier<NBTTagCompound>> next = new LinkedHashMap<>();
            for (int index = 0; index < updated.size(); index++) {
                UUID id = updated.get(index);
                if (!lines.containsKey(id) || !clouds.get(owner).lineIds().contains(id)
                        || !owner.equals(snapshots.get(index).owner())
                        || !id.equals(snapshots.get(index).lineId()))
                    return CommitStatus.CONFLICT;
                if (durable) next.put(id, snapshots.get(index)::write);
            }
            CloudworksWarehouseDelta changes = new CloudworksWarehouseDelta();
            CloudworksWarehouseDelta pendingChanges = runtimeWarehouseDeltas.get(owner);
            if (pendingChanges != null) changes.merge(pendingChanges);
            changes.merge(checkpoint.changes());
            if (durable) {
                Map<UUID, java.util.function.Supplier<NBTTagCompound>> all = pendingRuntimeValues(owner);
                all.putAll(next);
                persistRuntimeValues(owner, checkpoint.coreTag(), all, changes.write());
            }
            if (!checkpoint.acceptOnce()) return CommitStatus.CONFLICT;
            clouds.put(owner, checkpoint.cloud());
            for (int index = 0; index < updated.size(); index++) {
                UUID id = updated.get(index);
                runtimeLineSnapshots.put(id, snapshots.get(index));
                guiLines.remove(id);
                runtimeUnmaterialized.add(id);
            }
            if (!durable) {
                runtimeDirtyLines.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).addAll(updated);
                runtimeWarehouseDeltas.put(owner, changes);
            }
            changedFromRuntime(owner);
            checkpoint.publishModuleEvents();
            CloudworksAPI.emit(new CloudworksEvent(owner, null,
                    CloudworksEvent.Kind.RUNTIME_COMMITTED, revisions.get(owner), 1, ""));
            return CommitStatus.COMMITTED;
        }
    }

    private Map<UUID, NBTTagCompound> pendingRuntimeTags(UUID owner) {
        Map<UUID, NBTTagCompound> tags = new LinkedHashMap<>();
        Set<UUID> ids = runtimeDirtyLines.get(owner);
        CloudworksData cloud = clouds.get(owner);
        if (ids != null && cloud != null)
            for (UUID id : ids)
                if (cloud.lineIds().contains(id)) {
                    NBTTagCompound tag = publishedLineTag(id);
                    if (tag != null) tags.put(id, tag);
                }
        return tags;
    }

    /** Captures only immutable publications, never the manager or its live maps. */
    private Map<UUID, java.util.function.Supplier<NBTTagCompound>> pendingRuntimeValues(UUID owner) {
        Map<UUID, java.util.function.Supplier<NBTTagCompound>> values = new LinkedHashMap<>();
        Set<UUID> ids = runtimeDirtyLines.get(owner);
        CloudworksData cloud = clouds.get(owner);
        if (ids != null && cloud != null) for (UUID id : ids) {
            if (!cloud.lineIds().contains(id)) continue;
            mekanism.cloudworks.common.runtime.CloudworksRuntimeLineSnapshot snapshot = runtimeLineSnapshots.get(id);
            if (snapshot != null) values.put(id, snapshot::write);
            else {
                NBTTagCompound tag = guiLines.get(id);
                if (tag != null) values.put(id, () -> tag);
            }
        }
        return values;
    }

    /**
     * Independent save cadence. Only frozen payload references and line metadata are visited here.
     */
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
            persistRuntimeValues(owner, cloud.writeCore(), pendingRuntimeValues(owner),
                    changes == null ? new CloudworksWarehouseDelta().write() : changes.write());
        }
    }

    private void persistRuntimeTags(UUID owner, NBTTagCompound cloud, Map<UUID, NBTTagCompound> tags) {
        persistRuntimeTags(owner, cloud, tags, null);
    }

    private void persistRuntimeTags(UUID owner, NBTTagCompound cloud, Map<UUID, NBTTagCompound> tags,
                                    NBTTagCompound warehouseDelta) {
        Map<UUID, java.util.function.Supplier<NBTTagCompound>> values = new LinkedHashMap<>();
        tags.forEach((id, tag) -> values.put(id, () -> tag));
        persistRuntimeValues(owner, cloud, values, warehouseDelta);
    }

    private void persistRuntimeValues(UUID owner, NBTTagCompound cloud,
            Map<UUID, java.util.function.Supplier<NBTTagCompound>> values, NBTTagCompound warehouseDelta) {
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
        Map<UUID, Long> generations = new LinkedHashMap<>();
        values.keySet().forEach(id -> generations.put(id, Math.addExact(checkpointGenerations.getOrDefault(id, 0L), 1L)));
        Map<UUID, java.util.function.Supplier<NBTTagCompound>> frozen = new LinkedHashMap<>(values);
        java.util.function.Supplier<NBTTagCompound> materialize = () -> {
            NBTTagList entries = new NBTTagList();
            for (Map.Entry<UUID, java.util.function.Supplier<NBTTagCompound>> line : frozen.entrySet()) {
                NBTTagCompound source = line.getValue().get();
                NBTTagCompound tag = new NBTTagCompound();
                for (String key : source.getKeySet()) tag.setTag(key, source.getTag(key));
                tag.setLong("generation", generations.get(line.getKey()));
                entries.appendTag(tag);
            }
            intent.setTag("lines", entries);
            return intent;
        };
        if (persistence == null) {
            materialize.get();
            ioSink.beginBatch();
            try {
                ioSink.writeIntentFile(owner, intent);
                ioSink.applyIntentRelaxed(owner, intent, CloudworksPersistenceQueue.AbortPoint.NONE);
                ioSink.commitBatch();
            } finally {
                ioSink.endBatch();
            }
        } else persistence.submitDeferredIntent(owner, materialize);
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

    /**
     * Constant-size credential check, also used for unchanged Runtime ticks.
     */
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
                    CloudworksUpgradeEffects.settings()))
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
        publish0(owner, cloudTag, lineTags, recycleTag, machineCacheTag, transferTag, terminalTag,
                controlChange, checkpointOnly, blueprintPending, deletedLineIds, null);
    }

    private void publish0(UUID owner, NBTTagCompound cloudTag, List<NBTTagCompound> lineTags,
                          NBTTagCompound recycleTag, NBTTagCompound machineCacheTag,
                          NBTTagCompound transferTag, NBTTagCompound terminalTag,
                          boolean controlChange, boolean checkpointOnly, NBTTagCompound blueprintPending,
                          Collection<UUID> deletedLineIds, CloudworksSpecialNodeStore.Snapshot specialNodes) {
        // Per-owner P1 gate: a poisoned fault domain never submits new durable
        // work; its later batches would only be dropped by the IO worker.
        if (recoveryRequired || failedOwners.contains(owner)) throw dataError(recoveryCause(owner));
        for (NBTTagCompound tag : lineTags) ProductionLineData.read(tag.copy());
        List<CloudworksModuleEvents.Event> lifecycleEvents = new ArrayList<>();
        if (controlChange && !checkpointOnly) {
            CloudworksGenerationContext clock =
                    new CloudworksGenerationContext(0,
                            parent == null ? globalSimulatedTick : parent.globalSimulatedTick, 0, false);
            for (NBTTagCompound tag : lineTags) {
                UUID id = UUID.fromString(tag.getString("lineId"));
                NBTTagCompound previous = publishedLineTag(id);
                lifecycleEvents.addAll(CloudworksModuleEvents.candidateChanges(
                        previous == null ? null : ProductionLineData.read(previous.copy()),
                        ProductionLineData.read(tag.copy()), clock));
            }
            for (UUID id : deletedLineIds) {
                NBTTagCompound previous = publishedLineTag(id);
                if (previous != null) lifecycleEvents.addAll(CloudworksModuleEvents.candidateChanges(
                        ProductionLineData.read(previous.copy()), null, clock));
            }
        }
        if (recycleTag != null) CloudworksRecycleBin.read(recycleTag.copy());
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
        if (specialNodes != null) {
            intent.setTag("specialNodeDefinitions", specialNodes.nodesTag());
            intent.setTag("infiniteResources", specialNodes.resourcesTag());
        }
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
                deleted.appendTag(new NBTTagString(id.toString()));
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
        CloudworksModuleEvents.enqueue(lifecycleEvents);
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
            runtimeLineSnapshots.remove(id);
            checkpointGenerations.put(id, entry.getLong("generation"));
        }
        if (machineCacheTag != null) machineCaches.put(owner,
                CloudworksMachineCache.read(machineCacheTag.copy()));
        if (machineCacheTag != null) guiMachines.put(owner, machineCacheTag);
        if (recycleTag != null) guiRecycle.put(owner, recycleTag);
        if (specialNodes != null) specialNodeStore(owner).publish(specialNodes);
        if (blueprintPending != null) {
            CloudworksPendingBlueprint pendingBlueprint = CloudworksPendingBlueprint.read(blueprintPending);
            if (pendingBlueprint == null) pendingBlueprints.remove(owner);
            else pendingBlueprints.put(owner, pendingBlueprint);
        }
        if (checkpointOnly) return;
        if (controlChange) changed(owner);
        else changedFromRuntime(owner);
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
            Mekanism.logger.error("Cloudworks persistence replay failed for owner {}", owner, failure);
            throw failure;
        } finally {
            if (ownBatch) ioSink.endBatch();
        }
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
        if (strict) {
            IoReads cache = IO_READS.get();
            if (cache != null) {
                cache.diskOnly = true;
                cache.shared.clear();
                cache.tags.clear();
                cache.fingerprints.clear();
                cache.fileDigests.clear();
                cache.projections.clear();
            }
        }
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
            IO_READS.get().projections.put(intent, new ProjectionPlan(cloud, projectionPaths(owner, intent)));
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
        if (intent.hasKey("infiniteResources", 10) && !intent.hasKey("specialNodeDefinitions", 10))
            throw dataError(null);
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
        for (String key : new String[]{"specialNodeDefinitions", "infiniteResources"}) {
            if (intent.hasKey(key, 10)) {
                Path file = projectionPaths(owner, intent).get(key);
                writeIntentProjection(intent, key, file, nextProjection(owner, intent, cloud, key), null, strict);
            }
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
            try {
                id = UUID.fromString(value);
            } catch (IllegalArgumentException invalid) {
                throw dataError(invalid);
            }
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
        CloudworksRecycleBin.read(next.copy());
        NBTTagCompound disk = Files.exists(recycleFile(owner))
                ? read(recycleFile(owner)) : new CloudworksRecycleBin().write();
        return matchesProjection(disk, next,
                intent.hasKey("previousRecycle", 10) ? intent.getCompoundTag("previousRecycle") : null);
    }

    /**
     * Strict replay validation of the upload machine-cache projection.
     */
    private boolean matchesMachineCache(UUID owner, NBTTagCompound intent) {
        NBTTagCompound next = intent.getCompoundTag("machineCache");
        CloudworksMachineCache.read(next.copy());
        NBTTagCompound disk = Files.exists(machineCacheFile(owner))
                ? read(machineCacheFile(owner))
                : new CloudworksMachineCache().write();
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

    /**
     * Loads one warehouse file when present; missing files keep the fresh defaults.
     */
    private void applyWarehouse(Path file, Consumer<NBTTagCompound> applicator) {
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

    /**
     * Clears only a stopped node's input/output cache and preserves the node.
     */
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
                for (CloudworksLineEditSession edit : activeEdits.values())
                    if (edit.line.equals(id)) {
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
        runtimeLineSnapshots.remove(line.lineId());
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
        private final Map<UUID, LineBaseline> lineBaselines = new HashMap<>();
        private final CloudworksSpecialNodeStore.Snapshot specialNodes;
        private final AtomicBoolean consumed =
                new AtomicBoolean();

        private VirtualSnapshot(UUID originManager, long worldEpoch, UUID owner, long revision,
                                long sessionGeneration, long registryVersion, long layoutFingerprint,
                                int recipeVersion, UUID transaction,
                                long symbolGeneration, long transactionSeed, CloudworksData cloud, Map<UUID, NBTTagCompound> lines,
                                boolean frozenPublication, CloudworksSpecialNodeStore.Snapshot specialNodes) {
            this.originManager = originManager;
            this.worldEpoch = worldEpoch;
            this.owner = owner;
            this.revision = revision;
            this.sessionGeneration = sessionGeneration;
            this.registryVersion = registryVersion;
            this.layoutFingerprint = layoutFingerprint;
            this.recipeVersion = recipeVersion;
            this.upgradeSettings = CloudworksUpgradeEffects.settings();
            this.transaction = transaction;
            this.symbolGeneration = symbolGeneration;
            this.transactionSeed = transactionSeed;
            this.cloud = cloud.copyForRuntime();
            this.specialNodes = specialNodes;
            this.lines = new LinkedHashMap<>();
            lines.forEach((id, tag) -> {
                NBTTagCompound frozen = frozenPublication ? tag : tag.copy();
                this.lines.put(id, frozen);
                lineBaselines.put(id, new LineBaseline(frozen));
            });
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

        public CloudworksSpecialNodeStore.Snapshot specialNodes() {
            return specialNodes;
        }

        public List<ProductionLineData> lines() {
            DEBUG_RUNTIME_LINE_DECODES.incrementAndGet();
            List<ProductionLineData> copies = new ArrayList<>(lines.size());
            for (NBTTagCompound tag : lines.values()) copies.add(ProductionLineData.read(tag.copy()));
            return copies;
        }

        /** Cold configuration baseline; never returns the frozen publication itself. */
        public NBTTagCompound copyLineTag(UUID id) {
            NBTTagCompound tag = lines.get(id);
            if (tag == null) throw new IllegalArgumentException("Unknown Runtime baseline line");
            return tag.copy();
        }

        public LineBaseline lineBaseline(UUID id) {
            LineBaseline baseline = lineBaselines.get(id);
            if (baseline == null) throw new IllegalArgumentException("Unknown Runtime baseline line");
            return baseline;
        }

        /** Small overview without copying any runtime inventory or execution tree. */
        public static final class LineBaseline {
            private final NBTTagCompound tag;
            private LineBaseline(NBTTagCompound tag) { this.tag = staticLineBaseline(tag); }

            private static NBTTagCompound staticLineBaseline(NBTTagCompound source) {
                NBTTagCompound baseline = new NBTTagCompound();
                for (String key : source.getKeySet())
                    if (!key.equals("machines") && !key.equals("channels") && !key.equals("links")
                            && !key.equals("auxiliaries") && !key.equals("energy")
                            && !key.equals("maxEnergy") && !key.equals("generation"))
                        baseline.setTag(key, source.getTag(key));
                baseline.setTag("machines", filtered(source.getTagList("machines", 10), Arrays.asList(
                        "active", "progress", "totalTicks", "state", "outputSequence", "processCredits",
                        "processingBatches", "executionBranches", "generationRemainders", "moduleCostRemainders",
                        "thermalState", "inputs", "outputs", "templates", "operation")));
                baseline.setTag("channels", filtered(source.getTagList("channels", 10), Collections.singletonList("buffer")));
                baseline.setTag("links", filtered(source.getTagList("links", 10), Collections.singletonList("ratioRemainder")));
                baseline.setTag("auxiliaries", filtered(source.getTagList("auxiliaries", 10),
                        Arrays.asList("input", "costRemainders")));
                return baseline;
            }

            private static NBTTagList filtered(NBTTagList source, List<String> removed) {
                NBTTagList result = new NBTTagList();
                for (int index = 0; index < source.tagCount(); index++) {
                    NBTTagCompound value = source.getCompoundTagAt(index), retained = new NBTTagCompound();
                    for (String key : value.getKeySet())
                        if (!removed.contains(key)) retained.setTag(key, value.getTag(key));
                    result.appendTag(retained);
                }
                return result;
            }

            public NBTTagCompound copyTag() { return tag.copy(); }

            public NBTTagCompound copyOverview() {
            NBTTagCompound source = tag;
            if (source == null) throw new IllegalArgumentException("Unknown Runtime baseline line");
            NBTTagCompound header = new NBTTagCompound();
            for (String key : source.getKeySet())
                if (!key.equals("machines") && !key.equals("channels") && !key.equals("links") && !key.equals("auxiliaries"))
                    header.setTag(key, source.getTag(key).copy());
            NBTTagList nodes = source.getTagList("machines", 10), icon = new NBTTagList();
            if (nodes.tagCount() > 0) {
                NBTTagCompound first = new NBTTagCompound();
                first.setTag("machine", nodes.getCompoundTagAt(0).getCompoundTag("machine").copy());
                icon.appendTag(first);
            }
            header.setTag("machines", icon);
            header.setInteger("runtimeMachineCount", nodes.tagCount());
            return header;
            }

            public NBTTagList copyChannels() { return tag.getTagList("channels", 10).copy(); }
        }

        public void visitNodeTags(Consumer<NBTTagCompound> visitor) {
            for (NBTTagCompound line : lines.values()) {
                NBTTagList nodes = line.getTagList("machines", 10);
                for (int index = 0; index < nodes.tagCount(); index++) visitor.accept(nodes.getCompoundTagAt(index));
            }
        }

        public void visitDescriptorTags(Consumer<NBTTagCompound> visitor) {
            for (NBTTagCompound line : lines.values()) visitDescriptorTree(line, visitor);
        }

        private static void visitDescriptorTree(NBTBase value,
                                                Consumer<NBTTagCompound> visitor) {
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
            Predicate<NBTTagCompound> needsFuel = CloudworksNativeContracts.thermalFuelMatcher();
            visitNodeTags(tag -> {
                if (heat[0]) return;
                NBTTagCompound machine = tag.getCompoundTag("machine");
                heat[0] = needsFuel.test(machine);
                if (!heat[0] && tag.hasKey("customUpgrades", 10))
                    heat[0] = CloudworksNativeContracts.moduleHeatFuel(tag);
            });
            return heat[0];
        }
    }
}
