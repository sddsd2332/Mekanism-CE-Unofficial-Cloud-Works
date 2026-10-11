package mekanism.cloudworks.common.network;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.common.PacketHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.simpleimpl.*;

public final class PacketCloudworksLineEditResult implements IMessageHandler<PacketCloudworksLineEditResult.Message, IMessage> {
    @Override
    public IMessage onMessage(Message message, MessageContext context) {
        if (!message.valid) return null;
        EntityPlayer player = PacketHandler.getPlayer(context);
        if (player == null || !player.world.isRemote) return null;
        PacketHandler.handlePacket(() -> {
            if (player.openContainer instanceof ContainerCloudworks container
                    && container.windowId == message.window && message.session.equals(container.session())) {
                container.acceptLineEdit(message.sequence, message.operation, message.token, message.draft, message.error);
            }
        }, player);
        return null;
    }

    public static final class Message implements IMessage {
        private int window, operation;
        private UUID session, token;
        private long sequence;
        private CloudworksLineDraft draft;
        private String error;
        private boolean valid;

        public Message() {
        }

        public Message(ContainerCloudworks container, long sequence, int operation, UUID token,
                CloudworksLineDraft draft, String error) {
            window = container.windowId;
            session = container.session();
            this.sequence = sequence;
            this.operation = operation;
            this.token = token;
            this.draft = draft;
            this.error = error;
        }

        @Override
        public void toBytes(ByteBuf out) {
            PacketBuffer b = new PacketBuffer(out);
            b.writeInt(window);
            CloudworksGuiCodec.uuid(b, session);
            b.writeLong(sequence);
            b.writeByte(operation);
            b.writeString(error);
            b.writeBoolean(token != null);
            if (token != null) CloudworksGuiCodec.uuid(b, token);
            b.writeBoolean(draft != null);
            if (draft != null) CloudworksLineDraftCodec.write(b, draft);
        }

        @Override
        public void fromBytes(ByteBuf input) {
            try {
                if (input.readableBytes() > CloudworksGuiCodec.MAX_BYTES) return;
                PacketBuffer b = new PacketBuffer(input);
                window = b.readInt();
                session = CloudworksGuiCodec.uuid(b);
                sequence = b.readLong();
                operation = b.readUnsignedByte();
                error = b.readString(256);
                token = b.readBoolean() ? CloudworksGuiCodec.uuid(b) : null;
                draft = b.readBoolean() ? CloudworksLineDraftCodec.read(b) : null;
                valid = !b.isReadable();
            } catch (RuntimeException invalid) {
                valid = false;
            }
        }
    }
}
