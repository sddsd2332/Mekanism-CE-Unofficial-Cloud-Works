package mekanism.cloudworks.common.team;

import java.util.UUID;
import net.minecraft.entity.player.EntityPlayer;
import java.nio.file.Path;

/** Authoritative server-only team facade. GUI data never grants server access. */
public final class CloudworksTeamContext {
    private static final CloudworksTeamRegistry REGISTRY = new CloudworksTeamRegistry();

    private CloudworksTeamContext() { }
    public static void load(Path root) { REGISTRY.load(root); }
    public static void close() { REGISTRY.close(); }
    public static CloudworksTeamRegistry registry() { return REGISTRY; }
    public static UUID owner(EntityPlayer player) { return player == null ? null : owner(player.getUniqueID()); }
    public static UUID owner(UUID player) { return REGISTRY.owner(player); }
    public static boolean canAccess(EntityPlayer player, UUID team) {
        return player != null && REGISTRY.canAccess(player.getUniqueID(), team);
    }
    public static boolean canAccess(UUID player, UUID team) { return REGISTRY.canAccess(player, team); }
    public static boolean anyMemberOnline(UUID team) { return REGISTRY.anyMemberOnline(team, null); }
    public static boolean anyMemberOnline(UUID team, UUID excluding) { return REGISTRY.anyMemberOnline(team, excluding); }
    public static void playerLoggedIn(UUID player, String name) { REGISTRY.setOnline(player, true, name); }
    public static void playerLoggedOut(UUID player) { REGISTRY.setOnline(player, false, null); }
    public static CloudworksTeamSnapshot snapshot(UUID team) { return REGISTRY.snapshot(team); }
}
