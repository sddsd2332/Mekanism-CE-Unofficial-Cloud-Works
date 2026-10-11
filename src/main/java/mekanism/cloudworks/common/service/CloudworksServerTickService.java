package mekanism.cloudworks.common.service;

import mekanism.cloudworks.common.runtime.CloudworksRuntimeService;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

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
        if (runtime != null) throw new IllegalStateException("Cloudworks Runtime already started");
        maintenanceTick = 0;
        mekanism.cloudworks.common.content.CloudworksPhysicalCheckpointService.start();
        int processors = Math.max(2, Runtime.getRuntime().availableProcessors());
        int workers = mekanism.cloudworks.common.config.CloudworksConfig.settings().workers(processors);
        runtime = new CloudworksRuntimeService(workers);
        gui = new CloudworksGuiService(Math.max(1, Math.min(2, processors / 4)));
        commands = new CloudworksCommandService();
        CloudworksNativeContracts.refresh();
    }

    /**
     * Structural-command admission (Runtime §3.3); null-safe before server start.
     */
    public boolean submitStructuralCommand(Runnable command) {
        return submitStructuralCommand(null, command);
    }

    public boolean submitStructuralCommand(java.util.UUID owner, Runnable command) {
        CloudworksCommandService service = commands;
        return service != null && service.submit(owner == null ? new java.util.UUID(0L, 0L) : owner, command);
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

    public CloudworksRuntimeService.Phase virtualPhase(java.util.UUID owner) {
        return runtime == null ? CloudworksRuntimeService.Phase.IDLE : runtime.phase(owner);
    }

    public long runtimeTickDebt(java.util.UUID owner) {
        return runtime == null ? 0 : runtime.tickDebt(owner);
    }

    public java.util.concurrent.FutureTask<CloudworksGuiService.Result> submitGui(
            CloudworksGuiRequest request, long generation, byte[][] previous) {
        return gui == null ? null : gui.submit(request, generation, previous);
    }

    public java.util.concurrent.FutureTask<CloudworksResourceQuery.Result> submitResourceQuery(
            CloudworksResourceQuery request) {
        return gui == null ? null : gui.submit(request);
    }

    public void playerLoggedIn(java.util.UUID owner) {
            sessions.loggedIn(owner);
            // Invalidate any detached results captured before this presence change.
            CloudworksPlayerDataManager.INSTANCE.noteSessionEvent(owner);
    }

    public void playerLoggedOut(java.util.UUID owner) {
            sessions.loggedOut(owner);
            CloudworksPlayerDataManager.INSTANCE.noteSessionEvent(owner);
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
            mekanism.cloudworks.common.content.CloudworksTerminalPersistence.completePlayerWrites();
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
        } finally {
            mekanism.cloudworks.common.content.CloudworksPhysicalCheckpointService.flush();
        }
    }

    /**
     * Aggregated chat + toast notices for recycle overflow, one message per player per tick.
     */
    private void drainRecycleOverflowNotices() {
        java.util.Set<java.util.UUID> owners = CloudworksPlayerDataManager.INSTANCE.drainRecycleOverflowNotices();
        if (owners.isEmpty()) return;
        net.minecraft.server.MinecraftServer server;
        try {
            server = net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        } catch (RuntimeException | NoClassDefFoundError noServer) {
            return;   // bare unit-test environment without a launched FML server
        }
        if (server == null) return;
        for (java.util.UUID owner : owners) {
            net.minecraft.entity.player.EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(owner);
            if (player == null) continue;
            player.sendMessage(new net.minecraft.util.text.TextComponentTranslation("chat.cloudworks.recycle_overflow"));
            mekanism.cloudworks.common.network.CloudworksNetwork.init();
            mekanism.cloudworks.common.network.CloudworksNetwork.sendNotice(
                    player, mekanism.cloudworks.common.network.CloudworksNetwork.Notice.RECYCLE_OVERFLOW);
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
            net.minecraft.server.MinecraftServer server =
                    net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
            if (server == null) return null;
            net.minecraft.world.WorldServer overworld = server.getWorld(0);
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
                        try { mekanism.cloudworks.common.content.CloudworksPhysicalCheckpointService.shutdown(); }
                        finally { mekanism.cloudworks.common.content.CloudworksTerminalPersistence.shutdown(); }
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
        }
    }
}
