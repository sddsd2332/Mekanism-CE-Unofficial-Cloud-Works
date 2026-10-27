package mekanism.cloudworks.common.team;
import java.util.UUID;

/** Optional integration lifecycle; the core never exposes third-party types. */
public interface CloudworksTeamBridge extends AutoCloseable {
    void start();
    default CloudworksExternalTeam team(UUID player) { return null; }

    @Override
    void close();
}
