package mekanism.cloudworks.common.network.data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksChannelNode;
import net.minecraft.item.ItemStack;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.NBTTagCompound;

/** Editable configuration only. Runtime buffers, progress and energy never come from the client. */
public final class CloudworksLineDraft {
    public static final int MAX_MARKERS = 25;
    public static final int MAX_CUSTOM_UPGRADE_ID_CHARS = 128;
    public UUID line;
    public String name = "";
    public boolean enabled, receivingFlow, sendingFlow, explicitRouting, runWhileOwnerOffline;
    public int speed, energy, maxSpeed, maxEnergy;
    public int maxNodes = 4096;
    public long maxMachinesPerNode = 1024;
    public int nodeBufferMultiplier = 1;
    public final List<QIOResourceDescriptor> markers = new ArrayList<>();
    public final List<Machine> machines = new ArrayList<>();
    public final List<Auxiliary> auxiliaries = new ArrayList<>();
    public final List<Channel> channels = new ArrayList<>();
    public final List<Wire> links = new ArrayList<>();

    public static final class Machine {
        public UUID id;
        public ItemStack type;
        public int x, y;
        public long count;
        public long required, missing;
        public boolean active = true;
        public String recipeType = "";
        public final java.util.List<mekanism.cloudworks.common.data.CloudworksOreRule> oreRules = new java.util.ArrayList<>();
        public int fusionInjectionRate = 2;
        /** Zero automatically shares the line remainder for that upgrade type. */
            /** Custom-type assignments per namespaced id (8.5-B5); counts installed on this node. */
        public final Map<String, Long> assignedCustom = new LinkedHashMap<>();
        public UUID waiting;
        /** Server-owned port preview for an unfilled blueprint; never an installed upgrade. */
        public String waitingMechanism = "";

        public Machine(UUID id, ItemStack type, int x, int y, long count) {
            this.id = id;
            this.type = type.copy();
            this.x = x;
            this.y = y;
            this.count = count;
        }

        /** Count assigned for one custom type; 0 when absent. */
        public long assignedCustom(String typeId) {
            Long value = assignedCustom.get(typeId);
            return value == null ? 0L : value;
        }

        /** Sets one custom assignment; zero or negative removes the entry. */
        public void assignedCustom(String typeId, long value) {
            if (typeId == null || typeId.length() > MAX_CUSTOM_UPGRADE_ID_CHARS)
                throw new IllegalArgumentException("Invalid custom upgrade assignment id");
            if (value <= 0L) assignedCustom.remove(typeId);
            else assignedCustom.put(typeId, value);
        }
    }

    public static final class Channel {
        public UUID id;
        public CloudworksChannelNode.Mode mode;
        public String archetypeId = "";
        public int x, y;
        public boolean active = true;
        public UUID waiting;
        /** Special-node configuration (9.1); empty for plain channels. */
        public NBTTagCompound config = new NBTTagCompound();
        public final List<Resource> resources = new ArrayList<>();

        public Channel(UUID id, CloudworksChannelNode.Mode mode, int x, int y) {
            this(id, mode, "", x, y);
        }

        public Channel(UUID id, CloudworksChannelNode.Mode mode, String archetypeId, int x, int y) {
            this.id = id;
            this.mode = mode;
            this.archetypeId = archetypeId == null ? "" : archetypeId;
            this.x = x;
            this.y = y;
        }
    }

    public static final class Auxiliary {
        public UUID id;
        public ItemStack type;
        public int x, y, range;
        public boolean active = true;
        public final List<UUID> targets = new ArrayList<>();
        public final List<Resource> resources = new ArrayList<>();

        public Auxiliary(UUID id, ItemStack type, int x, int y) {
            this.id = id;
            this.type = type.copy();
            this.type.setCount(1);
            this.x = x;
            this.y = y;
        }
    }

    public static final class Resource {
        public final QIOResourceDescriptor descriptor;
        public long amount;

        public Resource(QIOResourceDescriptor descriptor, long amount) {
            this.descriptor = descriptor;
            this.amount = amount;
        }
    }

    public static final class Wire {
        public UUID id, source, target;
        public String sourcePort, targetPort;
        public boolean enabled = true;
        public int priority;
        public double ratio = 1;
        public final List<QIOResourceDescriptor> filters = new ArrayList<>();

        public Wire(UUID id, UUID source, String sourcePort, UUID target, String targetPort) {
            this.id = id;
            this.source = source;
            this.sourcePort = sourcePort;
            this.target = target;
            this.targetPort = targetPort;
        }
    }
}
