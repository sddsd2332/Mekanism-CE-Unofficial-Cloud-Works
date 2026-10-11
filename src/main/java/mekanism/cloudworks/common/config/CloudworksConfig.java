package mekanism.cloudworks.common.config;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.File;
import javax.annotation.Nullable;
import net.minecraftforge.common.config.Configuration;

/** Mek-style options; server workers read only the immutable startup snapshot. */
public final class CloudworksConfig {
    private static final CloudworksConfig LOCAL = new CloudworksConfig();
    private static CloudworksConfig synced;
    private static Settings syncedSettings;
    private static Configuration file;
    private static boolean serverRunning;
    private static CloudworksConfig serverValues = LOCAL;
    private static volatile Settings settings = new Settings(LOCAL);

    public final CloudworksGeneralConfig general = new CloudworksGeneralConfig();
    public final CloudworksRuntimeConfig runtime = new CloudworksRuntimeConfig();

    public static CloudworksConfig local() { return LOCAL; }
    public static CloudworksConfig current() { return synced == null ? LOCAL : synced; }
    public static Settings settings() { return settings; }
    public static Settings currentSettings() { return syncedSettings == null ? settings : syncedSettings; }

    public static void load(File path) {
        file = new Configuration(path);
        reload();
    }

    public static void reload() {
        if (file == null) return;
        file.load();
        LOCAL.general.load(file);
        LOCAL.runtime.load(file);
        if (file.hasChanged()) file.save();
        if (!serverRunning) freeze();
    }

    public static void serverStarting() {
        freeze();
        serverRunning = true;
    }

    public static void serverStopped() { serverRunning = false; }

    private static void freeze() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            LOCAL.write(buffer);
            CloudworksConfig copy = new CloudworksConfig();
            copy.read(buffer);
            Settings snapshot = new Settings(copy);
            serverValues = copy;
            settings = snapshot;
        } finally { buffer.release(); }
    }

    public static void writeServer(ByteBuf buffer) { serverValues.write(buffer); }

    public static void readServer(ByteBuf buffer) {
        CloudworksConfig config = new CloudworksConfig();
        config.read(buffer);
        setSyncedConfig(config);
    }

    public static void setSyncedConfig(@Nullable CloudworksConfig config) {
        syncedSettings = config == null ? null : new Settings(config);
        synced = config;
    }
    private void write(ByteBuf buffer) { general.write(buffer); runtime.write(buffer); }
    private void read(ByteBuf buffer) { general.read(buffer); runtime.read(buffer); }

    public static final class Settings {
        public final double receivingCapacity, cloudCapacity, sendingCapacity;
        public final int initialLines, maxLines, initialMachineNodes, maxMachineNodes, initialWorkRate, maxWorkRate;
        public final long maxMachinesPerNode, recycleCapacityPerType;
        public final boolean offlineProcessingEnabled;
        public final int workerThreads, checkpointIntervalTicks, portTransferIntervalTicks, guiRefreshIntervalTicks;

        private Settings(CloudworksConfig source) {
            CloudworksGeneralConfig general = source.general;
            receivingCapacity = capacity(general.initialReceivingEnergyCapacity.val());
            cloudCapacity = capacity(general.initialCloudEnergyCapacity.val());
            sendingCapacity = capacity(general.initialSendingEnergyCapacity.val());
            maxLines = positive(general.maxLines.val());
            initialLines = Math.min(positive(general.initialLines.val()), maxLines);
            maxMachineNodes = positive(general.maxMachineNodes.val());
            initialMachineNodes = Math.min(positive(general.initialMachineNodes.val()), maxMachineNodes);
            maxWorkRate = positive(general.maxWorkRate.val());
            initialWorkRate = Math.min(positive(general.initialWorkRate.val()), maxWorkRate);
            maxMachinesPerNode = general.maxMachinesPerNode.val();
            recycleCapacityPerType = general.recycleCapacityPerType.val();
            if (maxMachinesPerNode < 1 || recycleCapacityPerType < 1 || recycleCapacityPerType > Long.MAX_VALUE / 128)
                throw new IllegalArgumentException("Invalid Cloudworks quantity limit");
            offlineProcessingEnabled = general.offlineProcessingEnabled.val();
            workerThreads = source.runtime.workerThreads.val();
            checkpointIntervalTicks = source.runtime.checkpointIntervalTicks.val();
            portTransferIntervalTicks = source.runtime.portTransferIntervalTicks.val();
            guiRefreshIntervalTicks = source.runtime.guiRefreshIntervalTicks.val();
            if (workerThreads < 0 || workerThreads > 256 || checkpointIntervalTicks < 20 || checkpointIntervalTicks > 12000
                    || portTransferIntervalTicks < 1 || portTransferIntervalTicks > 12000
                    || source.runtime.guiRefreshIntervalTicks.val() < 1
                    || source.runtime.guiRefreshIntervalTicks.val() > 20)
                throw new IllegalArgumentException("Invalid Cloudworks Runtime settings");
        }

        public int workers(int processors) {
            return workerThreads == 0 ? Math.max(2, Math.min(32, Math.max(2, processors) - 1)) : workerThreads;
        }

        private static int positive(int value) {
            if (value < 1) throw new IllegalArgumentException("Invalid Cloudworks limit");
            return value;
        }

        private static double capacity(double value) {
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid Cloudworks capacity");
            return value;
        }
    }
}
