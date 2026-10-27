package mekanism.cloudworks.common.team;

import java.util.UUID;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import net.minecraft.entity.player.EntityPlayerMP;
import java.util.Objects;
import net.minecraft.util.text.TextComponentTranslation;

/** Captures GUI inputs on the server thread; all persistent team changes run on the command worker. */
public final class CloudworksTeamActions {
    private CloudworksTeamActions() { }

    public static boolean isTeamAction(Operation operation) {
        switch (operation) {
            case TEAM_ACCESS: case TEAM_CREATE: case TEAM_JOIN: case TEAM_REMOVE:
            case TEAM_REQUEST_JOIN: case TEAM_APPROVE_JOIN: case TEAM_DENY_JOIN: case TEAM_SET_MODE: case TEAM_SET_COLOR: case TEAM_IMPORT:
            case TEAM_LEAVE: case TEAM_RENAME: case TEAM_OWNER: case TEAM_DISSOLVE:
                return true;
            default: return false;
        }
    }

    public static void handle(EntityPlayerMP player, ContainerCloudworks container,
            Operation operation, UUID selected, String text) {
        UUID actor = player.getUniqueID();
        String actorName = player.getName();
        UUID expectedTeam = CloudworksTeamContext.owner(actor);
        CloudworksExternalTeam external = operation == Operation.TEAM_IMPORT
                ? CloudworksTeamBridges.team(actor) : null;
        if ((operation == Operation.TEAM_ACCESS || operation == Operation.TEAM_REMOVE
                || operation == Operation.TEAM_OWNER || operation == Operation.TEAM_JOIN) && selected == null) {
            container.sendData("cloudworks.team.error.invalid_action");
            return;
        }
        boolean queued = CloudworksTeamManager.submit(() -> {
            CloudworksTeamRegistry registry = CloudworksTeamContext.registry();
            if (!Objects.equals(expectedTeam, registry.owner(actor)))
                throw new IllegalArgumentException("cloudworks.team.error.changed");
            switch (operation) {
                case TEAM_CREATE -> registry.create(actor, textName(text, actorName), textColor(text), textMode(text));
                case TEAM_JOIN -> registry.join(actor, actorName, selected);
                case TEAM_REQUEST_JOIN -> registry.requestJoin(actor, actorName, selected);
                case TEAM_APPROVE_JOIN -> registry.approveJoin(actor, expectedTeam, selected);
                case TEAM_DENY_JOIN -> registry.denyJoin(actor, expectedTeam, selected);
                case TEAM_SET_MODE -> registry.setJoinMode(actor, expectedTeam, CloudworksTeam.JoinMode.valueOf(text));
                case TEAM_SET_COLOR -> registry.setColor(actor, expectedTeam, Integer.parseUnsignedInt(text, 16));
                case TEAM_REMOVE -> registry.removeMember(actor, expectedTeam, selected);
                case TEAM_LEAVE -> registry.leave(actor);
                case TEAM_RENAME -> registry.rename(actor, expectedTeam, text);
                case TEAM_OWNER -> registry.transferOwner(actor, selected);
                case TEAM_DISSOLVE -> {
                    CloudworksTeamSnapshot before = registry.snapshot(expectedTeam);
                    UUID dissolved = registry.dissolve(actor);
                    CloudworksPlayerDataManager.INSTANCE.markTeamDissolved(
                            dissolved, before.name(), (short) 0, actor);
                }
                case TEAM_IMPORT -> {
                    if (external == null) throw new IllegalArgumentException("cloudworks.team.error.ftb_required");
                    registry.importMembers(actor, external);
                }
                case TEAM_ACCESS -> {
                    if (!"0".equals(text) && !"1".equals(text))
                        throw new IllegalArgumentException("cloudworks.team.error.invalid_action");
                    registry.setAllowed(actor, expectedTeam, selected, "1".equals(text));
                }
                default -> throw new IllegalArgumentException("cloudworks.team.error.invalid_action");
            }
            UUID team = registry.owner(actor);
            if (team != null) CloudworksPlayerDataManager.INSTANCE.enable(team);
        }, status -> {
            if (player.openContainer != container) return;
            if (!container.canInteractWith(player)) {
                player.closeScreen();
                player.sendMessage(new TextComponentTranslation(status));
            } else container.sendData(status);
        });
        if (!queued) container.sendData("cloudworks.error.busy");
    }

    private static String textName(String text, String fallback) {
        if (text == null || text.isEmpty()) return fallback + "'s Cloudworks";
        return text.split("\\|", -1)[0];
    }
    private static int textColor(String text) {
        try { return Integer.parseUnsignedInt(text.split("\\|", -1).length > 1 ? text.split("\\|", -1)[1] : "ff4f7fff", 16); }
        catch (RuntimeException invalid) { return 0xFF4F7FFF; }
    }
    private static CloudworksTeam.JoinMode textMode(String text) {
        try {
            String[] parts = text.split("\\|", -1);
            return parts.length > 2 ? CloudworksTeam.JoinMode.valueOf(parts[2]) : CloudworksTeam.JoinMode.APPROVAL;
        } catch (RuntimeException invalid) { return CloudworksTeam.JoinMode.APPROVAL; }
    }
}
