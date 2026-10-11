package mekanism.cloudworks.common.network;

import io.netty.buffer.ByteBuf;
import java.util.*;
import mekanism.cloudworks.common.blueprint.*;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager.BlueprintRequirement;
import mekanism.common.PacketHandler;
import net.minecraft.entity.player.*;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.simpleimpl.*;

/** Explicit request/response data, scoped to the terminal and its edit token. */
public final class PacketCloudworksBlueprint {
    public static final int EXPORT = 0, LIST = 1, READ = 2, PARSE = 3, PLACE = 4,
            REQUIREMENTS = 5, FILL = 6, CANCEL = 7, DELETE = 8, COPY = 9, PRECHECK = 10;

    public static final class Metadata {
        public final UUID id, exporter;
        public final String name, exporterName;
        public final long time;
        public final boolean canDelete;
        public Metadata(UUID id, UUID exporter, String name, String exporterName, long time, boolean canDelete) {
            this.id = id; this.exporter = exporter; this.name = name; this.exporterName = exporterName;
            this.time = time; this.canDelete = canDelete;
        }
    }

    public static final class Result {
        public int operation;
        public int pageOffset, nextOffset;
        public boolean hasMore;
        public String error = "", code = "";
        public CloudworksBlueprint blueprint;
        public CloudworksLineDraft draft;
        public final List<Metadata> entries = new ArrayList<>();
        public final List<BlueprintRequirement> requirements = new ArrayList<>();
        public boolean filled;
    }

    public static final class Request implements IMessage {
        public int window, operation, x, y;
        public UUID session, line, token, entry;
        public long sequence;
        public String code = "";
        public byte[] payload = new byte[0];
        public final Set<UUID> selection = new LinkedHashSet<>();
        private boolean valid;
        public Request() {}
        public Request(ContainerCloudworks container, int operation, UUID line, UUID entry,
                String code, CloudworksBlueprint blueprint, int x, int y, Set<UUID> selection) {
            window = container.windowId; session = container.session(); sequence = container.nextSequence();
            token = container.editToken; this.operation = operation; this.line = line; this.entry = entry;
            this.code = code; this.x = x; this.y = y;
            payload = blueprint == null ? new byte[0] : blueprint.bytes();
            if (selection != null) this.selection.addAll(selection);
        }
        @Override public void toBytes(ByteBuf out) {
            PacketBuffer b = new PacketBuffer(out);
            b.writeInt(window); CloudworksGuiCodec.uuid(b, session); b.writeLong(sequence);
            b.writeByte(operation); optional(b, line); optional(b, token); optional(b, entry);
            if (code.length() > CloudworksBlueprintLibrary.CODE_LENGTH) throw CloudworksBlueprint.invalid();
            b.writeString(code); b.writeByteArray(payload);
            b.writeInt(x); b.writeInt(y);
            b.writeVarInt(selection.size());
            selection.forEach(id -> CloudworksGuiCodec.uuid(b, id));
            if (b.writerIndex() > CloudworksGuiCodec.MAX_BYTES) throw CloudworksBlueprint.invalid();
        }
        @Override public void fromBytes(ByteBuf in) {
            try {
                if (in.readableBytes() > CloudworksGuiCodec.MAX_BYTES) return;
                PacketBuffer b = new PacketBuffer(in);
                window = b.readInt(); session = CloudworksGuiCodec.uuid(b); sequence = b.readLong();
                operation = b.readUnsignedByte(); line = optional(b); token = optional(b); entry = optional(b);
                code = b.readString(CloudworksBlueprintLibrary.CODE_LENGTH);
                payload = b.readByteArray(CloudworksGuiCodec.MAX_BYTES - 1024);
                x = b.readInt(); y = b.readInt();
                for (int count = CloudworksLineDraftCodec.count(b, b.readableBytes() / 16); count > 0; count--)
                    if (!selection.add(CloudworksGuiCodec.uuid(b))) throw CloudworksBlueprint.invalid();
                valid = !b.isReadable() && operation >= EXPORT && operation <= PRECHECK && sequence > 0;
            } catch (RuntimeException invalid) { valid = false; }
        }
    }
    public static final class Handler implements IMessageHandler<Request, IMessage> {
        @Override public IMessage onMessage(Request request, MessageContext context) {
            if (!request.valid) return null;
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (player.openContainer instanceof ContainerCloudworks container
                        && container.accepts(player, request.window, request.session, request.sequence))
                    container.handleBlueprint(request);
            });
            return null;
        }
    }

    public static final class Response implements IMessage {
        private int window;
        private UUID session;
        private long sequence;
        public Result result;
        private boolean valid;
        public Response() {}
        public Response(ContainerCloudworks container, long sequence, Result result) {
            window = container.windowId; session = container.session(); this.sequence = sequence; this.result = result;
        }
        @Override public void toBytes(ByteBuf out) {
            PacketBuffer b = new PacketBuffer(out);
            b.writeInt(window); CloudworksGuiCodec.uuid(b, session); b.writeLong(sequence);
            b.writeByte(result.operation); b.writeString(result.error);
            if (result.code.length() > CloudworksBlueprintLibrary.CODE_LENGTH) throw CloudworksBlueprint.invalid();
            b.writeString(result.code);
            b.writeByteArray(result.blueprint == null ? new byte[0] : result.blueprint.bytes());
            b.writeBoolean(result.draft != null);
            if (result.draft != null) CloudworksLineDraftCodec.write(b, result.draft);
            b.writeBoolean(result.filled);
            b.writeVarInt(result.entries.size());
            for (Metadata entry : result.entries) {
                CloudworksGuiCodec.uuid(b, entry.id); CloudworksGuiCodec.uuid(b, entry.exporter);
                b.writeString(entry.name); b.writeString(entry.exporterName); b.writeLong(entry.time); b.writeBoolean(entry.canDelete);
            }
            b.writeVarInt(result.requirements.size());
            for (BlueprintRequirement requirement : result.requirements) {
                CloudworksGuiCodec.writeStack(b, requirement.stack);
                b.writeLong(requirement.required); b.writeLong(requirement.missing);
            }
            b.writeInt(result.pageOffset); b.writeInt(result.nextOffset); b.writeBoolean(result.hasMore);
            if (b.writerIndex() > CloudworksGuiCodec.MAX_BYTES) throw CloudworksBlueprint.invalid();
        }
        @Override public void fromBytes(ByteBuf in) {
            try {
                if (in.readableBytes() > CloudworksGuiCodec.MAX_BYTES) return;
                PacketBuffer b = new PacketBuffer(in);
                window = b.readInt(); session = CloudworksGuiCodec.uuid(b); sequence = b.readLong();
                result = new Result(); result.operation = b.readUnsignedByte(); result.error = b.readString(256);
                result.code = b.readString(CloudworksBlueprintLibrary.CODE_LENGTH);
                if (!result.code.isEmpty()) CloudworksBlueprintLibrary.parseCode(result.code);
                byte[] bytes = b.readByteArray(CloudworksGuiCodec.MAX_BYTES - 1024);
                if (bytes.length > 0) result.blueprint = CloudworksBlueprint.fromBytes(bytes);
                result.draft = b.readBoolean() ? CloudworksLineDraftCodec.read(b) : null;
                result.filled = b.readBoolean();
                for (int count = CloudworksLineDraftCodec.count(b, b.readableBytes() / 42); count > 0; count--)
                    result.entries.add(new Metadata(CloudworksGuiCodec.uuid(b), CloudworksGuiCodec.uuid(b),
                            b.readString(64), b.readString(64), b.readLong(), b.readBoolean()));
                for (int count = CloudworksLineDraftCodec.count(b, b.readableBytes() / 16); count > 0; count--) {
                    net.minecraft.item.ItemStack stack = CloudworksGuiCodec.readStack(b);
                    long required = b.readLong(), missing = b.readLong();
                    if (stack.isEmpty() || required <= 0 || missing < 0 || missing > required) throw CloudworksBlueprint.invalid();
                    result.requirements.add(new BlueprintRequirement(stack, required, missing));
                }
                result.pageOffset = b.readInt(); result.nextOffset = b.readInt(); result.hasMore = b.readBoolean();
                valid = !b.isReadable() && result.pageOffset >= 0 && result.nextOffset >= result.pageOffset
                        && result.entries.size() <= CloudworksBlueprintLibrary.PAGE_SIZE;
            } catch (RuntimeException invalid) {
                if (mekanism.common.Mekanism.logger != null)
                    mekanism.common.Mekanism.logger.debug("Rejected malformed Cloudworks blueprint response", invalid);
                valid = false;
            }
        }
    }
    public static final class ResponseHandler implements IMessageHandler<Response, IMessage> {
        @Override public IMessage onMessage(Response response, MessageContext context) {
            if (!response.valid) return null;
            EntityPlayer player = PacketHandler.getPlayer(context);
            if (player != null && player.world.isRemote) PacketHandler.handlePacket(() -> {
                if (player.openContainer instanceof ContainerCloudworks container && container.windowId == response.window
                        && response.session.equals(container.session()))
                    container.acceptBlueprint(response.sequence, response.result);
            }, player);
            return null;
        }
    }
    private static void optional(PacketBuffer b, UUID id) {
        b.writeBoolean(id != null); if (id != null) CloudworksGuiCodec.uuid(b, id);
    }
    private static UUID optional(PacketBuffer b) { return b.readBoolean() ? CloudworksGuiCodec.uuid(b) : null; }
}
