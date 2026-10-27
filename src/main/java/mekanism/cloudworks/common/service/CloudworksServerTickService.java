package mekanism.cloudworks.common.service;

import mekanism.cloudworks.common.runtime.CloudworksRuntimeService;
import mekanism.cloudworks.common.team.CloudworksTeamContext;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.util.concurrent.FutureTask;
import java.util.Set;
import java.util.UUID;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.common.config.CloudworksConfig;
import mekanism.cloudworks.common.content.CloudworksPhysicalCheckpointService;
import mekanism.cloudworks.common.content.CloudworksTerminalPersistence;
import mekanism.cloudworks.common.network.CloudworksNetwork;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * Main-thread lifecycle and physical boundaries for player-owned Runtime worlds.
 */
public final class CloudworksServerTickService {
    public static final CloudworksServerTickService INSTANCE = new CloudworksServerTickService();
    private final CloudworksSessionPolicy sessions = new CloudworksSessionPolicy();
    private CloudworksRuntimeService runtime;
    private CloudworksGuiService gui;
    private CloudworksCommandService commands;
    private long maintenanceTick;

    private CloudworksServerTickService() {
    }

    public void onServerStarting() {
        onServerStarting(null);
    }

    /**
     * Starts the runtime and, when a real server world is available, installs
     * its random source for transaction snapshots. The supplier is only
     * evaluated by the owner thread during snapshot capture.
     */
    public void onServerStarting(WorldServer world) {
        if (runtime != null) throw new IllegalStateException("Cloudworks Runtime already started");
        maintenanceTick = 0;
        if (world != null) {
            CloudworksTransactionSeeds.production(world.rand::nextLong);
        }
        CloudworksPhysicalCheckpointService.start();
        int processors = Math.max(2, Runtime.getRuntime().availableProcessors());
        int workers = CloudworksConfig.settings().workers(processors);
        runtime = new CloudworksRuntimeService(workers);
        gui = new CloudworksGuiService(Math.max(1, Math.min(2, processors / 4)));
        commands = new CloudworksCommandService();
        CloudworksApiServices apiServices = new CloudworksApiServices(
                CloudworksPlayerDataManager.INSTANCE);
        CloudworksAPI.installServices(apiServices);
        CloudworksAPI.rebindCards(apiServices::registerCard);
        CloudworksNativeContracts.refresh();
    }

    /**
     * Structural-command admission (Runtime §3.3); null-safe before server start.
     */
    public boolean submitStructuralCommand(UUID owner, Runnable command) {
        CloudworksCommandService service = commands;
        return service != null && service.submit(owner == null ? new UUID(0L, 0L) : owner, command);
    }

    /**
     * Production counters for tests and diagnostics; read-only snapshot semantics.
     */
    public CloudworksRuntimeService.Stats stats() {
        return runtime == null ? new CloudworksRuntimeService.Stats() : runtime.statsSnapshot();
    }

    /**
     * Planning-queue backlog size; diagnostics only.
     */
    public int debugPendingCount() {
        return runtime == null ? -1 : runtime.pendingCount();
    }

    public boolean debugOwnerCompiled(UUID owner) { return runtime != null && runtime.compiled(owner); }
    public long debugOwnerTickDebt(UUID owner) { return runtime == null ? 0 : runtime.tickDebt(owner); }

    public CloudworksRuntimeService.Phase virtualPhase(UUID owner) {
        return runtime == null ? CloudworksRuntimeService.Phase.IDLE : runtime.phase(owner);
    }

    public long runtimeTickDebt(UUID owner) {
        return runtime == null ? 0 : runtime.tickDebt(owner);
    }

    public FutureTask<CloudworksGuiService.Result> submitGui(
            CloudworksGuiRequest request, long generation, byte[][] previous) {
        return gui == null ? null : gui.submit(request, generation, previous);
    }

    public FutureTask<CloudworksResourceQuery.Result> submitResourceQuery(
            CloudworksResourceQuery request) {
        return gui == null ? null : gui.submit(request);
    }

    public void playerLoggedIn(UUID owner) {
            sessions.loggedIn(owner);
            // Invalidate any detached results captured before this presence change.
            CloudworksPlayerDataManager.INSTANCE.noteSessionEvent(owner);
            UUID teamOwner = CloudworksTeamContext.owner(owner);
            if (teamOwner != null) {
                teamMemberLoggedIn(teamOwner);
            }
    }

    public void teamMemberLoggedIn(UUID teamOwner) {
        if (teamOwner == null) return;
        sessions.loggedIn(teamOwner);
        CloudworksPlayerDataManager.INSTANCE.noteSessionEvent(teamOwner);
    }

    public void teamMemberLoggedOut(UUID teamOwner) {
        sessions.loggedOut(teamOwner);
        CloudworksPlayerDataManager.INSTANCE.noteSessionEvent(teamOwner);
    }

    public void playerLoggedOut(UUID owner) {
        playerLoggedOut(owner, CloudworksTeamContext.owner(owner));
    }

    public void playerLoggedOut(UUID owner, UUID knownTeamOwner) {
            sessions.loggedOut(owner);
            CloudworksPlayerDataManager.INSTANCE.noteSessionEvent(owner);
            UUID teamOwner = knownTeamOwner;
            if (teamOwner != null && !CloudworksTeamContext.anyMemberOnline(teamOwner)) {
                sessions.loggedOut(teamOwner);
                CloudworksPlayerDataManager.INSTANCE.noteSessionEvent(teamOwner);
            }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        runServerTick(TickEvent.Phase.END);
    }

    /**
     * The Forge event body; also the direct entry used by lifecycle tests.
     */
    public void runServerTick(TickEvent.Phase phase) {
        if (phase != TickEvent.Phase.END) return;
        try {
            CloudworksTerminalPersistence.completePlayerWrites();
            // Parked upload acknowledgements complete on the server thread once
            // the IO backlog settles; the disk probe alone decides each one.
            CloudworksPlayerDataManager.INSTANCE.completePendingUploadAcknowledgements();
            if (CloudworksPlayerDataManager.INSTANCE.recoveryRequired()) return;
            // 协议储存箱: paused ports get their resume notification the moment the
            // receiving warehouse regains room for their blocked type.
            CloudworksPlayerDataManager.INSTANCE.dispatchTypeUnblockNotifications();
            CloudworksPlayerDataManager.INSTANCE.maintainPortRecords(
                    ++maintenanceTick);
            tickLoadedClouds();
            drainRecycleOverflowNotices();
            CloudworksAPI.flushEvents();
        } finally {
            CloudworksPhysicalCheckpointService.flush();
        }
    }

    /**
     * Aggregated chat + toast notices for recycle overflow, one message per player per tick.
     */
    private void drainRecycleOverflowNotices() {
        Set<UUID> owners = CloudworksPlayerDataManager.INSTANCE.drainRecycleOverflowNotices();
        if (owners.isEmpty()) return;
        MinecraftServer server;
        try {
            server = FMLCommonHandler.instance().getMinecraftServerInstance();
        } catch (RuntimeException | NoClassDefFoundError noServer) {
            return;   // bare unit-test environment without a launched FML server
        }
        if (server == null) return;
        for (UUID owner : owners) {
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(owner);
            if (player == null) continue;
            player.sendMessage(new TextComponentTranslation("chat.cloudworks.recycle_overflow"));
            CloudworksNetwork.init();
            CloudworksNetwork.sendNotice(
                    player, CloudworksNetwork.Notice.RECYCLE_OVERFLOW);
        }
    }

    private void tickLoadedClouds() {
        if (runtime == null) return;
        Long worldTime = overworldTime();
        if (worldTime == null) CloudworksPlayerDataManager.INSTANCE.advanceSimulatedClock();
        else CloudworksPlayerDataManager.INSTANCE.syncSimulatedClock(worldTime);
        runtime.beginTick();
        runtime.serverTick(CloudworksPlayerDataManager.INSTANCE, sessions);
    }

    private static Long overworldTime() {
        try {
            MinecraftServer server =
                    FMLCommonHandler.instance().getMinecraftServerInstance();
            if (server == null) return null;
            WorldServer overworld = server.getWorld(0);
            return overworld == null ? null : overworld.getWorldTime();
        } catch (RuntimeException | NoClassDefFoundError noServer) {
            return null;
        }
    }

    public void onServerStopping() {
        try {
            try {
                try { CloudworksPlayerDataManager.INSTANCE.flushMountedPorts(); }
                finally { if (commands != null) commands.close(); }
            } finally {
                try {
                    if (gui != null) gui.close();
                } finally {
                    try {
                        if (runtime != null) runtime.close(CloudworksPlayerDataManager.INSTANCE);
                    } finally {
                        try { CloudworksPhysicalCheckpointService.shutdown(); }
                        finally { CloudworksTerminalPersistence.shutdown(); }
                    }
                }
            }
        } finally {
            runtime = null;
            gui = null;
            commands = null;
            maintenanceTick = 0;
            sessions.clear();
            CloudworksNativeContracts.clear();
            CloudworksAPI.shutdownServices();
            CloudworksTransactionSeeds.production(null);
        }
    }
}
