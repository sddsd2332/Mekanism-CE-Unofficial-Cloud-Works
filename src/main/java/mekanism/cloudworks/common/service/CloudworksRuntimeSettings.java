package mekanism.cloudworks.common.service;

import mekanism.cloudworks.common.config.CloudworksConfig;

/** Server persistence cadence; cloud-domain port changes use the ordered journal. */
public final class CloudworksRuntimeSettings {

    private CloudworksRuntimeSettings() {}

    public static int checkpointIntervalTicks() { return CloudworksConfig.settings().checkpointIntervalTicks; }
    public static boolean durableCheckpoints() { return CloudworksConfig.settings().durableCheckpoints; }
}
