package mekanism.cloudworks.common.network;

import io.netty.buffer.ByteBuf;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.common.PacketHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public final class PacketCloudworksData implements IMessageHandler<PacketCloudworksData.Message, IMessage> {
    @Override
    public IMessage onMessage(Message message, MessageContext context) {
        if (!message.valid) return null;
        EntityPlayer player = PacketHandler.getPlayer(context);
        if (player == null || !player.world.isRemote) return null;
        PacketHandler.handlePacket(() -> {
            if (player.openContainer instanceof ContainerCloudworks container) {
                container.acceptData(message.window, message.session, message.sequence, message.generation,
                        message.chunkIndex, message.chunkCount, message.data, message.status);
            }
        }, player);
        return null;
    }

    public static final class Message implements IMessage {
        private int window;
        private UUID session;
        private long sequence;
        private long generation;
        private int chunkIndex, chunkCount;
        private String status = "";
        private byte[][] sections;
        private CloudworksGuiData data;
        private boolean valid;

        public Message() {
        }

        private Message(int window, UUID session, long sequence, long generation, int chunkIndex,
                int chunkCount, byte[][] sections, String status) {
            this.window = window;
            this.session = session;
            this.sequence = sequence;
            this.generation = generation;
            this.chunkIndex = chunkIndex;
            this.chunkCount = chunkCount;
            this.status = status;
            this.sections = sections;
            valid = true;
        }

        public static List<Message> create(int window, UUID session, long sequence, long generation,
                byte[][] current, byte[][] previous, String status) {
            List<byte[][]> chunks = new ArrayList<>();
            byte[][] chunk = new byte[CloudworksGuiCodec.SECTION_COUNT][];
            int size = 1024;
            for (int section = 0; section < current.length; section++) {
                if (previous == null || !Arrays.equals(current[section], previous[section])) {
                    int sectionSize = current[section].length + 5;
                    if (size + sectionSize > CloudworksGuiCodec.MAX_BYTES) {
                        chunks.add(chunk);
                        chunk = new byte[CloudworksGuiCodec.SECTION_COUNT][];
                        size = 1024;
                    }
                    chunk[section] = current[section];
                    size += sectionSize;
                }
            }
            chunks.add(chunk);
            List<Message> messages = new ArrayList<>(chunks.size());
            for (int index = 0; index < chunks.size(); index++) {
                messages.add(new Message(window, session, sequence, generation, index,
                        chunks.size(), chunks.get(index), status));
            }
            return messages;
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            PacketBuffer b = new PacketBuffer(buffer);
            b.writeInt(window);
            CloudworksGuiCodec.uuid(b, session);
            b.writeLong(sequence);
            b.writeLong(generation);
            b.writeByte(chunkIndex);
            b.writeByte(chunkCount);
            b.writeString(status);
            int mask = 0;
            for (int section = 0; section < sections.length; section++) {
                if (sections[section] != null) mask |= 1 << section;
            }
            b.writeByte(mask);
            for (byte[] section : sections) {
                if (section != null) b.writeByteArray(section);
            }
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            valid = false;
            try {
                if (buffer.readableBytes() > CloudworksGuiCodec.MAX_BYTES) return;
                PacketBuffer b = new PacketBuffer(buffer);
                window = b.readInt();
                session = CloudworksGuiCodec.uuid(b);
                sequence = b.readLong();
                generation = b.readLong();
                chunkIndex = b.readUnsignedByte();
                chunkCount = b.readUnsignedByte();
                status = b.readString(128);
                int mask = b.readUnsignedByte();
                if ((mask >>> CloudworksGuiCodec.SECTION_COUNT) != 0) return;
                sections = new byte[CloudworksGuiCodec.SECTION_COUNT][];
                for (int section = 0; section < sections.length; section++) {
                    if ((mask & 1 << section) != 0) sections[section] = b.readByteArray(CloudworksGuiCodec.MAX_BYTES);
                }
                data = CloudworksGuiCodec.decode(sections);
                valid = window >= 0 && sequence >= 0 && generation > 0
                        && chunkCount > 0 && chunkCount <= CloudworksGuiCodec.SECTION_COUNT
                        && chunkIndex < chunkCount && !b.isReadable();
            } catch (RuntimeException malformed) {
                data = null;
            }
        }
    }
}
