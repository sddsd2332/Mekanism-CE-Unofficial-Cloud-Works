package mekanism.cloudworks.common.network;

import io.netty.buffer.ByteBuf;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.cloudworks.common.util.CloudworksMessages;
import mekanism.common.PacketHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.UUID;

public final class CloudworksNetwork {
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("CLOUDWORKS");
    private static boolean initialized;

    private CloudworksNetwork() {
    }

    public static synchronized void init() {
        if (initialized) return;
        CHANNEL.registerMessage(Action.Handler.class, Action.class, 0, Side.SERVER);
        CHANNEL.registerMessage(PacketCloudworksData.class, PacketCloudworksData.Message.class, 1, Side.CLIENT);
        CHANNEL.registerMessage(Notice.Handler.class, Notice.class, 2, Side.CLIENT);
        CHANNEL.registerMessage(PacketCloudworksLineEdit.class, PacketCloudworksLineEdit.Message.class, 3, Side.SERVER);
        CHANNEL.registerMessage(PacketCloudworksLineEditResult.class, PacketCloudworksLineEditResult.Message.class, 4, Side.CLIENT);
        CHANNEL.registerMessage(PacketCloudworksPortQIO.Handler.class, PacketCloudworksPortQIO.Request.class, 5, Side.SERVER);
        CHANNEL.registerMessage(PacketCloudworksPortQIO.StateHandler.class, PacketCloudworksPortQIO.State.class, 6, Side.CLIENT);
        CHANNEL.registerMessage(PacketCloudworksBlueprint.Handler.class, PacketCloudworksBlueprint.Request.class, 7, Side.SERVER);
        CHANNEL.registerMessage(PacketCloudworksBlueprint.ResponseHandler.class, PacketCloudworksBlueprint.Response.class, 8, Side.CLIENT);
        initialized = true;
    }

    /**
     * Fires the top-right notice toast on the client (AE2-style, no payload).
     */
    public static void sendNotice(EntityPlayerMP player, int kind) {
        CHANNEL.sendTo(new Notice(kind), player);
    }

    private static UUID readUuid(ByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    /**
     * Thread-safe hop onto the next server tick; the MinecraftServer task queue
     * is concurrent, so the command worker may hand the GUI response back
     * without touching world state off-thread.
     */
    public static void hopToMainThread(Runnable task) {
        try {
            net.minecraftforge.fml.common.FMLCommonHandler.instance()
                    .getMinecraftServerInstance().addScheduledTask(task);
        } catch (RuntimeException | NoClassDefFoundError noServer) {
            task.run();   // bare environment without a launched server: run inline
        }
    }

    private static void writeUuid(ByteBuf buf, UUID id) {
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
    }

    public enum Operation {
        REFRESH, CREATE, START, STOP, RENAME, DELETE, ADD_NODE, REMOVE_NODE, LINK, UNLINK,
        VIEW_LINE, MOVE_NODE, TOGGLE_NODE,
        RESOURCE_CHOICES, UPDATE_LINK, UPLOAD_ENERGY, INSTALL_UPGRADE, REMOVE_SPEED, REMOVE_ENERGY, ADD_INVENTORY, RETURN_CACHE, MERGE_MACHINE,
        FILTER_LINK_CHOICE, FILTER_LINK_HELD, FILTER_LINK_CLEAR, CLEAR_NODE_CACHE,
        CACHE_VIEW, WAREHOUSES_VIEW,
        RECYCLE_VIEW, RECYCLE_ROUTE, RECYCLE_CLEAR, ENERGY_FLOW, ENERGY_UPLOAD_CONFIRM,
        MACHINE_UPLOAD, MACHINE_DOWNLOAD, UPGRADE_UPLOAD_CONFIRM, UPGRADE_DOWNLOAD, LINE_FLOW, LINE_OFFLINE
    }

    /**
     * Server-to-client notice trigger: recycle overflow and similar player-facing events.
     */
    public static final class Notice implements IMessage {
        public static final int RECYCLE_OVERFLOW = 0;
        private int kind;

        public Notice() {
        }

        public Notice(int kind) {
            this.kind = kind;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            kind = buf.readByte();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(kind);
        }

        public static final class Handler implements IMessageHandler<Notice, IMessage> {
            @SideOnly(Side.CLIENT)
            private static void showToast(int kind) {
                net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() ->
                        net.minecraft.client.Minecraft.getMinecraft().getToastGui().add(
                                new mekanism.cloudworks.client.toast.CloudworksNoticeToast(kind)));
            }

            @Override
            public IMessage onMessage(Notice message, MessageContext ctx) {
                showToast(message.kind);
                return null;
            }
        }
    }

    public static final class Action implements IMessage {
        private int window, offset;
        private UUID session, line, node;
        private long sequence, revision;
        private Operation operation;
        private String name = "";
        private boolean valid;

        public Action() {
        }

        public Action(ContainerCloudworks container, Operation operation, UUID line, String name) {
            this(container, operation, line, null, name);
        }

        public Action(ContainerCloudworks container, Operation operation, UUID line, UUID node, String name) {
            this(container.windowId, container.session(), container.nextSequence(), container.revision(), operation, line, node, name, container.offset());
        }

        public Action(int window, UUID session, long sequence, long revision, Operation operation, UUID line, String name, int offset) {
            this(window, session, sequence, revision, operation, line, null, name, offset);
        }

        public Action(int window, UUID session, long sequence, long revision, Operation operation, UUID line, UUID node, String name, int offset) {
            this.window = window;
            this.session = session;
            this.sequence = sequence;
            this.revision = revision;
            this.operation = operation;
            this.line = line;
            this.node = node;
            this.name = name;
            this.offset = offset;
            valid = true;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            valid = false;
            try {
                if (buf.readableBytes() > 4096) return;
                PacketBuffer data = new PacketBuffer(buf);
                window = data.readInt();
                session = readUuid(data);
                sequence = data.readLong();
                revision = data.readLong();
                int code = data.readUnsignedByte();
                if (code >= Operation.values().length) return;
                operation = Operation.values()[code];
                line = data.readBoolean() ? readUuid(data) : null;
                node = data.readBoolean() ? readUuid(data) : null;
                name = data.readString(512);
                offset = data.readInt();
                valid = !data.isReadable() && window >= 0 && sequence > 0 && revision >= 0 && offset >= 0;
            } catch (RuntimeException malformed) {
                valid = false;
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            PacketBuffer data = new PacketBuffer(buf);
            data.writeInt(window);
            writeUuid(data, session);
            data.writeLong(sequence);
            data.writeLong(revision);
            data.writeByte(operation.ordinal());
            data.writeBoolean(line != null);
            if (line != null) writeUuid(data, line);
            data.writeBoolean(node != null);
            if (node != null) writeUuid(data, node);
            data.writeString(name);
            data.writeInt(offset);
        }

        public boolean valid() {
            return valid;
        }

        public Operation operation() {
            return operation;
        }

        public static final class Handler implements IMessageHandler<Action, IMessage> {
            @Override
            public IMessage onMessage(Action message, MessageContext context) {
                if (!message.valid) return null;
                EntityPlayerMP player = context.getServerHandler().player;
                player.getServerWorld().addScheduledTask(() -> {
                    if (!(player.openContainer instanceof ContainerCloudworks container)
                            || !container.accepts(player, message.window, message.session, message.sequence)) return;
                    CloudworksPlayerDataManager manager = CloudworksPlayerDataManager.INSTANCE;
                    UUID cloudOwner = container.ownerId();
                    // Structural commands (Runtime §3.3): the server thread only
                    // validates the session and captures main-thread inputs
                    // (held-item descriptor, GUI resource choice); application
                    // runs on the bounded command worker under the manager lock
                    // and the response hops back to this thread.
                    if (mekanism.cloudworks.common.service.CloudworksStructuralCommand.asynchronous(message.operation)) {
                        mekanism.cloudworks.common.service.CloudworksStructuralCommand command;
                        try {
                            command = mekanism.cloudworks.common.service.CloudworksStructuralCommand.capture(cloudOwner,
                                    message.operation, message.line, message.node, message.name, message.revision,
                                    (lineId, nodeId) -> container.resourceChoice(lineId, nodeId,
                                            Integer.parseInt(message.name)),
                                    player.getHeldItemMainhand());
                        } catch (RuntimeException malformed) {
                            container.sendData("cloudworks.error.editor");
                            return;
                        }
                        if (command == null) {
                            container.sendData("cloudworks.error.editor");
                            return;
                        }
                        boolean queued = mekanism.cloudworks.common.service.CloudworksServerTickService.INSTANCE
                                .submitStructuralCommand(cloudOwner, () -> {
                            String status = command.apply(CloudworksPlayerDataManager.INSTANCE);
                            hopToMainThread(() -> {
                                if (player.openContainer != container || !container.canInteractWith(player)) return;
                                if (CloudworksMessages.SAVED.equals(status)) {
                                    if (message.operation == Operation.DELETE) container.selectGraph(null);
                                    else if (command.createdLine() != null) container.selectGraph(command.createdLine());
                                }
                                player.inventoryContainer.detectAndSendChanges();
                                container.sendData(status);
                            });
                        });
                        if (!queued) container.sendData("cloudworks.error.busy");
                        return;
                    }
                    String status = "";
                    try {
                        if (message.operation != Operation.REFRESH && message.operation != Operation.VIEW_LINE
                                && message.operation != Operation.RESOURCE_CHOICES
                                && message.operation != Operation.CACHE_VIEW
                                && message.operation != Operation.WAREHOUSES_VIEW
                                && message.operation != Operation.RECYCLE_VIEW
                                && message.operation != Operation.MACHINE_UPLOAD
                                && message.operation != Operation.MACHINE_DOWNLOAD
                                && message.operation != Operation.START
                                && message.operation != Operation.STOP
                                && message.operation != Operation.INSTALL_UPGRADE
                                && message.operation != Operation.REMOVE_SPEED
                                && message.operation != Operation.REMOVE_ENERGY
                                && message.operation != Operation.UPGRADE_UPLOAD_CONFIRM
                                && message.operation != Operation.UPGRADE_DOWNLOAD
                                && manager.commandRevision(cloudOwner) != message.revision) {
                            status = "cloudworks.error.stale";
                        } else {
                            boolean applied = true;
                            // Structural operations (CREATE/START/STOP/RENAME/DELETE/
                            // LINK/UNLINK/REMOVE_NODE/LINE_FLOW and the editor's
                            // node/link/upgrade/cache operations) were intercepted
                            // above and applied on the command worker; only
                            // main-thread-bound work remains here.
                            switch (message.operation) {
                                case REFRESH -> container.setOffset(message.offset);
                                case ADD_NODE -> {
                                    if (message.line == null || player.getHeldItemMainhand().isEmpty()) applied = false;
                                    else {
                                        net.minecraft.item.ItemStack held = player.getHeldItemMainhand();
                                        mekanism.cloudworks.api.ICloudworksUploadSource source =
                                                new mekanism.cloudworks.common.service.CloudworksPlayerInventorySource(
                                                player.inventory.mainInventory, player.getUniqueID(), cloudOwner, () -> {
                                            player.inventory.markDirty();
                                            player.inventoryContainer.detectAndSendChanges();
                                        });
                                        mekanism.cloudworks.api.CloudworksUploadResult uploaded =
                                                manager.uploadMachines(source, message.line, held, held.getCount(), 0, 0,
                                                        player.world.provider.getDimension());
                                        applied = uploaded.success();
                                    }
                                }
                                case CACHE_VIEW -> {
                                    // 机器缓存层视图: value = "-1" reset | "types" types-only | typeIndex for variants
                                    container.selectCacheView("-1".equals(message.name) ? -2
                                            : message.name.isEmpty() || "types".equals(message.name) ? -1
                                            : Integer.parseInt(message.name));
                                }
                                case MACHINE_UPLOAD -> {
                                    container.uploadMachines();
                                }
                                case MACHINE_DOWNLOAD -> {
                                    // 终端插槽·机器下载: value = "typeIndex,variantIndex,shift" —
                                    // materializes into the host's machine-output slot
                                    String[] parts = message.name.split(",", -1);
                                    container.downloadMachines(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                                            "1".equals(parts[2]));
                                }
                                case UPGRADE_DOWNLOAD -> {
                                    // 终端插槽·升级下载: value = "speed|energy,shift" —
                                    // materializes pool counts into the host's upgrade-output slot
                                    String[] parts = message.name.split(",", -1);
                                    container.downloadUpgrade("speed".equals(parts[0]), "1".equals(parts[1]));
                                }
                                case WAREHOUSES_VIEW -> container.setWarehousesView("1".equals(message.name));
                                case RECYCLE_VIEW -> container.setRecycleView("1".equals(message.name));
                                case RECYCLE_ROUTE -> {
                                    // 手动路由: "transfer" → 中转仓, "sending" → 发货仓
                                    boolean toTransfer = "transfer".equals(message.name);
                                    manager.setRecycleRoute(cloudOwner, toTransfer);
                                    manager.routeRecycleBin(cloudOwner);
                                }
                                case RECYCLE_CLEAR -> manager.clearRecycleBin(cloudOwner);
                                case ENERGY_FLOW -> {
                                    // owner 级两门开关: value 形如 "r2c=1" / "c2s=0"，可分号并联；未提及的门保持原值
                                    boolean r2c = manager.getOrCreate(cloudOwner).energyFlowReceivingToCloud();
                                    boolean c2s = manager.getOrCreate(cloudOwner).energyFlowCloudToSending();
                                    for (String segment : message.name.split(";")) {
                                        String[] pair = segment.split("=", 2);
                                        if (pair.length == 2 && "r2c".equals(pair[0])) r2c = "1".equals(pair[1]);
                                        if (pair.length == 2 && "c2s".equals(pair[0])) c2s = "1".equals(pair[1]);
                                    }
                                    manager.setEnergyFlows(cloudOwner, r2c,
                                          manager.getOrCreate(cloudOwner).energyFlowReceivingToLines(), c2s,
                                          manager.getOrCreate(cloudOwner).energyFlowLinesToSending());
                                }
                                case ENERGY_UPLOAD_CONFIRM -> container.uploadEnergy();
                                case UPGRADE_UPLOAD_CONFIRM -> container.uploadUpgrade();
                                default -> applied = mekanism.cloudworks.common.service.CloudworksEditorActions.apply(
                                        container, player, message.operation, message.line, message.node, message.name);
                            }
                            if (!applied) status = "cloudworks.error.not_found";
                            else if (message.operation != Operation.REFRESH) status = CloudworksMessages.SAVED;
                        }
                    } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException error) {
                        status = CloudworksMessages.errorKey(error);
                    }
                    player.inventoryContainer.detectAndSendChanges();
                    container.sendData(status);
                });
                return null;
            }
        }
    }
}
