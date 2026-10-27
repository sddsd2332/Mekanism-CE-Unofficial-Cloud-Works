package mekanism.cloudworks.common.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import mekanism.cloudworks.common.data.CloudworksInventory;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Complete immutable line publication. Cold configuration belongs to the captured
 * epoch, and every dynamic value belongs to this checkpoint. No runtime, worker
 * template, live array or predecessor checkpoint is retained. NBT is constructed
 * only at a reader boundary; the manager caches that reader-owned tag until the
 * next publication. Metadata checks never materialize machines.
 */
public final class CloudworksRuntimeLineSnapshot {
    public static final AtomicLong DEBUG_MATERIALIZATIONS = new AtomicLong();
    public static volatile String DEBUG_LAST_MATERIALIZATION_THREAD = "";
    private final CloudworksPlayerDataManager.VirtualSnapshot.LineBaseline baseline;
    private final UUID id, owner;
    private final boolean enabled, explicitRouting, runWhileOwnerOffline;
    private final double energy, capacity;
    private final CloudworksRuntimeObjectSnapshot<MachineNodeData.RuntimeProjection> machines;
    private final CloudworksRuntimeObjectSnapshot<InventoryValue> channels;
    private final CloudworksRuntimeObjectSnapshot<AuxiliaryValue> auxiliaries;
    private final double[] linkRemainders;

    CloudworksRuntimeLineSnapshot(CloudworksPlayerDataManager.VirtualSnapshot origin, ProductionLineData line,
            CloudworksRuntimeObjectSnapshot<MachineNodeData.RuntimeProjection> machines,
            CloudworksRuntimeObjectSnapshot<InventoryValue> channels,
            CloudworksRuntimeObjectSnapshot<AuxiliaryValue> auxiliaries, double[] linkRemainders) {
        baseline = origin.lineBaseline(line.lineId());
        id = line.lineId(); owner = line.owner();
        enabled = line.enabled(); explicitRouting = line.explicitRouting();
        runWhileOwnerOffline = line.runWhileOwnerOffline();
        energy = line.energy(); capacity = line.maxEnergy();
        this.machines = machines; this.channels = channels; this.auxiliaries = auxiliaries;
        this.linkRemainders = linkRemainders;
    }

    public UUID lineId() { return id; }
    public UUID owner() { return owner; }
    public boolean enabled() { return enabled; }
    public boolean explicitRouting() { return explicitRouting; }
    public boolean runWhileOwnerOffline() { return runWhileOwnerOffline; }

    public NBTTagCompound overview() {
        NBTTagCompound tag = baseline.copyOverview();
        tag.setDouble("energy", energy); tag.setDouble("maxEnergy", capacity);
        return tag;
    }

    public NBTTagList configuredChannels() { return baseline.copyChannels(); }

    /** Returns a fresh caller-owned projection, without native callbacks or live state access. */
    public NBTTagCompound write() {
        NBTTagCompound tag = baseline.copyTag();
        tag.removeTag("generation");
        tag.setDouble("energy", energy); tag.setDouble("maxEnergy", capacity);
        NBTTagList nodes = tag.getTagList("machines", 10);
        if (nodes.tagCount() != machines.size()) throw new IllegalStateException("Runtime machine snapshot shape changed");
        for (int node = 0; node < machines.size(); node++) {
            MachineNodeData.RuntimeProjection value = machines.get(node);
            if (value != null) value.writeOnto(nodes.getCompoundTagAt(node));
        }
        NBTTagList channelTags = tag.getTagList("channels", 10);
        if (channelTags.tagCount() != channels.size()) throw new IllegalStateException("Runtime channel snapshot shape changed");
        for (int node = 0; node < channels.size(); node++)
            channelTags.getCompoundTagAt(node).setTag("buffer", channels.get(node).write());
        NBTTagList auxiliaryTags = tag.getTagList("auxiliaries", 10);
        if (auxiliaryTags.tagCount() != auxiliaries.size()) throw new IllegalStateException("Runtime auxiliary snapshot shape changed");
        for (int node = 0; node < auxiliaries.size(); node++) auxiliaries.get(node).writeOnto(auxiliaryTags.getCompoundTagAt(node));
        NBTTagList links = tag.getTagList("links", 10);
        if (links.tagCount() != linkRemainders.length) throw new IllegalStateException("Runtime link snapshot shape changed");
        for (int link = 0; link < linkRemainders.length; link++) links.getCompoundTagAt(link).setDouble("ratioRemainder", linkRemainders[link]);
        DEBUG_MATERIALIZATIONS.incrementAndGet();
        DEBUG_LAST_MATERIALIZATION_THREAD = Thread.currentThread().getName();
        return tag;
    }

    static final class InventoryValue {
        private final CloudworksInventory inventory;
        InventoryValue(CloudworksInventory inventory) { this.inventory = inventory.copy(); }
        NBTTagCompound write() { return inventory.write(); }
    }

    static final class AuxiliaryValue {
        private final InventoryValue input;
        private final Map<String, Double> costs;
        AuxiliaryValue(CloudworksInventory input, Map<String, Double> costs) {
            this.input = new InventoryValue(input);
            this.costs = Collections.unmodifiableMap(new LinkedHashMap<>(costs));
        }
        void writeOnto(NBTTagCompound tag) {
            tag.setTag("input", input.write());
            NBTTagCompound remainders = new NBTTagCompound();
            costs.forEach(remainders::setDouble);
            tag.setTag("costRemainders", remainders);
        }
    }
}
