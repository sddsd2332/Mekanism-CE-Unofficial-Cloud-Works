package mekanism.cloudworks.common.team;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import mekanism.cloudworks.common.network.CloudworksNetwork;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.cloudworks.common.service.CloudworksServerTickService;
import mekanism.cloudworks.common.util.CloudworksMessages;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.common.Mekanism;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

/** Low-frequency membership transactions use the existing bounded structural executor. */
public final class CloudworksTeamManager {
    private static final UUID COMMAND_OWNER = new UUID(0, 1);
    private static final Map<UUID, Boolean> PRESENCE = new LinkedHashMap<>();
    private static final Set<UUID> RECOVERY_REGISTERED = new HashSet<>();

    private CloudworksTeamManager() { }

    public static boolean submit(Runnable mutation, Consumer<String> completion) {
        CloudworksTeamRegistry registry = CloudworksTeamContext.registry();
        return CloudworksServerTickService.INSTANCE.submitStructuralCommand(COMMAND_OWNER,
                transaction(registry, mutation, completion, CloudworksNetwork::hopToMainThread));
    }

    static Runnable transaction(CloudworksTeamRegistry registry, Runnable mutation,
            Consumer<String> completion, Consumer<Runnable> mainThread) {
        long epoch = registry.epoch();
        return () -> {
            if (!registry.loaded() || registry.epoch() != epoch) {
                mainThread.accept(() -> {
                    if (completion != null) completion.accept("cloudworks.team.error.changed");
                });
                return;
            }
            String status = CloudworksMessages.SAVED;
            try {
                mutation.run();
            } catch (RuntimeException failure) {
                status = failure.getMessage() != null && failure.getMessage().startsWith("cloudworks.team.error.")
                        ? failure.getMessage() : CloudworksMessages.errorKey(failure);
                String message = failure.getMessage();
                if (isExpectedRejection(message))
                    Mekanism.logger.debug(
                            "Cloudworks team transaction rejected: {}", message);
                else
                    Mekanism.logger.warn("Cloudworks team transaction failed", failure);
            }
            // An FTB batch can contain a failed event followed by a successful
            // dissolve. Always admit the successful dissolutions to recovery.
            if (registry.loaded() && registry.epoch() == epoch) {
                try {
                    recoverDissolutions();
                    for (CloudworksTeamSnapshot team : registry.snapshots())
                        if (registry.anyMemberOnline(team.id(), null)
                                && CloudworksPlayerDataManager.INSTANCE.recordEnabled(team.id()))
                            CloudworksPlayerDataManager.INSTANCE.getOrCreate(team.id());
                } catch (RuntimeException failure) {
                    if (CloudworksMessages.SAVED.equals(status)) status = CloudworksMessages.errorKey(failure);
                    Mekanism.logger.warn("Cloudworks team recovery admission failed", failure);
                }
            }
            String result = status;
            mainThread.accept(() -> {
                if (!registry.loaded() || registry.epoch() != epoch) {
                    if (completion != null) completion.accept("cloudworks.team.error.changed");
                    return;
                }
                try {
                    refreshPresence();
                } finally {
                    if (completion != null) completion.accept(result);
                }
            });
        };
    }

    private static boolean isExpectedRejection(String message) {
        return message != null && (message.startsWith("cloudworks.team.error.")
                || "cloudworks.error.busy".equals(message)
                || "cloudworks.error.disabled".equals(message)
                || "cloudworks.error.team_required".equals(message)
                || "cloudworks.error.team_recovery".equals(message));
    }

    /** The registry retains dissolving records so an interrupted recovery admission is retried on restart. */
    public static void recoverDissolutions() {
        for (CloudworksTeamSnapshot team : CloudworksTeamContext.registry().dissolving()) {
            if (RECOVERY_REGISTERED.contains(team.id())) continue;
            if (!CloudworksPlayerDataManager.INSTANCE.hasCloudRecord(team.id())) {
                CloudworksTeamContext.registry().finishRecovery(team.id());
                continue;
            }
            CloudworksPlayerDataManager.INSTANCE.markTeamDissolved(
                    team.id(), team.name(), (short) 0, team.owner());
            RECOVERY_REGISTERED.add(team.id());
        }
    }

    public static void recoveryCompleted(UUID team) {
        CloudworksTeamContext.registry().finishRecovery(team);
        RECOVERY_REGISTERED.remove(team);
    }

    public static void refreshPresence() {
        Map<UUID, Boolean> next = new LinkedHashMap<>();
        for (CloudworksTeamSnapshot team : CloudworksTeamContext.registry().snapshots()) {
            boolean present = CloudworksTeamContext.anyMemberOnline(team.id());
            next.put(team.id(), present);
            if (present && !Boolean.TRUE.equals(PRESENCE.get(team.id())))
                CloudworksServerTickService.INSTANCE.teamMemberLoggedIn(team.id());
            else if (!present && Boolean.TRUE.equals(PRESENCE.get(team.id())))
                CloudworksServerTickService.INSTANCE.teamMemberLoggedOut(team.id());
        }
        for (UUID id : PRESENCE.keySet())
            if (!next.containsKey(id)) CloudworksServerTickService.INSTANCE.teamMemberLoggedOut(id);
        PRESENCE.clear();
        PRESENCE.putAll(next);
        MinecraftServer server =
                FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null)
            for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                if (player.openContainer instanceof ContainerCloudworks container
                        && container.isTeamTerminal() && !container.canInteractWith(player))
                    player.closeScreen();
                try {
                    deliverNotices(player);
                } catch (RuntimeException failure) {
                    Mekanism.logger.warn("Could not deliver Cloudworks team notices", failure);
                }
            }
    }

    /** Online members receive notices after the transaction; offline members receive them on login. */
    public static void deliverNotices(EntityPlayerMP player) {
        if (player == null) return;
        deliverNotices(player.getUniqueID(), notice ->
                CloudworksNetwork.sendNotice(player, CloudworksNetwork.Notice.TEAM_DISSOLVED,
                        notice.leader, notice.leaderName, notice.teamName));
    }

    static void deliverNotices(UUID player, Consumer<CloudworksTeamRegistry.TeamDissolutionNotice> delivery) {
        for (CloudworksTeamRegistry.TeamDissolutionNotice notice :
                CloudworksTeamContext.registry().takeNotices(player))
            delivery.accept(notice);
    }

    public static void close() { PRESENCE.clear(); RECOVERY_REGISTERED.clear(); }

    public static CloudworksGuiData.Team view(UUID player) {
        CloudworksTeamRegistry registry = CloudworksTeamContext.registry();
        CloudworksTeamSnapshot team = registry.snapshot(registry.owner(player));
        List<CloudworksGuiData.Member> members = new ArrayList<>();
        for (CloudworksTeamMember member : team.members())
            members.add(new CloudworksGuiData.Member(member.id(), member.name(), member.online(), team.allowed(member.id())));
        List<CloudworksGuiData.TeamRequest> requests = new ArrayList<>();
        if (team.owner() != null && team.owner().equals(player))
            for (CloudworksTeamMember request : team.requests().values())
                requests.add(new CloudworksGuiData.TeamRequest(request.id(), request.name()));
        return new CloudworksGuiData.Team(team.id(), team.name(), team.owner(), members, team.ftbBinding,
                CloudworksTeamBridges.available(), team.joinMode.name(), team.color, requests);
    }
}
