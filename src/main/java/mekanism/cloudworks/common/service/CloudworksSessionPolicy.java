package mekanism.cloudworks.common.service;

import mekanism.cloudworks.common.data.CloudworksData;
import mekanism.cloudworks.common.data.ProductionLineData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Process-local presence; loading a record never grants a running session.
 */
public final class CloudworksSessionPolicy {
    private final Set<UUID> joined = new HashSet<>();
    private final Set<UUID> online = new HashSet<>();

    public void loggedIn(UUID owner) {
        joined.add(owner);
        online.add(owner);
    }

    public void loggedOut(UUID owner) {
        online.remove(owner);
    }

    public void clear() {
        joined.clear();
        online.clear();
    }

    public boolean canRun(CloudworksData cloud, ProductionLineData line) {
        return canRun(cloud, line.owner(), line.enabled(), line.runWhileOwnerOffline());
    }

    public boolean canRun(CloudworksData cloud, UUID lineOwner, boolean enabled, boolean offline) {
        UUID owner = cloud.owner();
        return cloud.enabled() && enabled && owner.equals(lineOwner) && joined.contains(owner)
                && (online.contains(owner) || mekanism.cloudworks.common.config.CloudworksConfig.settings().offlineProcessingEnabled
                    && cloud.offlineProcessingUnlocked() && offline);
    }
}
