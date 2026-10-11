package mekanism.cloudworks.common.network.data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksChannelNode;
import net.minecraft.item.ItemStack;

/** Editable configuration only. Runtime buffers, progress and energy never come from the client. */
public final class CloudworksLineDraft {
    public static final int MAX_MARKERS = 25;
    public UUID line;
    public String name = "";
    public boolean enabled, receivingFlow, sendingFlow, explicitRouting, runWhileOwnerOffline;
    public int speed, energy, maxSpeed, maxEnergy;
    public int maxNodes = 4096;
    public long maxMachinesPerNode = 1024;
    public final List<QIOResourceDescriptor> markers = new ArrayList<>();
    public final List<Machine> machines = new ArrayList<>();
    public final List<Channel> channels = new ArrayList<>();
    public final List<Wire> links = new ArrayList<>();

    public static final class Machine {
        public UUID id;
        public ItemStack type;
        public int x, y, dimension;
        public long count;
        public long required, missing;
        public boolean active = true;
        public UUID waiting;

        public Machine(UUID id, ItemStack type, int x, int y, long count) {
            this.id = id;
            this.type = type.copy();
            this.x = x;
            this.y = y;
            this.count = count;
        }
    }

    public static final class Channel {
        public UUID id;
        public CloudworksChannelNode.Mode mode;
        public int x, y;
        public boolean active = true;
        public UUID waiting;
        public final List<Resource> resources = new ArrayList<>();

        public Channel(UUID id, CloudworksChannelNode.Mode mode, int x, int y) {
            this.id = id;
            this.mode = mode;
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
