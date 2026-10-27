package mekanism.cloudworks.common.data;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;

import java.util.*;
import mekanism.cloudworks.common.config.CloudworksConfig;
import mekanism.cloudworks.common.service.CloudworksOutputMultiplier;
import net.minecraft.nbt.NBTTagString;

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
     * 云端自定义升级池: 命名空间类型 → 数量; 未知集成条目原样保留.
     */
    private CloudworksUpgradeSet customUpgradePool = new CloudworksUpgradeSet();
    /**
     * 云级效果状态: 通用通道卡片效果的等级表; 内置效果仍用专有字段(见 NBT 文档映射表).
     */
    private CloudworksEffectState cloudEffects = new CloudworksEffectState();
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
    private int outputMultiplierUnits = CloudworksOutputMultiplier.SCALE;
    private int maxMachineNodes = DEFAULT_MAX_MACHINE_NODES;
    private int warehouseCapMultiplier = 1;
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
        CloudworksConfig.Settings settings =
                CloudworksConfig.settings();
        CloudworksData data = new CloudworksData(owner);
        data.setEnergy(0, settings.cloudCapacity, false);
        data.setWarehouseCapacities(settings.receivingCapacity, settings.sendingCapacity);
        data.setLimits(settings.initialLines, settings.initialLines, settings.initialWorkRate);
        data.setMaxMachineNodes(settings.initialMachineNodes);
        return data;
    }

    /**
     * Runtime-private template copy, without NBT or codec resolution.
     */
    public CloudworksData copyForRuntime() {
        CloudworksData copy = new CloudworksData(owner);
        copy.enabled = enabled;
        copy.offlineProcessingUnlocked = offlineProcessingUnlocked;
        copy.recycleToTransfer = recycleToTransfer;
        copy.receivingEnergy = receivingEnergy;
        copy.sendingEnergy = sendingEnergy;
        copy.speedUpgradePool = speedUpgradePool;
        copy.energyUpgradePool = energyUpgradePool;
        copy.customUpgradePool = customUpgradePool.copy();
        copy.cloudEffects = cloudEffects.copy();
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
        copy.outputMultiplierUnits = outputMultiplierUnits;
        copy.maxMachineNodes = maxMachineNodes;
        copy.warehouseCapMultiplier = warehouseCapMultiplier;
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
        CloudworksDataChecks.fields(tag, 1, "enabled", "offlineProcessingUnlocked", "unlimitedEnergy",
                "flowReceivingToCloud", "flowReceivingToLines", "flowCloudToSending", "flowLinesToSending");
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
        if (tag.hasKey("outputMultiplierUnits")) {
            CloudworksDataChecks.field(tag, "outputMultiplierUnits", NBT.TAG_INT);
            data.setOutputMultiplierUnits(tag.getInteger("outputMultiplierUnits"));
        }
        data.maxMachineNodes = tag.getInteger("maxMachineNodes");
        if (data.maxMachineNodes < 1) throw new IllegalArgumentException("Invalid node limit");
        data.warehouseCapMultiplier = tag.hasKey("warehouseCapMultiplier", NBT.TAG_INT)
                ? tag.getInteger("warehouseCapMultiplier") : 1;
        if (data.warehouseCapMultiplier < 1) throw new IllegalArgumentException("Invalid warehouse cap multiplier");
        data.recycleToTransfer = !tag.hasKey("recycleToTransfer") || tag.getBoolean("recycleToTransfer");
        // Warehouse energy relays and flow gates: development phase, no old-save
        // compatibility — every written record carries all keys.
        data.receivingEnergy = tag.getDouble("receivingEnergy");
        data.sendingEnergy = tag.getDouble("sendingEnergy");
        data.speedUpgradePool = tag.getInteger("speedUpgradePool");
        data.energyUpgradePool = tag.getInteger("energyUpgradePool");
        data.customUpgradePool = CloudworksUpgradeSet.read(tag, "customUpgradePool");
        data.cloudEffects = CloudworksEffectState.read(tag, "cloudEffects");
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

    public int warehouseCapMultiplier() {
        return warehouseCapMultiplier;
    }

    public void setWarehouseCapMultiplier(int value) {
        if (value < 1) throw new IllegalArgumentException("Invalid warehouse cap multiplier");
        warehouseCapMultiplier = value;
    }

    public long effectiveWarehouseItemCap() {
        return Math.multiplyExact(
                CloudworksConfig.settings().warehouseItemTypeCap,
                (long) warehouseCapMultiplier);
    }

    public int workRate() {
        return workRate;
    }

    public int outputMultiplierUnits() {
        return outputMultiplierUnits;
    }

    public void setOutputMultiplierUnits(int units) {
        outputMultiplierUnits = CloudworksOutputMultiplier.validate(units);
    }

    /**
     * Per-line machine node ceiling applied to every line of this owner independently.
     */
    public int maxMachineNodes() {
        return maxMachineNodes;
    }

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

    public double receivingEnergyCapacity() {
        return unlimitedEnergy ? Double.MAX_VALUE : receivingMaxEnergy;
    }

    public double sendingEnergyCapacity() {
        return unlimitedEnergy ? Double.MAX_VALUE : sendingMaxEnergy;
    }

    public void setWarehouseCapacities(double receiving, double sending) {
        if (!Double.isFinite(receiving) || !Double.isFinite(sending) || receiving < 0 || sending < 0)
            throw new IllegalArgumentException("Invalid warehouse capacity");
        receivingMaxEnergy = receiving;
        sendingMaxEnergy = sending;
    }

    /**
     * 云端升级池读数: speed (true) / energy (false).
     */
    public int upgradePool(boolean speed) {
        return speed ? speedUpgradePool : energyUpgradePool;
    }

    /**
     * 云端升级池增减: 正数入库（回池），负数出库（安装扣减）; 返回新值.
     */
    public int addUpgradePool(boolean speed, int delta) {
        if (speed) speedUpgradePool = Math.addExact(speedUpgradePool, delta);
        else energyUpgradePool = Math.addExact(energyUpgradePool, delta);
        return speed ? speedUpgradePool : energyUpgradePool;
    }

    /**
     * 云端自定义升级池: 命名空间类型 → 数量; 未知集成条目原样保留.
     */
    public CloudworksUpgradeSet customUpgradePool() {
        return customUpgradePool;
    }

    /**
     * 通用通道云级效果等级表; 内置效果专有字段之外的唯一载体.
     */
    public CloudworksEffectState cloudEffects() {
        return cloudEffects;
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

    // 8.4: gate accessors delegate to the unified CloudworksFlowGates view;
    // the stored fields above stay the single truth (schema unchanged).
    public boolean energyFlowReceivingToCloud() {
        return flowGates.gate(CloudworksFlowGate.RECEIVING_TO_CLOUD);
    }

    public boolean energyFlowReceivingToLines() {
        return flowGates.gate(CloudworksFlowGate.RECEIVING_TO_LINES);
    }

    public boolean energyFlowCloudToSending() {
        return flowGates.gate(CloudworksFlowGate.CLOUD_TO_SENDING);
    }

    public boolean energyFlowLinesToSending() {
        return flowGates.gate(CloudworksFlowGate.LINES_TO_SENDING);
    }

    public void setEnergyFlows(boolean receivingToCloud, boolean receivingToLines, boolean cloudToSending, boolean linesToSending) {
        flowGates.set(CloudworksFlowGate.RECEIVING_TO_CLOUD, receivingToCloud);
        flowGates.set(CloudworksFlowGate.RECEIVING_TO_LINES, receivingToLines);
        flowGates.set(CloudworksFlowGate.CLOUD_TO_SENDING, cloudToSending);
        flowGates.set(CloudworksFlowGate.LINES_TO_SENDING, linesToSending);
    }

    /**
     * Unified gate view over this cloud's four flow gates.
     */
    public CloudworksFlowGates flowGates() {
        return flowGates;
    }

    private final CloudworksFlowGates flowGates = new CloudworksFlowGates() {
        @Override
        public CloudworksFlowGate.Scope scope() {
            return CloudworksFlowGate.Scope.CLOUD;
        }

        @Override
        public boolean gate(CloudworksFlowGate gate) {
            switch (gate) {
                case RECEIVING_TO_CLOUD:
                    return energyFlowReceivingToCloud;
                case RECEIVING_TO_LINES:
                    return energyFlowReceivingToLines;
                case CLOUD_TO_SENDING:
                    return energyFlowCloudToSending;
                case LINES_TO_SENDING:
                    return energyFlowLinesToSending;
                default:
                    throw new IllegalArgumentException("Not a cloud flow gate: " + gate);
            }
        }

        @Override
        public void set(CloudworksFlowGate gate, boolean value) {
            switch (gate) {
                case RECEIVING_TO_CLOUD:
                    energyFlowReceivingToCloud = value;
                    break;
                case RECEIVING_TO_LINES:
                    energyFlowReceivingToLines = value;
                    break;
                case CLOUD_TO_SENDING:
                    energyFlowCloudToSending = value;
                    break;
                case LINES_TO_SENDING:
                    energyFlowLinesToSending = value;
                    break;
                default:
                    throw new IllegalArgumentException("Not a cloud flow gate: " + gate);
            }
        }
    };

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

    /**
     * Runtime projections reuse unchanged frozen warehouse tags.
     */
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
        tag.setInteger("outputMultiplierUnits", outputMultiplierUnits);
        tag.setInteger("maxMachineNodes", maxMachineNodes);
        tag.setInteger("warehouseCapMultiplier", warehouseCapMultiplier);
        tag.setBoolean("recycleToTransfer", recycleToTransfer);
        tag.setDouble("receivingEnergy", receivingEnergy);
        tag.setDouble("sendingEnergy", sendingEnergy);
        tag.setInteger("speedUpgradePool", speedUpgradePool);
        tag.setInteger("energyUpgradePool", energyUpgradePool);
        customUpgradePool.write(tag, "customUpgradePool");
        cloudEffects.write(tag, "cloudEffects");
        tag.setBoolean("flowReceivingToCloud", energyFlowReceivingToCloud);
        tag.setBoolean("flowReceivingToLines", energyFlowReceivingToLines);
        tag.setBoolean("flowCloudToSending", energyFlowCloudToSending);
        tag.setBoolean("flowLinesToSending", energyFlowLinesToSending);
        NBTTagList lines = new NBTTagList();
        for (UUID id : lineIds) lines.appendTag(new NBTTagString(id.toString()));
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
        customUpgradePool = next.customUpgradePool.copy();
        cloudEffects = next.cloudEffects.copy();
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
        outputMultiplierUnits = next.outputMultiplierUnits;
        maxMachineNodes = next.maxMachineNodes;
        warehouseCapMultiplier = next.warehouseCapMultiplier;
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
