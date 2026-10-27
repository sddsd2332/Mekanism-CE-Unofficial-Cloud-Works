package mekanism.cloudworks.common.team;

/** No-op bridge used when no optional team mod is installed. */
public final class CloudworksTeamBridgeNone implements CloudworksTeamBridge {
    @Override
    public void start() {
    }

    @Override
    public void close() {
    }
}
