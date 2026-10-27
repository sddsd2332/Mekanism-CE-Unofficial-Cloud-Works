package mekanism.cloudworks.common.network;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.inventory.container.ContainerCloudworksTeamRecovery;
import mekanism.cloudworks.common.tile.TileEntityCloudworksTeamRecovery;
import mekanism.common.content.qio.QIOAmount;
import mekanism.common.content.qio.QIOResourceEntry;
import mekanism.common.inventory.container.MekanismTileContainer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketSetSlot;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceCodecs;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

/**
 * Take-only interactions with the team recovery grid: page requests and take
 * operations. Only an open, accessible recovery container may use it, and
 * nothing can ever be deposited into the recovery buffer through this channel.
 */
public final class PacketCloudworksTeamRecovery {

    public enum Operation { PAGE, TAKE_STACK, TAKE_ALL }
    public static final int PAGE_SIZE = 128;
    private static final int MAX_BYTES = 1024 * 1024;

    private PacketCloudworksTeamRecovery() {
    }

    public static final class Request implements IMessage {
        private int window, offset;
        private Operation operation;
        private QIOResourceDescriptor resource;
        private long amount;
        private boolean valid;

        public Request() {
        }

        public Request(int window, Operation operation, QIOResourceDescriptor resource, int offset) {
            this(window, operation, resource, offset, 0);
        }

        public Request(int window, Operation operation, QIOResourceDescriptor resource,
                       int offset, long amount) {
            this.window = window;
            this.operation = operation;
            this.resource = resource;
            this.offset = offset;
            this.amount = amount;
            valid = true;
        }

        public boolean valid() {
            return valid;
        }

        public long requestedAmount() {
            return amount;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            PacketBuffer data = new PacketBuffer(buf);
            data.writeInt(window);
            data.writeByte(operation.ordinal());
            data.writeInt(offset);
            data.writeLong(amount);
            if (operation == Operation.TAKE_STACK || operation == Operation.TAKE_ALL)
                CloudworksLineDraftCodec.resource(data, resource);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            valid = false;
            try {
                if (buf.readableBytes() > MAX_BYTES) return;
                PacketBuffer data = new PacketBuffer(buf);
                window = data.readInt();
                int code = data.readUnsignedByte();
                if (code >= Operation.values().length) return;
                operation = Operation.values()[code];
                offset = data.readInt();
                amount = data.readLong();
                if (operation == Operation.TAKE_STACK || operation == Operation.TAKE_ALL)
                    resource = CloudworksLineDraftCodec.resource(data);
                valid = window >= 0 && offset >= 0 && amount >= 0 && !data.isReadable();
            } catch (RuntimeException malformed) {
                valid = false;
            }
        }
    }

    public static final class Handler implements IMessageHandler<Request, IMessage> {
        @Override
        public IMessage onMessage(Request request, MessageContext context) {
            if (!request.valid) return null;
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (!(player.openContainer instanceof ContainerCloudworksTeamRecovery container)
                        || container.windowId != request.window || !container.canInteractWith(player))
                    return;
                TileEntityCloudworksTeamRecovery tile = container.getTileEntity();
                if (tile == null) return;
                boolean changed = false;
                if (request.operation == Operation.TAKE_STACK || request.operation == Operation.TAKE_ALL) {
                    long amount = request.operation == Operation.TAKE_ALL
                            ? takeAllAmount(tile, request.resource)
                            : request.amount > 0 ? request.amount : oneTakeUnit(tile, request.resource);
                    changed = tile.takeFor(player, request.resource, amount,
                            request.operation == Operation.TAKE_ALL) > 0;
                }
                if (changed) {
                    syncAfterTransfer(player, container);
                }
                // The response always pages at the client's current offset so a take
                // never snaps the view back to the first page.
                container.sendRecoveryPage(player, request.offset);
            });
            return null;
        }

        /**
         * QIO viewer transfers are server-authoritative. Sync the carried stack
         * explicitly because this container has no real slot representing the
         * dynamic resource grid.
         */
        private static void syncAfterTransfer(EntityPlayerMP player,
                ContainerCloudworksTeamRecovery container) {
            player.inventory.markDirty();
            container.detectAndSendChanges();
            player.connection.sendPacket(new SPacketSetSlot(-1, -1, player.inventory.getItemStack()));
            player.sendContainerToPlayer(container);
        }

        private static long oneTakeUnit(TileEntityCloudworksTeamRecovery tile, QIOResourceDescriptor resource) {
            long stored = tile.recoveryBuffer().amount(resource);
            if (stored <= 0) return 0;
            if (QIOResourceCodecs.ITEM_FAMILY.equals(resource.getFamily())) {
                ItemStack template = resource.resolveItemStackTemplate();
                return Math.min(stored, template == null ? 0 : template.getMaxStackSize());
            }
            return Math.min(stored, TileEntityCloudworksTeamRecovery.slotCapOf(resource));
        }

        private static long takeAllAmount(TileEntityCloudworksTeamRecovery tile,
                                          QIOResourceDescriptor resource) {
            long stored = tile.recoveryBuffer().amount(resource);
            if (stored <= 0) return 0;
            if (QIOResourceCodecs.ITEM_FAMILY.equals(resource.getFamily())) {
                ItemStack template = resource.resolveItemStackTemplate();
                return Math.min(stored, template == null ? 0 : template.getMaxStackSize());
            }
            return Math.min(stored, TileEntityCloudworksTeamRecovery.slotCapOf(resource));
        }

    }

    public static State page(TileEntityCloudworksTeamRecovery tile, int window, int requestedOffset) {
        List<QIOResourceEntry> entries = new ArrayList<>();
        List<QIOResourceDescriptor> order = new ArrayList<>(tile.recoveryBuffer().snapshot().keySet());
        int total = order.size();
        // After the final row of a page is taken, show the preceding page
        // instead of leaving the user on an empty page with resources before it.
        int offset = total == 0 ? 0 : requestedOffset >= total
                ? (total - 1) / PAGE_SIZE * PAGE_SIZE : Math.max(0, requestedOffset);
        for (int index = offset; index < total && entries.size() < PAGE_SIZE; index++) {
            QIOResourceDescriptor descriptor = order.get(index);
            entries.add(QIOResourceEntry.of(descriptorId(descriptor), descriptor,
                    QIOAmount.of(tile.recoveryBuffer().amount(descriptor))));
        }
        return new State(window, entries, offset, total, tile.recoveryVersion());
    }

    private static UUID descriptorId(QIOResourceDescriptor descriptor) {
        return UUID.nameUUIDFromBytes(
                descriptor.write().toString().getBytes(StandardCharsets.UTF_8));
    }

    public static final class State implements IMessage {
        private int window, offset, total, version;
        private List<QIOResourceEntry> entries = new ArrayList<>();
        private boolean valid;

        public State() {
        }

        public State(int window, List<QIOResourceEntry> entries, int offset, int total, int version) {
            this.window = window;
            this.entries = new ArrayList<>(entries);
            this.offset = offset;
            this.total = total;
            this.version = version;
        }

        public boolean valid() {
            return valid;
        }

        public int offset() { return offset; }
        public List<QIOResourceEntry> entries() { return Collections.unmodifiableList(entries); }

        @Override
        public void toBytes(ByteBuf buf) {
            PacketBuffer data = new PacketBuffer(buf);
            data.writeInt(window);
            data.writeInt(offset);
            data.writeInt(total);
            data.writeInt(version);
            data.writeVarInt(entries.size());
            for (QIOResourceEntry entry : entries) {
                CloudworksLineDraftCodec.resource(data, entry.getDescriptor());
                data.writeLong(entry.getAmount());
            }
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            valid = false;
            try {
                if (buf.readableBytes() > MAX_BYTES) return;
                PacketBuffer data = new PacketBuffer(buf);
                window = data.readInt();
                offset = data.readInt();
                total = data.readInt();
                version = data.readInt();
                int count = CloudworksLineDraftCodec.count(data, PAGE_SIZE);
                entries = new ArrayList<>();
                for (int index = 0; index < count; index++) {
                    QIOResourceDescriptor descriptor = CloudworksLineDraftCodec.resource(data);
                    long amount = data.readLong();
                    if (amount < 0) return;
                    entries.add(QIOResourceEntry.of(descriptorId(descriptor), descriptor, QIOAmount.of(amount)));
                }
                valid = window >= 0 && offset >= 0 && total >= 0 && offset <= total
                        && entries.size() <= total - offset && !data.isReadable();
            } catch (RuntimeException malformed) {
                valid = false;
            }
        }
    }

    public static final class StateHandler implements IMessageHandler<State, IMessage> {
        @SideOnly(Side.CLIENT)
        private static void apply(State state) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                EntityPlayer player = Minecraft.getMinecraft().player;
                if (player != null && player.openContainer instanceof MekanismTileContainer<?> container
                        && container.windowId == state.window
                        && container.getTileEntity() instanceof TileEntityCloudworksTeamRecovery tile)
                    tile.applyRecoveryState(state.entries, state.offset, state.total, state.version);
            });
        }

        @Override
        public IMessage onMessage(State state, MessageContext context) {
            if (state.valid) apply(state);
            return null;
        }
    }
}
