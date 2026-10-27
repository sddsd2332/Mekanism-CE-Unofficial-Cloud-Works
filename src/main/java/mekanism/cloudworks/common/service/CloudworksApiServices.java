package mekanism.cloudworks.common.service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import mekanism.cloudworks.api.*;
import mekanism.cloudworks.api.card.*;
import mekanism.cloudworks.api.environment.*;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.api.node.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.cloudworks.api.transfer.*;
import mekanism.cloudworks.api.work.*;
import mekanism.cloudworks.common.card.CloudworksCardEffect;
import mekanism.cloudworks.common.card.CloudworksCardEffects;
import mekanism.cloudworks.common.data.*;
import mekanism.cloudworks.common.team.CloudworksTeamContext;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import mekanism.cloudworks.common.util.CloudworksMessages;

/** Server implementation of the public query/event/card API. */
public final class CloudworksApiServices implements CloudworksAPI.Services {
    private final CloudworksPlayerDataManager manager;
    private final Executor executor;
    private final long worldEpoch;
    private volatile boolean closed;
    private final Map<UUID, List<Consumer<List<CloudworksEvent>>>> listeners = new HashMap<>();
    private final Map<ResourceLocation, CloudworksCardType> cards = new LinkedHashMap<>();
    private final Map<UUID, List<CloudworksEvent>> pendingEvents = new LinkedHashMap<>();

    public CloudworksApiServices(CloudworksPlayerDataManager manager) {
        this(manager, Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "Cloudworks-API");
            thread.setDaemon(true);
            return thread;
        }));
    }

    CloudworksApiServices(CloudworksPlayerDataManager manager, Executor executor) {
        this.manager = Objects.requireNonNull(manager);
        this.executor = Objects.requireNonNull(executor);
        worldEpoch = manager.worldEpoch();
    }

    public void registerCard(CloudworksCardType card) {
        if (cards.putIfAbsent(card.id, card) != null)
            throw new IllegalArgumentException("Duplicate API card: " + card.id);
    }

    @Override
    public CompletionStage<CloudworksSnapshot> query(UUID actor, UUID owner, CloudworksQuery query) {
        if (!actor.equals(owner) && !CloudworksTeamContext.canAccess(actor, owner))
            throw new SecurityException("CloudWorks query denied");
        return CompletableFuture.supplyAsync(() -> snapshot(owner, query), executor);
    }

    @Override
    public CompletionStage<CloudworksDepositResult> deposit(UUID actor, UUID owner, CloudworksDepositRequest request) {
        CompletableFuture<CloudworksDepositResult> result = new CompletableFuture<>();
        if (closed || !manager.acceptsApiRequests(worldEpoch)) return CompletableFuture.completedFuture(unavailable());
        try {
            executor.execute(() -> {
                CloudworksDepositResult settled;
                try {
                    if (closed || !manager.acceptsApiRequests(worldEpoch)) settled = unavailable();
                    else if (!actor.equals(owner) && !CloudworksTeamContext.canAccess(actor, owner))
                        settled = CloudworksDepositResult.refused(CloudworksDepositResult.Status.DENIED, "cloudworks.error.permission");
                    else synchronized (manager.ownerLock(owner)) {
                        if (closed || !manager.acceptsApiRequests(worldEpoch)) settled = unavailable();
                        else if (!actor.equals(owner) && !CloudworksTeamContext.canAccess(actor, owner))
                            settled = CloudworksDepositResult.refused(CloudworksDepositResult.Status.DENIED, "cloudworks.error.permission");
                        else {
                            manager.depositAssets(owner, request);
                            settled = CloudworksDepositResult.accepted(request.amount());
                        }
                    }
                } catch (ArithmeticException overflow) {
                    settled = CloudworksDepositResult.refused(CloudworksDepositResult.Status.INVALID_AMOUNT, "cloudworks.error.deposit_amount");
                } catch (IllegalArgumentException invalid) {
                    settled = CloudworksDepositResult.refused(CloudworksDepositResult.Status.INVALID_ITEM,
                            CloudworksMessages.errorKey(invalid));
                } catch (RuntimeException failure) {
                    String error = CloudworksMessages.errorKey(failure);
                    CloudworksDepositResult.Status status = "cloudworks.error.disabled".equals(error)
                            ? CloudworksDepositResult.Status.DISABLED
                            : "cloudworks.error.machine_unregistered".equals(error) || "cloudworks.error.upgrade".equals(error)
                            ? CloudworksDepositResult.Status.INVALID_ITEM : CloudworksDepositResult.Status.STORAGE_ERROR;
                    settled = CloudworksDepositResult.refused(status, error);
                }
                // User continuations must not run under an owner lock.
                result.complete(settled);
            });
        } catch (RejectedExecutionException stopped) {
            result.complete(unavailable());
        }
        return result;
    }

    @Override
    public CompletionStage<CloudworksTransferResult> transfer(UUID actor, UUID owner, CloudworksTransferRequest request) {
        CompletableFuture<CloudworksTransferResult> result = new CompletableFuture<>();
        if (closed || !manager.acceptsApiRequests(worldEpoch)) return CompletableFuture.completedFuture(
                CloudworksTransferResult.refused(CloudworksTransferResult.Status.UNAVAILABLE, "cloudworks.error.storage"));
        try {
            executor.execute(() -> {
                CloudworksTransferResult settled;
                try {
                    if (closed || !manager.acceptsApiRequests(worldEpoch)) settled = unavailableTransfer();
                    else if (!actor.equals(owner) && !CloudworksTeamContext.canAccess(actor, owner))
                        settled = CloudworksTransferResult.refused(CloudworksTransferResult.Status.DENIED, "cloudworks.error.permission");
                    else synchronized (manager.ownerLock(owner)) {
                        if (closed || !manager.acceptsApiRequests(worldEpoch)) settled = unavailableTransfer();
                        else settled = manager.transferAssets(owner, request);
                    }
                } catch (ArithmeticException overflow) {
                    settled = CloudworksTransferResult.refused(CloudworksTransferResult.Status.INVALID_AMOUNT,
                            "cloudworks.error.deposit_amount");
                } catch (IllegalArgumentException invalid) {
                    settled = CloudworksTransferResult.refused(CloudworksTransferResult.Status.INVALID_ITEM,
                            CloudworksMessages.errorKey(invalid));
                } catch (RuntimeException failure) {
                    String error = CloudworksMessages.errorKey(failure);
                    CloudworksTransferResult.Status status = "cloudworks.error.disabled".equals(error)
                            ? CloudworksTransferResult.Status.DISABLED
                            : error.contains("not_found") ? CloudworksTransferResult.Status.INSUFFICIENT
                            : CloudworksTransferResult.Status.STORAGE_ERROR;
                    settled = CloudworksTransferResult.refused(status, error);
                }
                result.complete(settled);
            });
        } catch (RejectedExecutionException stopped) {
            result.complete(unavailableTransfer());
        }
        return result;
    }

    private static CloudworksTransferResult unavailableTransfer() {
        return CloudworksTransferResult.refused(CloudworksTransferResult.Status.UNAVAILABLE, "cloudworks.error.storage");
    }

    private static CloudworksDepositResult unavailable() {
        return CloudworksDepositResult.refused(CloudworksDepositResult.Status.UNAVAILABLE, "cloudworks.error.storage");
    }

    @Override
    public AutoCloseable subscribe(UUID actor, UUID owner, Consumer<List<CloudworksEvent>> listener) {
        if (!actor.equals(owner) && !CloudworksTeamContext.canAccess(actor, owner))
            throw new SecurityException("CloudWorks event subscription denied");
        synchronized (listeners) {
            listeners.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(listener);
        }
        return () -> {
            synchronized (listeners) {
                List<Consumer<List<CloudworksEvent>>> values = listeners.get(owner);
                if (values != null) {
                    values.remove(listener);
                    if (values.isEmpty()) listeners.remove(owner);
                }
            }
        };
    }

    @Override
    public CloudworksCardResult useCard(EntityPlayer player, EnumHand hand, ResourceLocation id) {
        CloudworksCardType type = cards.get(id);
        if (type == null) return new CloudworksCardResult(CloudworksCardResult.Status.INVALID_ITEM,
                0, "cloudworks.error.storage", new Object[0]);
        CloudworksCardEffect effect;
        try {
            effect = CloudworksCardEffects.get(id);
        } catch (IllegalArgumentException missingBuiltIn) {
            effect = CloudworksCardEffect.fromApi(type);
        }
        CloudworksCardEffect.Result applied = manager.applyCardEffect(player.getUniqueID(), effect);
        if (applied.applied()) player.getHeldItem(hand).shrink(1);
        if (applied.applied()) emit(new CloudworksEvent(player.getUniqueID(), null,
                CloudworksEvent.Kind.CARD_APPLIED, manager.revision(player.getUniqueID()), 1, id.toString()));
        return new CloudworksCardResult(
                CloudworksCardResult.Status.valueOf(applied.status().name()), applied.value(),
                applied.messageKey(), applied.messageArguments());
    }

    public void publish(CloudworksEvent event) {
        synchronized (pendingEvents) {
            pendingEvents.computeIfAbsent(event.owner, ignored -> new ArrayList<>()).add(event);
        }
    }

    @Override
    public void emit(CloudworksEvent event) {
        publish(event);
    }

    @Override
    public void flushEvents() {
        Map<UUID, List<CloudworksEvent>> batches;
        synchronized (pendingEvents) {
            batches = new LinkedHashMap<>(pendingEvents);
            pendingEvents.clear();
        }
        for (Map.Entry<UUID, List<CloudworksEvent>> entry : batches.entrySet()) {
            List<Consumer<List<CloudworksEvent>>> copy;
            synchronized (listeners) {
                copy = new ArrayList<>(listeners.getOrDefault(entry.getKey(), Collections.emptyList()));
            }
            List<CloudworksEvent> batch = Collections.unmodifiableList(new ArrayList<>(entry.getValue()));
            for (Consumer<List<CloudworksEvent>> listener : copy)
                executor.execute(() -> {
                    try { listener.accept(batch); } catch (RuntimeException ignored) { }
                });
        }
    }

    public void close() {
        closed = true;
        // Queued deposits run their closed/epoch guard and settle their result instead of remaining pending forever.
        if (executor instanceof ExecutorService) ((ExecutorService) executor).shutdown();
        synchronized (listeners) { listeners.clear(); }
    }

    private CloudworksSnapshot snapshot(UUID owner, CloudworksQuery query) {
        // Capture under the exact owner-domain monitor used by all manager
        // mutations. Only the resulting immutable projection crosses to the
        // asynchronous API executor; never read a live domain without it.
        synchronized (manager.ownerLock(owner)) {
        if (closed || !manager.acceptsApiRequests(worldEpoch)) throw new IllegalStateException("CloudWorks server is not running");
        CloudworksData existing = manager.loadIfExists(owner);
        if (existing == null) throw new SecurityException("Unknown CloudWorks owner");
        CloudworksData cloud = existing.copyForRuntime();
        List<CloudworksSnapshot.Line> lines = new ArrayList<>();
        List<CloudworksSnapshot.Node> nodes = new ArrayList<>();
        List<CloudworksSnapshot.Resource> resources = new ArrayList<>();
        long total;
        List<ProductionLineData> owned = manager.lines(owner);
        if (query.scope() == CloudworksQuery.Scope.LINES) {
            total = owned.size();
            for (int i = query.offset(); i < owned.size() && lines.size() < query.limit(); i++) {
                ProductionLineData line = owned.get(i);
                lines.add(new CloudworksSnapshot.Line(line.lineId(), line.name(), line.enabled(),
                        line.runWhileOwnerOffline(), line.energy(), line.maxEnergy(),
                        line.machines().size(), line.channels().size()));
            }
        } else if (query.line() != null) {
            ProductionLineData line = manager.line(query.line());
            if (line == null || !owner.equals(line.owner())) throw new SecurityException("Unknown line");
            if (query.scope() == CloudworksQuery.Scope.NODES) {
                total = line.machines().size() + line.channels().size();
                int index = 0;
                for (MachineNodeData node : line.machines()) {
                    if (index++ < query.offset() || nodes.size() >= query.limit()) continue;
                    nodes.add(new CloudworksSnapshot.Node(node.nodeId(), "machine", node.state(), node.active(),
                            node.machineCount(), node.x(), node.z(), node.machine()));
                }
            } else {
                MachineNodeData node = line.machines().stream().filter(value -> value.nodeId().equals(query.node()))
                        .findFirst().orElseThrow(() -> new SecurityException("Unknown node"));
                List<CloudworksResourceStack> values = query.scope() == CloudworksQuery.Scope.NODE_INPUTS
                        ? node.inputs() : node.outputs();
                total = values.size();
                for (int i = query.offset(); i < values.size() && resources.size() < query.limit(); i++) {
                    CloudworksResourceStack value = values.get(i);
                    resources.add(new CloudworksSnapshot.Resource(value.descriptor(), value.amount(), value.reserved()));
                }
            }
        } else {
            CloudworksInventory inventory = query.scope() == CloudworksQuery.Scope.RECEIVING ? cloud.receiving()
                    : query.scope() == CloudworksQuery.Scope.TRANSFER ? cloud.transfer() : cloud.sending();
            Map<QIOResourceDescriptor, Long> values = inventory.snapshot();
            total = values.size();
            int index = 0;
            for (Map.Entry<QIOResourceDescriptor, Long> entry : values.entrySet()) {
                if (index++ < query.offset() || resources.size() >= query.limit()) continue;
                resources.add(new CloudworksSnapshot.Resource(entry.getKey(), entry.getValue(), 0));
            }
        }
        Map<String, Long> effects = new LinkedHashMap<>();
        return new CloudworksSnapshot(owner, manager.revision(owner), System.currentTimeMillis(), cloud.enabled(),
                cloud.offlineProcessingUnlocked(), cloud.energy(), cloud.maxEnergy(), cloud.receivingEnergy(),
                cloud.maxEnergy(), cloud.sendingEnergy(), cloud.maxEnergy(), cloud.workRate(),
                cloud.enabled() ? manager.generationContext(owner).clockTick()
                        : manager.simulatedTick(), query, total, lines, nodes,
                resources, effects);
        }
    }

}
