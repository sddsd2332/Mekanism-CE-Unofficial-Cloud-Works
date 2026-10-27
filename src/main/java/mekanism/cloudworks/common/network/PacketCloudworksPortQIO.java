package mekanism.cloudworks.common.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.content.CloudworksPortIo;
import mekanism.cloudworks.common.tile.prefab.TileEntityCloudworksPort;
import mekanism.common.content.qio.QIOFrequency;
import mekanism.common.content.qio.QIOResourceEntry;
import mekanism.common.inventory.container.MekanismTileContainer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

/** Only an open, accessible port container may configure its QIO resource markers. */
public final class PacketCloudworksPortQIO {
    public enum Operation { REFRESH, ADD_FILTER, REMOVE_FILTER, RESOURCES }
    public static final int PAGE_SIZE = 128;
    private static final int MAX_BYTES = 1024 * 1024;

    private PacketCloudworksPortQIO() {
    }

    public static final class Request implements IMessage {
        private int window, offset;
        private Operation operation;
        private QIOResourceDescriptor resource;
        private boolean valid;

        public Request() {
        }

        public Request(int window, Operation operation, QIOResourceDescriptor resource, int offset) {
            this.window = window;
            this.operation = operation;
            this.resource = resource;
            this.offset = offset;
            valid = true;
        }
        public boolean valid() { return valid; }

        @Override public void toBytes(ByteBuf buf) {
            PacketBuffer data = new PacketBuffer(buf);
            data.writeInt(window);
            data.writeByte(operation.ordinal());
            data.writeInt(offset);
            if (operation == Operation.ADD_FILTER || operation == Operation.REMOVE_FILTER)
                CloudworksLineDraftCodec.resource(data, resource);
        }

        @Override public void fromBytes(ByteBuf buf) {
            valid = false;
            try {
                if (buf.readableBytes() > MAX_BYTES) return;
                PacketBuffer data = new PacketBuffer(buf);
                window = data.readInt();
                int code = data.readUnsignedByte();
                if (code >= Operation.values().length) return;
                operation = Operation.values()[code];
                offset = data.readInt();
                if (operation == Operation.ADD_FILTER || operation == Operation.REMOVE_FILTER)
                    resource = CloudworksLineDraftCodec.resource(data);
                valid = window >= 0 && offset >= 0 && !data.isReadable();
            } catch (RuntimeException malformed) {
                valid = false;
            }
        }
    }

    public static final class Handler implements IMessageHandler<Request, IMessage> {
        @Override public IMessage onMessage(Request request, MessageContext context) {
            if (!request.valid) return null;
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (!(player.openContainer instanceof MekanismTileContainer<?> container)
                        || container.windowId != request.window || !container.canInteractWith(player)
                        || !(container.getTileEntity() instanceof TileEntityCloudworksPort port)
                        || !port.hasQIOUpgrade()) return;
                boolean changed = switch (request.operation) {
                    case ADD_FILTER -> port.addQIOFilter(request.resource);
                    case REMOVE_FILTER -> port.removeQIOFilter(request.resource);
                    default -> false;
                };
                List<QIOResourceDescriptor> choices = new ArrayList<>();
                int offset = -1, total = 0;
                if (request.operation == Operation.RESOURCES) {
                    offset = request.offset;
                    QIOFrequency frequency = port.getQIOFrequencyForTransfer();
                    if (frequency != null) {
                        for (QIOResourceEntry entry : frequency.getResourceEntries()) {
                            QIOResourceDescriptor descriptor = entry.getDescriptor();
                            if (CloudworksPortIo.isSupportedDownloadResource(descriptor)) {
                                if (total >= offset && choices.size() < PAGE_SIZE) choices.add(descriptor);
                                total++;
                            }
                        }
                    }
                    offset = Math.min(offset, total);
                }
                CloudworksNetwork.CHANNEL.sendTo(new State(request.window, port.getQIOFilters(),
                        choices, offset, total), player);
                if (changed) {
                    for (EntityPlayer viewer : port.playersUsing) {
                        if (viewer != player && viewer instanceof EntityPlayerMP serverViewer
                                && viewer.openContainer instanceof MekanismTileContainer<?> other
                                && other.getTileEntity() == port && other.canInteractWith(viewer))
                            CloudworksNetwork.CHANNEL.sendTo(new State(other.windowId, port.getQIOFilters(),
                                    Collections.emptyList(), -1, 0), serverViewer);
                    }
                }
            });
            return null;
        }
    }

    public static final class State implements IMessage {
        private int window, offset, total;
        private List<QIOResourceDescriptor> filters = Collections.emptyList(), choices = Collections.emptyList();
        private boolean valid;

        public State() {
        }

        public State(int window, List<QIOResourceDescriptor> filters,
                List<QIOResourceDescriptor> choices, int offset, int total) {
            this.window = window;
            this.filters = new ArrayList<>(filters);
            this.choices = new ArrayList<>(choices);
            this.offset = offset;
            this.total = total;
        }
        public boolean valid() { return valid; }

        @Override public void toBytes(ByteBuf buf) {
            PacketBuffer data = new PacketBuffer(buf);
            data.writeInt(window);
            data.writeInt(offset);
            data.writeInt(total);
            writeList(data, filters);
            writeList(data, choices);
        }

        @Override public void fromBytes(ByteBuf buf) {
            valid = false;
            try {
                if (buf.readableBytes() > MAX_BYTES) return;
                PacketBuffer data = new PacketBuffer(buf);
                window = data.readInt();
                offset = data.readInt();
                total = data.readInt();
                filters = readList(data, data.readableBytes());
                choices = readList(data, PAGE_SIZE);
                valid = window >= 0 && offset >= -1 && total >= 0
                        && (offset < 0 ? choices.isEmpty() : offset <= total && choices.size() <= total - offset)
                        && !data.isReadable();
            } catch (RuntimeException malformed) {
                valid = false;
            }
        }

        private static void writeList(PacketBuffer data, List<QIOResourceDescriptor> resources) {
            data.writeVarInt(resources.size());
            for (QIOResourceDescriptor resource : resources) CloudworksLineDraftCodec.resource(data, resource);
        }

        private static List<QIOResourceDescriptor> readList(PacketBuffer data, int maximum) {
            int count = CloudworksLineDraftCodec.count(data, maximum);
            List<QIOResourceDescriptor> resources = new ArrayList<>();
            for (int index = 0; index < count; index++) resources.add(CloudworksLineDraftCodec.resource(data));
            return resources;
        }
    }

    public static final class StateHandler implements IMessageHandler<State, IMessage> {
        @SideOnly(Side.CLIENT)
        private static void apply(State state) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                EntityPlayer player = Minecraft.getMinecraft().player;
                if (player != null && player.openContainer instanceof MekanismTileContainer<?> container
                        && container.windowId == state.window
                        && container.getTileEntity() instanceof TileEntityCloudworksPort port)
                    port.applyQIOState(state.filters, state.choices, state.offset, state.total);
            });
        }

        @Override public IMessage onMessage(State state, MessageContext context) {
            if (state.valid) apply(state);
            return null;
        }
    }
}
