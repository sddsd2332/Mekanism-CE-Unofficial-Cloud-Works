package mekanism.cloudworks.common.content;

import java.util.List;
import java.util.UUID;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import mekanism.api.inventory.IInventorySlot;
import mekanism.cloudworks.common.data.CloudworksPortRecord;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import net.minecraft.nbt.NBTTagCompound;

/** QIO-style UUID mount; world/block NBT is a reference, never another resource authority. */
public final class CloudworksPortBinding {
    private UUID id, storage;
    private CloudworksPortRecord record;
    private boolean dirty, restoring, mounting;
    private long lastSave;
    private long version, capturedVersion = -1;
    private List<IInventorySlot> liveSlots;
    private List<CloudworksHybridTank> liveTanks;
    private DoubleSupplier liveEnergy;

    public boolean active() {
        return record != null && CloudworksPlayerDataManager.INSTANCE.portMounted(record.owner, id, this);
    }

    public void changed() {
        if (!restoring) {
            dirty = true;
            version++;
        }
    }

    public boolean mount(UUID owner, boolean input, List<IInventorySlot> slots,
            List<CloudworksHybridTank> tanks, DoubleSupplier energy, DoubleConsumer setEnergy) {
        if (owner == null || mounting) return false;
        if (active()) return true;
        CloudworksPlayerDataManager manager = CloudworksPlayerDataManager.INSTANCE;
        mounting = true;
        try {
            boolean fresh = id == null;
            UUID currentStorage = manager.storageId();
            UUID candidate = fresh ? UUID.randomUUID() : id;
            if (!fresh && !currentStorage.equals(storage)) return false;
            record = manager.mountPort(owner, candidate, input, this, fresh);
            if (record == null) return false;
            id = candidate;
            storage = currentStorage;
            liveSlots = slots;
            liveTanks = tanks;
            liveEnergy = energy;
            restore(slots, tanks, setEnergy);
            dirty = fresh;
            capturedVersion = version;
            return true;
        } catch (RuntimeException unavailable) {
            if (record != null) manager.unmountPort(record.owner, record.id, this);
            record = null;
            return false;
        } finally { mounting = false; }
    }

    public int transfer(List<IInventorySlot> slots, List<CloudworksHybridTank> tanks,
            DoubleSupplier energy, double maxEnergy, DoubleConsumer setEnergy, boolean[] grace) {
        if (!active()) throw new IllegalStateException("Port is not mounted");
        restoring = true;
        try {
            int blocked = CloudworksPlayerDataManager.INSTANCE.transferPort(record.owner, id, this,
                    slots, tanks, energy.getAsDouble(), maxEnergy, setEnergy, grace);
            dirty = false;
            capturedVersion = version;
            return blocked;
        } finally { restoring = false; }
    }

    private void restore(List<IInventorySlot> slots, List<CloudworksHybridTank> tanks, DoubleConsumer setEnergy) {
        restoring = true;
        try { record.restore(slots, tanks); setEnergy.accept(record.energy()); }
        finally { restoring = false; }
    }

    public void save(List<IInventorySlot> slots, List<CloudworksHybridTank> tanks, DoubleSupplier energy, long tick) {
        if (!active() || !dirty || tick - lastSave < 200) return;
        saveNow(slots, tanks, energy);
        lastSave = tick;
    }

    public void saveNow(List<IInventorySlot> slots, List<CloudworksHybridTank> tanks, DoubleSupplier energy) {
        if (!active() || !dirty) return;
        CloudworksPlayerDataManager.INSTANCE.savePort(record.owner, id, this,
                CloudworksPortRecord.capture(id, record.owner, record.input, slots, tanks, energy.getAsDouble()));
        dirty = false;
        capturedVersion = version;
    }

    public void flush() {
        if (liveSlots != null) saveNow(liveSlots, liveTanks, liveEnergy);
    }

    public void detach(List<IInventorySlot> slots, List<CloudworksHybridTank> tanks, DoubleSupplier energy) {
        try {
            if (active()) {
                try { saveNow(slots, tanks, energy); }
                finally { CloudworksPlayerDataManager.INSTANCE.unmountPort(record.owner, id, this); }
            }
        } finally {
            record = null;
            liveSlots = null;
            liveTanks = null;
            liveEnergy = null;
        }
    }

    public void refresh(List<IInventorySlot> slots, List<CloudworksHybridTank> tanks, DoubleSupplier energy) {
        if (active() && dirty && capturedVersion != version) {
            CloudworksPlayerDataManager.INSTANCE.capturePort(record.owner, id, this,
                    CloudworksPortRecord.capture(id, record.owner, record.input, slots, tanks, energy.getAsDouble()));
            capturedVersion = version;
        }
    }

    public void write(NBTTagCompound tag) {
        if (id != null && storage != null) {
            tag.setString("cwPortId", id.toString());
            tag.setString("cwStorageId", storage.toString());
        }
    }

    public void read(NBTTagCompound tag) {
        if (record != null) throw new IllegalStateException("Cannot replace a mounted port identity");
        id = tag.hasKey("cwPortId", 8) ? UUID.fromString(tag.getString("cwPortId")) : null;
        storage = tag.hasKey("cwStorageId", 8) ? UUID.fromString(tag.getString("cwStorageId")) : null;
        if (id != null && storage == null) throw new IllegalArgumentException("Missing port storage scope");
    }
}
