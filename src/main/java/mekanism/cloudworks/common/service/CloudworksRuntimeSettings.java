package mekanism.cloudworks.common.service;

import java.io.File;
import mekanism.cloudworks.common.config.CloudworksConfig;

/** Server persistence cadence; cloud-domain port changes use the ordered journal. */
public final class CloudworksRuntimeSettings {

    private CloudworksRuntimeSettings() {}

    public static void load(File file) {
        CloudworksConfig.load(file);
    }

    public static int checkpointIntervalTicks() { return CloudworksConfig.settings().checkpointIntervalTicks; }
}
