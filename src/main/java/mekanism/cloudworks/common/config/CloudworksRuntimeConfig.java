package mekanism.cloudworks.common.config;

import mekanism.common.config.BaseConfig;
import mekanism.common.config.options.BooleanOption;
import mekanism.common.config.options.IntOption;

public final class CloudworksRuntimeConfig extends BaseConfig {
    public final IntOption workerThreads = new IntOption(this, "workerThreads", 0,
            "Runtime worker threads. Zero selects automatically from the available processors.", 0, 256)
            .setRequiresWorldRestart();
    public final IntOption checkpointIntervalTicks = new IntOption(this, "checkpointIntervalTicks", 200,
            "Ticks between periodic disk checkpoint requests when durableCheckpoints is enabled. "
                    + "Port transactions still use the ordered journal.", 20, 12000)
            .setRequiresWorldRestart();
    public final BooleanOption durableCheckpoints = new BooleanOption(this, "durableCheckpoints", false,
            "Periodically persist Runtime progress. False keeps in-memory checkpoints and saves at explicit "
                    + "flush, idle eviction and shutdown boundaries. A crash can lose simulated progress since "
                    + "the last durable boundary. Physical port transactions retain their ordered journal.")
            .setRequiresWorldRestart();
    public final IntOption portTransferIntervalTicks = new IntOption(this, "portTransferIntervalTicks", 200,
            "Ticks between upload/download port transfers, excluding time spent waiting for resources or buffer room.", 1, 12000)
            .setRequiresWorldRestart();
    public final IntOption guiRefreshIntervalTicks = new IntOption(this, "guiRefreshIntervalTicks", 20,
            "Ticks between periodic cloud GUI data refreshes while a container is open. "
                    + "1 = every tick; 20 = once per second.", 1, 20)
            .setRequiresWorldRestart();

    @Override public String getCategory() { return "runtime"; }
}
