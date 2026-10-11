package mekanism.cloudworks.common.data;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;

import java.util.*;

public final class CloudworksData {
    public static final int SCHEMA_VERSION = 1;
    /**
     * Schema of each single staging-inventory file (receiving.dat / transfer.dat / sending.dat).
     */
    public static final int INVENTORY_SCHEMA_VERSION = 1;
    /**
     * Default per-line machine node ceiling stored on the cloud record.
     */
    public static final int DEFAULT_MAX_MACHINE_NODES = 64;
    private final UUID owner;
    private final List<UUID> lineIds = new ArrayList<>();
    private boolean enabled;
    private boolean offlineProcessingUnlocked;
    /**
     * Recycle bin routing: true = transfer warehouse (reprocess), false = sending warehouse (download).
     */
    private boolean recycleToTransfer = true;
    /**
     * Warehouse energy relay (收货): upload-port surplus lands here before the flows move it on.
     */
    private double receivingEnergy;
    /**
     * Warehouse energy relay (发货): download ports refill from here.
     */
    private double sendingEnergy;
    /**
     * 云端升级池: speed upgrades owned by the cloud but not installed in any line.
     */
    private int speedUpgradePool;
    /**
     * 云端升级池: energy upgrades owned by the cloud but not installed in any line.
     */
    private int energyUpgradePool;
    /**
     * Configurable energy flow: receiving relay feeds the cloud processing pool.
     */
    private boolean energyFlowReceivingToCloud = true;
    /**
     * Configurable energy flow: receiving relay feeds line local pools directly.
     */
    private boolean energyFlowReceivingToLines = true;
    /**
     * Configurable energy flow: cloud pool drains into the sending relay (downloadable energy).
     */
    private boolean energyFlowCloudToSending = false;
    /**
     * Configurable energy flow: line local pools drain into the sending relay.
     */
    private boolean energyFlowLinesToSending = false;
    private double energy;
    private double maxEnergy;
    private double receivingMaxEnergy, sendingMaxEnergy;
    private boolean unlimitedEnergy;
    private int unlockedLines = 1;
    private int maxLines = 1;
    private int workRate = 1;
    private int maxMachineNodes = DEFAULT_MAX_MACHINE_NODES;
    private int simulatedHeight;
    private int lavaHeatCards;
    private boolean netherEnvironmentCard;
    /**
     * Staging inventories are unlimited; only channel buffers keep a real capacity.
     */
    private CloudworksInventory receiving = new CloudworksInventory(Long.MAX_VALUE);
    private CloudworksInventory transfer = new CloudworksInventory(Long.MAX_VALUE);
    private CloudworksInventory sending = new CloudworksInventory(Long.MAX_VALUE);
    /**
     * Intake rotation cursor; one engine rotation serves both warehouses (see transferCursor).
     */
    private int receivingCursor;
    /**
     * Mirror of {@link #receivingCursor}: the virtual engine rotates both warehouses together.
     */
    private int transferCursor;

    public CloudworksData(UUID owner) {
        this.owner = owner;
    }

    public static CloudworksData create(UUID owner) {
        mekanism.cloudworks.common.config.CloudworksConfig.Settings settings =
                mekanism.cloudworks.common.config.CloudworksConfig.settings();
        CloudworksData data = new CloudworksData(owner);
        data.setEnergy(0, settings.cloudCapacity, false);
        data.setWarehouseCapacities(settings.receivingCapacity, settings.sendingCapacity);
        data.setLimits(settings.initialLines, settings.initialLines, settings.initialWorkRate);
        data.setMaxMachineNodes(settings.initialMachineNodes);
        return data;
    }

    /** Runtime-private template copy, without NBT or codec resolution. */
    public CloudworksData copyForRuntime() {
        CloudworksData copy = new CloudworksData(owner);
        copy.enabled = enabled;
        copy.offlineProcessingUnlocked = offlineProcessingUnlocked;
        copy.recycleToTransfer = recycleToTransfer;
        copy.receivingEnergy = receivingEnergy;
        copy.sendingEnergy = sendingEnergy;
        copy.speedUpgradePool = speedUpgradePool;
        copy.energyUpgradePool = energyUpgradePool;
        copy.energyFlowReceivingToCloud = energyFlowReceivingToCloud;
        copy.energyFlowReceivingToLines = energyFlowReceivingToLines;
        copy.energyFlowCloudToSending = energyFlowCloudToSending;
        copy.energyFlowLinesToSending = energyFlowLinesToSending;
        copy.energy = energy;
        copy.maxEnergy = maxEnergy;
        copy.receivingMaxEnergy = receivingMaxEnergy;
        copy.sendingMaxEnergy = sendingMaxEnergy;
        copy.unlimitedEnergy = unlimitedEnergy;
        copy.unlockedLines = unlockedLines;
        copy.maxLines = maxLines;
        copy.workRate = workRate;
        copy.maxMachineNodes = maxMachineNodes;
        copy.simulatedHeight = simulatedHeight;
        copy.lavaHeatCards = lavaHeatCards;
        copy.netherEnvironmentCard = netherEnvironmentCard;
        copy.receiving = receiving.copy();
        copy.transfer = transfer.copy();
        copy.sending = sending.copy();
        copy.receivingCursor = receivingCursor;
        copy.transferCursor = transferCursor;
        copy.lineIds.addAll(lineIds);
        return copy;
    }

    private static CloudworksInventory unlimited(CloudworksInventory loaded) {
        loaded.setCapacity(Long.MAX_VALUE);
        return loaded;
    }

    public static CloudworksData read(NBTTagCompound tag) {
        CloudworksDataChecks.fields(tag, 8, "owner");
        CloudworksDataChecks.fields(tag, 1, "enabled", "offlineProcessingUnlocked", "unlimitedEnergy", "recycleToTransfer", "flowReceivingToCloud", "flowReceivingToLines", "flowCloudToSending", "flowLinesToSending");
        CloudworksDataChecks.fields(tag, 6, "energy", "maxEnergy", "receivingEnergy", "sendingEnergy");
        CloudworksDataChecks.fields(tag, 3, "unlockedLines", "maxLines", "workRate", "maxMachineNodes", "speedUpgradePool", "energyUpgradePool");
        CloudworksDataChecks.schema(tag, SCHEMA_VERSION);

        CloudworksData data = new CloudworksData(UUID.fromString(tag.getString("owner")));
        data.enabled = tag.getBoolean("enabled");
        data.offlineProcessingUnlocked = tag.getBoolean("offlineProcessingUnlocked");
        data.setEnergy(tag.getDouble("energy"), tag.getDouble("maxEnergy"), tag.getBoolean("unlimitedEnergy"));
        for (String key : new String[]{"receivingMaxEnergy", "sendingMaxEnergy"})
            if (tag.hasKey(key)) CloudworksDataChecks.field(tag, key, NBT.TAG_DOUBLE);
        double legacyCapacity = data.unlimitedEnergy ? Double.MAX_VALUE : data.maxEnergy;
        data.setWarehouseCapacities(tag.hasKey("receivingMaxEnergy") ? tag.getDouble("receivingMaxEnergy") : legacyCapacity,
                tag.hasKey("sendingMaxEnergy") ? tag.getDouble("sendingMaxEnergy") : legacyCapacity);
        if (tag.getInteger("unlockedLines") < 0 || tag.getInteger("maxLines") < tag.getInteger("unlockedLines")
                || tag.getInteger("workRate") < 1)
            throw new IllegalArgumentException("Invalid cloud limits");
        data.setLimits(tag.getInteger("unlockedLines"), tag.getInteger("maxLines"), tag.getInteger("workRate"));
        data.maxMachineNodes = tag.getInteger("maxMachineNodes");
        if (data.maxMachineNodes < 1) throw new IllegalArgumentException("Invalid node limit");
        data.simulatedHeight = tag.hasKey("simulatedHeight", NBT.TAG_INT)
                ? Math.max(0, Math.min(256, tag.getInteger("simulatedHeight"))) : 0;
        data.lavaHeatCards = tag.hasKey("lavaHeatCards", NBT.TAG_INT)
                ? Math.max(0, Math.min(7, tag.getInteger("lavaHeatCards"))) : 0;
        data.netherEnvironmentCard = tag.hasKey("netherEnvironmentCard", NBT.TAG_BYTE)
                && tag.getBoolean("netherEnvironmentCard");
        data.recycleToTransfer = !tag.hasKey("recycleToTransfer") || tag.getBoolean("recycleToTransfer");
        // Warehouse energy relays and flow gates: development phase, no old-save
        // compatibility — every written record carries all keys.
        data.receivingEnergy = tag.getDouble("receivingEnergy");
        data.sendingEnergy = tag.getDouble("sendingEnergy");
        data.speedUpgradePool = tag.getInteger("speedUpgradePool");
        data.energyUpgradePool = tag.getInteger("energyUpgradePool");
        if (!Double.isFinite(data.receivingEnergy) || data.receivingEnergy < 0
                || !Double.isFinite(data.sendingEnergy) || data.sendingEnergy < 0
                || data.speedUpgradePool < 0 || data.energyUpgradePool < 0)
            throw new IllegalArgumentException("Invalid cloud relay energy or upgrade pool");
        data.energyFlowReceivingToCloud = tag.getBoolean("flowReceivingToCloud");
        data.energyFlowReceivingToLines = tag.getBoolean("flowReceivingToLines");
        data.energyFlowCloudToSending = tag.getBoolean("flowCloudToSending");
        data.energyFlowLinesToSending = tag.getBoolean("flowLinesToSending");
        for (String inventory : new String[]{"receiving", "transfer", "sending"})
            if (tag.hasKey(inventory)) CloudworksDataChecks.field(tag, inventory, NBT.TAG_COMPOUND);
        if (tag.hasKey("receiving", NBT.TAG_COMPOUND))
            data.receiving = CloudworksInventory.read(tag.getCompoundTag("receiving"));
        if (tag.hasKey("transfer", NBT.TAG_COMPOUND))
            data.transfer = CloudworksInventory.read(tag.getCompoundTag("transfer"));
        if (tag.hasKey("sending", NBT.TAG_COMPOUND))
            data.sending = CloudworksInventory.read(tag.getCompoundTag("sending"));
        for (String cursor : new String[]{"receivingCursor", "transferCursor"}) {
            if (tag.hasKey(cursor)) CloudworksDataChecks.field(tag, cursor, NBT.TAG_INT);
            if (tag.getInteger(cursor) < 0) throw new IllegalArgumentException("Invalid warehouse cursor");
        }
        data.receivingCursor = tag.getInteger("receivingCursor");
        data.transferCursor = tag.getInteger("transferCursor");
        NBTTagList list = CloudworksDataChecks.list(tag, "lineIds", NBT.TAG_STRING);
        for (int i = 0; i < list.tagCount(); i++) {
            UUID id = UUID.fromString(list.getStringTagAt(i));
            if (data.lineIds.contains(id)) throw new IllegalArgumentException("Duplicate production line id");
            data.lineIds.add(id);
        }
        return data;
    }

    public UUID owner() {
        return owner;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean offlineProcessingUnlocked() {
        return offlineProcessingUnlocked;
    }

    public void setOfflineProcessingUnlocked(boolean value) {
        offlineProcessingUnlocked = value;
    }

    public double energy() {
        return energy;
    }

    public double maxEnergy() {
        return maxEnergy;
    }

    public boolean unlimitedEnergy() {
        return unlimitedEnergy;
    }

    public int unlockedLines() {
        return unlockedLines;
    }

    public int maxLines() {
        return maxLines;
    }

    public int workRate() {
        return workRate;
    }

    /**
     * Per-line machine node ceiling applied to every line of this owner independently.
     */
    public int maxMachineNodes() {
        return maxMachineNodes;
    }

    public int simulatedHeight() { return simulatedHeight; }
    public int lavaHeatCards() { return lavaHeatCards; }
    public boolean netherEnvironmentCard() { return netherEnvironmentCard; }

    public void setSimulatedHeight(int value) { simulatedHeight = Math.max(0, Math.min(256, value)); }
    public void setLavaHeatCards(int value) { lavaHeatCards = Math.max(0, Math.min(7, value)); }
    public void setNetherEnvironmentCard(boolean value) { netherEnvironmentCard = value; }

    public void setMaxMachineNodes(int value) {
        maxMachineNodes = Math.max(1, value);
    }

    /**
     * Recycle bin routing target: transfer warehouse (true) or sending warehouse (false).
     */
    public boolean recycleToTransfer() {
        return recycleToTransfer;
    }

    public void setRecycleToTransfer(boolean value) {
        recycleToTransfer = value;
    }

    /**
     * Receiving warehouse energy relay; its capacity follows the cloud pool.
     */
    public double receivingEnergy() {
        return receivingEnergy;
    }

    /**
     * Sending warehouse energy relay.
     */
    public double sendingEnergy() {
        return sendingEnergy;
    }

    public double receivingEnergyCapacity() { return unlimitedEnergy ? Double.MAX_VALUE : receivingMaxEnergy; }
    public double sendingEnergyCapacity() { return unlimitedEnergy ? Double.MAX_VALUE : sendingMaxEnergy; }

    public void setWarehouseCapacities(double receiving, double sending) {
        if (!Double.isFinite(receiving) || !Double.isFinite(sending) || receiving < 0 || sending < 0)
            throw new IllegalArgumentException("Invalid warehouse capacity");
        receivingMaxEnergy = receiving;
        sendingMaxEnergy = sending;
    }

    /** 云端升级池读数: speed (true) / energy (false). */
    public int upgradePool(boolean speed) {
        return speed ? speedUpgradePool : energyUpgradePool;
    }

    /** 云端升级池增减: 正数入库（回池），负数出库（安装扣减）; 返回新值. */
    public int addUpgradePool(boolean speed, int delta) {
        if (speed) speedUpgradePool = Math.addExact(speedUpgradePool, delta);
        else energyUpgradePool = Math.addExact(energyUpgradePool, delta);
        return speed ? speedUpgradePool : energyUpgradePool;
    }

    /**
     * Validates and clamps the warehouse relays against their independent capacities.
     */
    public void setWarehouseEnergy(double receiving, double sending) {
        if (!Double.isFinite(receiving) || !Double.isFinite(sending) || receiving < 0D || sending < 0D) {
            throw new IllegalArgumentException("Invalid warehouse energy");
        }
        this.receivingEnergy = Math.min(receiving, receivingEnergyCapacity());
        this.sendingEnergy = Math.min(sending, sendingEnergyCapacity());
    }

    public boolean energyFlowReceivingToCloud() {
        return energyFlowReceivingToCloud;
    }

    public boolean energyFlowReceivingToLines() {
        return energyFlowReceivingToLines;
    }

    public boolean energyFlowCloudToSending() {
        return energyFlowCloudToSending;
    }

    public boolean energyFlowLinesToSending() {
        return energyFlowLinesToSending;
    }

    public void setEnergyFlows(boolean receivingToCloud, boolean receivingToLines, boolean cloudToSending, boolean linesToSending) {
        this.energyFlowReceivingToCloud = receivingToCloud;
        this.energyFlowReceivingToLines = receivingToLines;
        this.energyFlowCloudToSending = cloudToSending;
        this.energyFlowLinesToSending = linesToSending;
    }

    public List<UUID> lineIds() {
        return lineIds;
    }

    public CloudworksInventory receiving() {
        return receiving;
    }

    public CloudworksInventory transfer() {
        return transfer;
    }

    public CloudworksInventory sending() {
        return sending;
    }

    public int receivingCursor() {
        return receivingCursor;
    }

    public int transferCursor() {
        return transferCursor;
    }

    public void setReceivingCursor(int value) {
        receivingCursor = Math.max(0, value);
    }

    public void setTransferCursor(int value) {
        transferCursor = Math.max(0, value);
    }

    public void setEnergy(double energy, double maxEnergy, boolean unlimited) {
        if (unlimited) {
            if (Double.isNaN(maxEnergy) || maxEnergy < 0D)
                throw new IllegalArgumentException("Invalid unlimited energy capacity");
            this.maxEnergy = maxEnergy;
            this.energy = 0D;
            this.unlimitedEnergy = true;
            return;
        }
        if (!Double.isFinite(energy) || !Double.isFinite(maxEnergy) || energy < 0D || maxEnergy < 0D)
            throw new IllegalArgumentException("Non-finite cloud energy");
        this.maxEnergy = Math.max(0, maxEnergy);
        this.energy = Math.max(0, Math.min(this.maxEnergy, energy));
        this.unlimitedEnergy = unlimited;
    }

    public void setLimits(int unlocked, int max, int workRate) {
        unlockedLines = Math.max(0, unlocked);
        maxLines = Math.max(unlockedLines, max);
        this.workRate = Math.max(1, workRate);
    }

    public void depositEnergy(double amount, double capacity, boolean unlimited) {
        if (unlimited) {
            unlimitedEnergy = true;
            energy = 0D;
            maxEnergy = Double.POSITIVE_INFINITY;
            return;
        }
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0D
                || Double.isNaN(capacity) || Double.isInfinite(capacity) || capacity <= 0D) {
            throw new IllegalArgumentException("Invalid energy upload");
        }
        if (unlimitedEnergy) return;
        // Keep fractional energy while guarding the finite NBT range.
        double nextCapacity = Math.min(Double.MAX_VALUE, maxEnergy + capacity);
        receivingMaxEnergy = Math.min(Double.MAX_VALUE, receivingMaxEnergy + capacity);
        sendingMaxEnergy = Math.min(Double.MAX_VALUE, sendingMaxEnergy + capacity);
        setEnergy(Math.min(Double.MAX_VALUE, energy + amount), nextCapacity, false);
    }

    /**
     * Full transactional serialization: configuration plus the three staging
     * inventories and their cursors. Snapshots, durable intents and in-memory
     * rollback capture this form; only the persistence layer splits it into
     * cloud.dat and the per-inventory files.
     */
    public NBTTagCompound write() {
        return writeProjection(receiving.write(), transfer.write(), sending.write());
    }

    /** Runtime projections reuse unchanged frozen warehouse tags. */
    public NBTTagCompound writeProjection(NBTTagCompound receiving, NBTTagCompound transfer, NBTTagCompound sending) {
        NBTTagCompound tag = writeCore();
        tag.setTag("receiving", receiving);
        tag.setTag("transfer", transfer);
        tag.setTag("sending", sending);
        tag.setInteger("receivingCursor", receivingCursor);
        tag.setInteger("transferCursor", transferCursor);
        return tag;
    }

    /**
     * cloud.dat content: configuration without the split staging inventories.
     */
    public NBTTagCompound writeCore() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", SCHEMA_VERSION);
        tag.setString("owner", owner.toString());
        tag.setBoolean("enabled", enabled);
        tag.setBoolean("offlineProcessingUnlocked", offlineProcessingUnlocked);
        tag.setDouble("energy", energy);
        tag.setDouble("maxEnergy", maxEnergy);
        tag.setDouble("receivingMaxEnergy", receivingMaxEnergy);
        tag.setDouble("sendingMaxEnergy", sendingMaxEnergy);
        tag.setBoolean("unlimitedEnergy", unlimitedEnergy);
        tag.setInteger("unlockedLines", unlockedLines);
        tag.setInteger("maxLines", maxLines);
        tag.setInteger("workRate", workRate);
        tag.setInteger("maxMachineNodes", maxMachineNodes);
        tag.setInteger("simulatedHeight", simulatedHeight);
        tag.setInteger("lavaHeatCards", lavaHeatCards);
        tag.setBoolean("netherEnvironmentCard", netherEnvironmentCard);
        tag.setBoolean("recycleToTransfer", recycleToTransfer);
        tag.setDouble("receivingEnergy", receivingEnergy);
        tag.setDouble("sendingEnergy", sendingEnergy);
        tag.setInteger("speedUpgradePool", speedUpgradePool);
        tag.setInteger("energyUpgradePool", energyUpgradePool);
        tag.setBoolean("flowReceivingToCloud", energyFlowReceivingToCloud);
        tag.setBoolean("flowReceivingToLines", energyFlowReceivingToLines);
        tag.setBoolean("flowCloudToSending", energyFlowCloudToSending);
        tag.setBoolean("flowLinesToSending", energyFlowLinesToSending);
        NBTTagList lines = new NBTTagList();
        for (UUID id : lineIds) lines.appendTag(new net.minecraft.nbt.NBTTagString(id.toString()));
        tag.setTag("lineIds", lines);
        return tag;
    }

    public void applyCore(NBTTagCompound tag) {
        CloudworksData next = read(tag);
        if (!owner.equals(next.owner)) throw new IllegalArgumentException("Cloud owner changed");
        enabled = next.enabled;
        offlineProcessingUnlocked = next.offlineProcessingUnlocked;
        recycleToTransfer = next.recycleToTransfer;
        receivingEnergy = next.receivingEnergy;
        sendingEnergy = next.sendingEnergy;
        speedUpgradePool = next.speedUpgradePool;
        energyUpgradePool = next.energyUpgradePool;
        energyFlowReceivingToCloud = next.energyFlowReceivingToCloud;
        energyFlowReceivingToLines = next.energyFlowReceivingToLines;
        energyFlowCloudToSending = next.energyFlowCloudToSending;
        energyFlowLinesToSending = next.energyFlowLinesToSending;
        energy = next.energy;
        maxEnergy = next.maxEnergy;
        receivingMaxEnergy = next.receivingMaxEnergy;
        sendingMaxEnergy = next.sendingMaxEnergy;
        unlimitedEnergy = next.unlimitedEnergy;
        unlockedLines = next.unlockedLines;
        maxLines = next.maxLines;
        workRate = next.workRate;
        maxMachineNodes = next.maxMachineNodes;
        simulatedHeight = next.simulatedHeight;
        lavaHeatCards = next.lavaHeatCards;
        netherEnvironmentCard = next.netherEnvironmentCard;
        lineIds.clear();
        lineIds.addAll(next.lineIds);
    }

    /**
     * receiving.dat content: the receiving inventory and its rotation cursor.
     */
    public NBTTagCompound writeReceiving() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", INVENTORY_SCHEMA_VERSION);
        tag.setTag("receiving", receiving.write());
        tag.setInteger("receivingCursor", receivingCursor);
        return tag;
    }

    /**
     * transfer.dat content: the transfer inventory and its rotation cursor.
     */
    public NBTTagCompound writeTransfer() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", INVENTORY_SCHEMA_VERSION);
        tag.setTag("transfer", transfer.write());
        tag.setInteger("transferCursor", transferCursor);
        return tag;
    }

    /**
     * sending.dat content: the sending inventory.
     */
    public NBTTagCompound writeSending() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", INVENTORY_SCHEMA_VERSION);
        tag.setTag("sending", sending.write());
        return tag;
    }

    /**
     * Loads one staging-inventory file; the unlimited capacity always wins over persisted bytes.
     */
    public void applyReceiving(NBTTagCompound tag) {
        CloudworksDataChecks.schema(tag, INVENTORY_SCHEMA_VERSION);
        CloudworksDataChecks.field(tag, "receiving", NBT.TAG_COMPOUND);
        CloudworksDataChecks.field(tag, "receivingCursor", NBT.TAG_INT);
        if (tag.getInteger("receivingCursor") < 0) throw new IllegalArgumentException("Invalid warehouse cursor");
        receiving = unlimited(CloudworksInventory.read(tag.getCompoundTag("receiving")));
        receivingCursor = Math.max(0, tag.getInteger("receivingCursor"));
    }

    /**
     * Loads one staging-inventory file; the unlimited capacity always wins over persisted bytes.
     */
    public void applyTransfer(NBTTagCompound tag) {
        CloudworksDataChecks.schema(tag, INVENTORY_SCHEMA_VERSION);
        CloudworksDataChecks.field(tag, "transfer", NBT.TAG_COMPOUND);
        CloudworksDataChecks.field(tag, "transferCursor", NBT.TAG_INT);
        if (tag.getInteger("transferCursor") < 0) throw new IllegalArgumentException("Invalid warehouse cursor");
        transfer = unlimited(CloudworksInventory.read(tag.getCompoundTag("transfer")));
        transferCursor = Math.max(0, tag.getInteger("transferCursor"));
    }

    /**
     * Loads one staging-inventory file; the unlimited capacity always wins over persisted bytes.
     */
    public void applySending(NBTTagCompound tag) {
        CloudworksDataChecks.schema(tag, INVENTORY_SCHEMA_VERSION);
        CloudworksDataChecks.field(tag, "sending", NBT.TAG_COMPOUND);
        sending = unlimited(CloudworksInventory.read(tag.getCompoundTag("sending")));
    }

    /**
     * Drops any inline staging inventories (legacy cloud.dat keys); warehouse files are authoritative.
     */
    public void resetStagingInventories() {
        receiving = new CloudworksInventory(Long.MAX_VALUE);
        transfer = new CloudworksInventory(Long.MAX_VALUE);
        sending = new CloudworksInventory(Long.MAX_VALUE);
        receivingCursor = 0;
        transferCursor = 0;
    }
}
