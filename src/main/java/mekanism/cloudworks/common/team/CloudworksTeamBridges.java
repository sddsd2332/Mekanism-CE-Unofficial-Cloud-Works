package mekanism.cloudworks.common.team;
import java.util.UUID;
import mekanism.cloudworks.common.integration.ftb.CloudworksTeamBridgeFtb;
import net.minecraftforge.fml.common.Loader;

/** Holds the selected optional-team bridge for the current server session. */
public final class CloudworksTeamBridges {
    private static CloudworksTeamBridge active = new CloudworksTeamBridgeNone();
    private static boolean available;

    private CloudworksTeamBridges() {
    }

    public static void install() {
        close();
        active = new CloudworksTeamBridgeNone();
        available = Loader.isModLoaded("ftblib");
        if (available) {
            active = new CloudworksTeamBridgeFtb();
        }
        active.start();
    }

    public static void close() {
        try {
            active.close();
        } finally {
            active = new CloudworksTeamBridgeNone();
            available = false;
        }
    }

    public static boolean available() { return available; }
    public static CloudworksExternalTeam team(UUID player) { return active.team(player); }
}
