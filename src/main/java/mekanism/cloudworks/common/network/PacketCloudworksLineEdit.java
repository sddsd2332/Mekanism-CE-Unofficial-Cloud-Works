package mekanism.cloudworks.common.network;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.simpleimpl.*;

/** Begin/commit protocol. The edit token is scoped to the currently open terminal container. */
public final class PacketCloudworksLineEdit implements IMessageHandler<PacketCloudworksLineEdit.Message, IMessage> {
    public static final int BEGIN = 0, COMMIT = 1, RELOAD = 2, SYNC = 3;
    // Operation ids 4-7 are retired and intentionally never reused.

    @Override
    public IMessage onMessage(Message message, MessageContext context) {
        if (!message.valid) return null;
        EntityPlayerMP player = context.getServerHandler().player;
        player.getServerWorld().addScheduledTask(() -> {
            if (!(player.openContainer instanceof ContainerCloudworks container)
                    || !container.accepts(player, message.window, message.session, message.sequence)) return;
            container.handleLineEdit(message.operation, message.line, message.token, message.draft, message.sequence);
        });
        return null;
    }

    public static final class Message implements IMessage {
        private int window, operation;
        private UUID session, line, token;
        private long sequence;
        private CloudworksLineDraft draft;
        private boolean valid;

        public Message() {
        }

        public Message(ContainerCloudworks container, int operation, UUID line, UUID token, CloudworksLineDraft draft) {
            window = container.windowId;
            session = container.session();
            sequence = container.nextSequence();
            this.operation = operation;
            this.line = line;
            this.token = token;
            this.draft = draft;
        }

        @Override
        public void toBytes(ByteBuf out) {
            PacketBuffer b = new PacketBuffer(out);
            b.writeInt(window);
            CloudworksGuiCodec.uuid(b, session);
            b.writeLong(sequence);
            b.writeByte(operation);
            CloudworksGuiCodec.uuid(b, line);
            if (operation != BEGIN && operation != RELOAD) {
                CloudworksGuiCodec.uuid(b, token);
            }
            if (operation == COMMIT || operation == SYNC) {
                CloudworksLineDraftCodec.write(b, draft);
            }
            if (b.writerIndex() > CloudworksGuiCodec.MAX_BYTES) throw new IllegalArgumentException("Editor packet too large");
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
                line = CloudworksGuiCodec.uuid(b);
                if (operation != BEGIN && operation != RELOAD) {
                    token = CloudworksGuiCodec.uuid(b);
                }
                if (operation == COMMIT || operation == SYNC) {
                    draft = CloudworksLineDraftCodec.read(b);
                }
                valid = !b.isReadable() && operation >= BEGIN && operation <= SYNC && sequence > 0;
            } catch (RuntimeException invalid) {
                valid = false;
            }
        }
    }
}
