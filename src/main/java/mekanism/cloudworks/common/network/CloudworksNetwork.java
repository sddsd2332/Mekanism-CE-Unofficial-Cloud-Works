package mekanism.cloudworks.common.network;

import io.netty.buffer.ByteBuf;
import mekanism.cloudworks.common.data.CloudworksData;
import mekanism.cloudworks.common.data.CloudworksFlowGate;
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

import java.util.Map;
import java.util.UUID;
import mekanism.cloudworks.api.transfer.CloudworksUploadResult;
import mekanism.cloudworks.api.transfer.ICloudworksUploadSource;
import mekanism.cloudworks.client.toast.CloudworksNoticeToast;
import mekanism.cloudworks.common.config.CloudworksConfig;
import mekanism.cloudworks.common.service.CloudworksEditorActions;
import mekanism.cloudworks.common.service.CloudworksPlayerInventorySource;
import mekanism.cloudworks.common.service.CloudworksServerTickService;
import mekanism.cloudworks.common.service.CloudworksStructuralCommand;
import mekanism.cloudworks.common.team.CloudworksTeamActions;
import mekanism.cloudworks.common.team.CloudworksTeamContext;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.FMLCommonHandler;

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
        CHANNEL.registerMessage(PacketCloudworksTeamRecovery.Handler.class, PacketCloudworksTeamRecovery.Request.class, 9, Side.SERVER);
        CHANNEL.registerMessage(PacketCloudworksTeamRecovery.StateHandler.class, PacketCloudworksTeamRecovery.State.class, 10, Side.CLIENT);
        CHANNEL.registerMessage(PacketCloudTeamJoin.Handler.class, PacketCloudTeamJoin.Request.class, 11, Side.SERVER);
        CHANNEL.registerMessage(PacketCloudTeamJoin.StateHandler.class, PacketCloudTeamJoin.State.class, 12, Side.CLIENT);
        initialized = true;
    }

    /**
     * Fires the top-right notice toast on the client (AE2-style, no payload).
     */
    public static void sendNotice(EntityPlayerMP player, int kind) {
        CHANNEL.sendTo(new Notice(kind), player);
    }

    public static void sendNotice(EntityPlayerMP player, int kind, UUID leader,
            String leaderName, String teamName) {
        CHANNEL.sendTo(new Notice(kind, leader, leaderName, teamName), player);
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
            FMLCommonHandler.instance()
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
        CACHE_VIEW, WAREHOUSES_VIEW, CATALOG_VIEW,
        RECYCLE_VIEW, RECYCLE_ROUTE, RECYCLE_CLEAR, ENERGY_FLOW, ENERGY_UPLOAD_CONFIRM,
         MACHINE_UPLOAD, MACHINE_DOWNLOAD, UPGRADE_UPLOAD_CONFIRM, UPGRADE_DOWNLOAD, LINE_FLOW, LINE_OFFLINE,
         LINE_BUFFER_CAPACITY, TEAM_ACCESS, TEAM_CREATE, TEAM_JOIN, TEAM_REMOVE,
         TEAM_LEAVE, TEAM_RENAME, TEAM_OWNER, TEAM_DISSOLVE,
         TEAM_REQUEST_JOIN, TEAM_APPROVE_JOIN, TEAM_DENY_JOIN, TEAM_SET_MODE, TEAM_SET_COLOR, TEAM_IMPORT
    }

    /**
     * Server-to-client notice trigger: recycle overflow and similar player-facing events.
     */
    public static final class Notice implements IMessage {
        public static final int RECYCLE_OVERFLOW = 0;
        public static final int TEAM_DISSOLVED = 1;
        private int kind;
        private UUID leader;
        private String leaderName = "", teamName = "";

        public Notice() {
        }

        public Notice(int kind) {
            this.kind = kind;
        }

        public Notice(int kind, UUID leader, String leaderName, String teamName) {
            this.kind = kind;
            this.leader = leader;
            this.leaderName = leaderName == null ? "" : leaderName;
            this.teamName = teamName == null ? "" : teamName;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            kind = buf.readByte();
            if (buf.readBoolean()) {
                leader = readUuid(buf);
                PacketBuffer data = new PacketBuffer(buf);
                leaderName = data.readString(64);
                teamName = data.readString(64);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(kind);
            buf.writeBoolean(leader != null);
            if (leader != null) {
                writeUuid(buf, leader);
                PacketBuffer data = new PacketBuffer(buf);
                data.writeString(leaderName);
                data.writeString(teamName);
            }
        }

        public static final class Handler implements IMessageHandler<Notice, IMessage> {
            @SideOnly(Side.CLIENT)
            private static void showToast(int kind, UUID leader, String leaderName, String teamName) {
                Minecraft.getMinecraft().addScheduledTask(() ->
                        Minecraft.getMinecraft().getToastGui().add(
                                new CloudworksNoticeToast(
                                        kind, leader, leaderName, teamName)));
            }

            @Override
            public IMessage onMessage(Notice message, MessageContext ctx) {
                showToast(message.kind, message.leader, message.leaderName, message.teamName);
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
                    UUID actor = player.getUniqueID();
                    if (!CloudworksConfig.settings().terminalItemUploadsEnabled
                            && (message.operation == Operation.ADD_NODE || message.operation == Operation.ADD_INVENTORY
                            || message.operation == Operation.MERGE_MACHINE || message.operation == Operation.MACHINE_UPLOAD
                            || message.operation == Operation.ENERGY_UPLOAD_CONFIRM || message.operation == Operation.UPGRADE_UPLOAD_CONFIRM
                            || message.operation == Operation.UPLOAD_ENERGY)) {
                        container.sendData("cloudworks.error.terminal_uploads_disabled");
                        return;
                    }
                    if (CloudworksTeamActions.isTeamAction(message.operation)) {
                        CloudworksTeamActions.handle(
                                player, container, message.operation, message.line, message.name);
                        return;
                    }
                    // Structural commands (Runtime §3.3): the server thread only
                    // validates the session and captures main-thread inputs
                    // (held-item descriptor, GUI resource choice); application
                    // runs on the bounded command worker under the manager lock
                    // and the response hops back to this thread.
                    if (CloudworksStructuralCommand.asynchronous(message.operation)) {
                        CloudworksStructuralCommand command;
                        try {
                            command = CloudworksStructuralCommand.capture(cloudOwner,
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
                        boolean queued = CloudworksServerTickService.INSTANCE
                                .submitStructuralCommand(cloudOwner, () -> {
                            if (container.isTeamTerminal()
                                    && !CloudworksTeamContext.canAccess(
                                            actor, cloudOwner)) return;
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
                                && message.operation != Operation.CATALOG_VIEW
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
                                        ItemStack held = player.getHeldItemMainhand();
                                        ICloudworksUploadSource source =
                                                new CloudworksPlayerInventorySource(
                                                player.inventory.mainInventory, player.getUniqueID(), cloudOwner, () -> {
                                            player.inventory.markDirty();
                                            player.inventoryContainer.detectAndSendChanges();
                                        });
                                        CloudworksUploadResult uploaded =
                                                manager.uploadMachines(source, message.line, held, held.getCount(), 0, 0);
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
                                    container.downloadUpgrade(parts[0], "1".equals(parts[1]));
                                }
                                case WAREHOUSES_VIEW -> container.setWarehousesView("1".equals(message.name));
                                case CATALOG_VIEW -> container.setCatalogView("1".equals(message.name));
                                case RECYCLE_VIEW -> container.setRecycleView("1".equals(message.name));
                                case RECYCLE_ROUTE -> {
                                    // 手动路由: "transfer" → 中转仓, "sending" → 发货仓
                                    boolean toTransfer = "transfer".equals(message.name);
                                    manager.setRecycleRoute(cloudOwner, toTransfer);
                                    manager.routeRecycleBin(cloudOwner);
                                }
                                case RECYCLE_CLEAR -> manager.clearRecycleBin(cloudOwner);
                                case ENERGY_FLOW -> {
                                    // 统一闸门线协议(8.4): value 形如 "r2c=1" / "c2s=0"，可分号并联；
                                    // 未提及的门保持原值，未声明为协议可编辑的云侧门(如 r2l/l2s)被忽略。
                                    // 校验路径不变: manager.setEnergyFlows 仍走 requireEnabled + 回滚持久化。
                                    CloudworksData cloud = manager.getOrCreate(cloudOwner);
                                    Map<CloudworksFlowGate, Boolean> edits = CloudworksFlowGate.parseWire(
                                            CloudworksFlowGate.Scope.CLOUD, message.name);
                                    manager.setEnergyFlows(cloudOwner,
                                            edits.getOrDefault(CloudworksFlowGate.RECEIVING_TO_CLOUD, cloud.energyFlowReceivingToCloud()),
                                            cloud.energyFlowReceivingToLines(),
                                            edits.getOrDefault(CloudworksFlowGate.CLOUD_TO_SENDING, cloud.energyFlowCloudToSending()),
                                            cloud.energyFlowLinesToSending());
                                }
                                case ENERGY_UPLOAD_CONFIRM -> container.uploadEnergy();
                                case UPGRADE_UPLOAD_CONFIRM -> container.uploadUpgrade();
                                default -> applied = CloudworksEditorActions.apply(
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
