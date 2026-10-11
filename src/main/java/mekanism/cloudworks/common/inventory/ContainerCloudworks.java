package mekanism.cloudworks.common.inventory;

import mekanism.api.inventory.IInventorySlot;
import mekanism.cloudworks.api.CloudworksEnabled;
import mekanism.cloudworks.common.capability.CloudworksCapabilities;
import mekanism.cloudworks.common.content.CloudworksTerminalHost;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.cloudworks.common.inventory.slot.CloudworksPlayerInventorySlots;
import mekanism.cloudworks.common.network.CloudworksNetwork;
import mekanism.cloudworks.common.network.PacketCloudworksData;
import mekanism.cloudworks.common.network.PacketCloudworksBlueprint;
import mekanism.cloudworks.common.blueprint.CloudworksBlueprint;
import mekanism.cloudworks.common.blueprint.CloudworksBlueprintLibrary;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.service.CloudworksGuiRequest;
import mekanism.cloudworks.common.service.CloudworksGuiService;
import mekanism.cloudworks.common.service.CloudworksGuiSource;
import mekanism.cloudworks.common.service.CloudworksServerTickService;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.cloudworks.common.util.CloudworksMessages;
import mekanism.cloudworks.common.config.CloudworksConfig;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.slot.HotBarSlot;
import mekanism.common.inventory.container.slot.IInsertableSlot;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import mekanism.common.inventory.container.slot.MainInventorySlot;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

import javax.annotation.Nullable;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ContainerCloudworks extends MekanismContainer {
    /**
     * Slot order contract of {@link CloudworksTerminalHost#terminalSlots()}:
     * machine in, machine out, energy in, upgrade in, upgrade out.
     */
    public static final int SLOT_MACHINE_IN = 0, SLOT_MACHINE_OUT = 1, SLOT_ENERGY_IN = 2,
            SLOT_UPGRADE_IN = 3, SLOT_UPGRADE_OUT = 4;
    private final EntityPlayer owner;
    private final UUID cloudOwner;
    @Nullable
    private final CloudworksTerminalHost host;
    private final List<VirtualInventoryContainerSlot> terminalSlots = new ArrayList<>();
    private CloudworksPlayerDataManager.TerminalSession terminalSession;
    private boolean clicking;
    private UUID session;
    private long lastRequest;
    private long clientSequence;
    private long receivedSequence;
    private long viewVersion;
    /** Timestamp of the last committed graph sample, not unrelated delta sections. */
    private long sampleTick = -1;
    private CloudworksGuiData guiData = CloudworksGuiData.EMPTY;
    private String status = "";
    private int offset;
    private int syncTicks;
    private boolean initialGuiSync = true;
    private byte[][] lastSentData;
    private java.util.concurrent.FutureTask<CloudworksGuiService.Result> guiTask;
    private CloudworksGuiRequest runningGuiRequest, pendingGuiRequest;
    private String pendingGuiRequestStatus = "";
    private java.util.concurrent.FutureTask<mekanism.cloudworks.common.service.CloudworksResourceQuery.Result> resourceTask;
    private mekanism.cloudworks.common.service.CloudworksResourceQuery pendingResourceQuery, runningResourceQuery;
    private long sentDataGeneration, receivedDataGeneration, pendingDataGeneration;
    private int pendingDataChunk, pendingDataChunks;
    private long pendingDataSequence;
    private UUID pendingDataSession;
    private CloudworksGuiData pendingData;
    private boolean pendingGraphSample;
    private UUID graphLine;
    private java.util.List<mekanism.api.qio.resource.QIOResourceDescriptor> resourceChoices = java.util.Collections.emptyList();
    private UUID resourceLine, resourceNode;
    private int resourceOffset, resourceTotal;
    private int linesSelection = -1;
    /** 机器缓存层视图态: -1 = off, else selected type index (variants shown for it). */
    private int cacheViewType = -2;
    /** 三仓查看视图态. */
    private boolean warehousesView;
    /** 回收站查看视图态. */
    private boolean recycleView;
    private mekanism.cloudworks.common.service.CloudworksLineEditSession lineEditSession;
    private final java.util.concurrent.atomic.AtomicBoolean lifecycleClosed = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<mekanism.cloudworks.common.service.CloudworksLineEditSession>
            serverEditSession = new java.util.concurrent.atomic.AtomicReference<>();
    private long editRequest, editResultVersion;
    private long editRequestAt;
    private boolean editRequestPending;
    public int editResultOperation;
    public UUID editToken;
    public mekanism.cloudworks.common.network.data.CloudworksLineDraft editSnapshot;
    public String editError = "";
    private long blueprintRequest, blueprintVersion;
    private long blueprintRequestAt;
    private boolean blueprintBusy;
    public PacketCloudworksBlueprint.Result blueprintResult;

    public long blueprintVersion() { return blueprintVersion; }
    public boolean blueprintBusy() { return blueprintBusy; }
    public static final int TIMED_OUT_EDIT = 1, TIMED_OUT_BLUEPRINT = 2;

    public int expireTimedOutRequests() {
        long now = System.currentTimeMillis();
        int expired = 0;
        if (editRequestPending && now - editRequestAt >= 10_000) {
            editRequestPending = false;
            editRequest = Long.MIN_VALUE;
            expired |= TIMED_OUT_EDIT;
        }
        if (blueprintBusy && now - blueprintRequestAt >= 10_000) {
            blueprintBusy = false;
            blueprintRequest = Long.MIN_VALUE;
            blueprintResult = null;
            expired |= TIMED_OUT_BLUEPRINT;
        }
        return expired;
    }

    public void requestBlueprint(int operation, UUID line, UUID entry, String code,
            CloudworksBlueprint blueprint, int x, int y, java.util.Set<UUID> selection) {
        if (session == null || blueprintBusy) return;
        CloudworksNetwork.CHANNEL.sendToServer(new PacketCloudworksBlueprint.Request(
                this, operation, line, entry, code, blueprint, x, y, selection));
        blueprintRequest = clientSequence;
        blueprintBusy = true;
        blueprintRequestAt = System.currentTimeMillis();
    }

    public void acceptBlueprint(long sequence, PacketCloudworksBlueprint.Result result) {
        if (sequence != blueprintRequest) return;
        blueprintResult = result;
        blueprintBusy = false;
        blueprintRequestAt = 0;
        blueprintVersion++;
    }

    public void handleBlueprint(PacketCloudworksBlueprint.Request request) {
        UUID actor = owner.getUniqueID(), cloudOwner = ownerId();
        String actorName = owner.getName();
        boolean operator = owner.canUseCommand(2, "cloudworks");
        int dimension = owner.dimension;
        boolean queued = CloudworksServerTickService.INSTANCE.submitStructuralCommand(cloudOwner, () -> {
            PacketCloudworksBlueprint.Result result = new PacketCloudworksBlueprint.Result();
            result.operation = request.operation;
            try {
                CloudworksPlayerDataManager manager = CloudworksPlayerDataManager.INSTANCE;
                synchronized (manager.ownerLock(cloudOwner)) {
                    if (lifecycleClosed.get()) return;
                    mekanism.cloudworks.common.service.CloudworksLineEditSession edit = serverEditSession.get();
                    if (request.operation == PacketCloudworksBlueprint.PLACE || request.operation == PacketCloudworksBlueprint.PARSE
                            || request.operation == PacketCloudworksBlueprint.PRECHECK
                            || request.operation == PacketCloudworksBlueprint.REQUIREMENTS || request.operation == PacketCloudworksBlueprint.FILL
                            || request.operation == PacketCloudworksBlueprint.CANCEL) {
                        if (edit == null || !edit.token.equals(request.token) || !edit.line.equals(request.line))
                            throw new IllegalStateException("cloudworks.error.stale");
                    }
                    CloudworksBlueprintLibrary library = CloudworksBlueprintLibrary.INSTANCE;
                    switch (request.operation) {
                        case PacketCloudworksBlueprint.EXPORT -> {
                            if (!actor.equals(cloudOwner)) throw new IllegalStateException("cloudworks.error.permission");
                            mekanism.cloudworks.common.data.ProductionLineData selectedLine =
                                    request.line == null ? null : manager.ownedLine(cloudOwner, request.line);
                            if (selectedLine == null) throw new IllegalStateException("cloudworks.error.not_found");
                            mekanism.cloudworks.common.network.data.CloudworksLineDraft draft =
                                    edit != null && edit.line.equals(request.line) ? edit.snapshot()
                                            : mekanism.cloudworks.common.service.CloudworksLineConfiguration.capture(selectedLine);
                            if (!request.selection.isEmpty()) draft =
                                    mekanism.cloudworks.common.service.CloudworksDraftGeometry.selection(draft, request.selection);
                            CloudworksBlueprint blueprint = new CloudworksBlueprint(actorName, actor, System.currentTimeMillis(), draft);
                            blueprint.validate();
                            result.code = library.put(blueprint).toString();
                        }
                        case PacketCloudworksBlueprint.LIST -> {
                            int offset = Math.max(0, Math.min(request.x, Integer.MAX_VALUE - CloudworksBlueprintLibrary.PAGE_SIZE));
                            CloudworksBlueprintLibrary.Page page = library.entries(offset, CloudworksBlueprintLibrary.PAGE_SIZE);
                            result.pageOffset = offset;
                            result.nextOffset = page.nextOffset;
                            result.hasMore = page.hasMore;
                            for (CloudworksBlueprintLibrary.Entry entry : page.entries) {
                                CloudworksBlueprint blueprint = entry.blueprint;
                                result.entries.add(new PacketCloudworksBlueprint.Metadata(entry.id, blueprint.exporterUUID,
                                        blueprint.draft().name, blueprint.exporterName, blueprint.exportTime,
                                        operator || actor.equals(blueprint.exporterUUID)));
                            }
                        }
                        case PacketCloudworksBlueprint.READ -> result.blueprint = library.read(request.entry);
                        case PacketCloudworksBlueprint.COPY -> result.code = library.code(request.entry);
                        case PacketCloudworksBlueprint.DELETE -> library.delete(request.entry, actor, operator);
                        case PacketCloudworksBlueprint.PARSE -> {
                            result.blueprint = library.readCode(request.code);
                            manager.precheckBlueprint(edit, result.blueprint);
                        }
                        case PacketCloudworksBlueprint.PRECHECK -> {
                            // Already-read previews remain independent of later library changes.
                            result.blueprint = CloudworksBlueprint.fromBytes(request.payload);
                            manager.precheckBlueprint(edit, result.blueprint);
                        }
                        case PacketCloudworksBlueprint.PLACE -> {
                            CloudworksBlueprint blueprint = CloudworksBlueprint.fromBytes(request.payload);
                            manager.placeBlueprint(edit, blueprint, request.x, request.y, dimension);
                            result.draft = manager.lineEditSnapshot(edit);
                            result.filled = edit.pending == null;
                        }
                        case PacketCloudworksBlueprint.REQUIREMENTS -> result.requirements.addAll(manager.blueprintRequirements(edit));
                        case PacketCloudworksBlueprint.FILL, PacketCloudworksBlueprint.CANCEL -> {
                            result.filled = manager.fillBlueprint(edit, request.operation == PacketCloudworksBlueprint.CANCEL, dimension);
                            if (result.filled) result.draft = manager.lineEditSnapshot(edit);
                            if (!result.filled) result.requirements.addAll(manager.blueprintRequirements(edit));
                        }
                        default -> throw new IllegalArgumentException("cloudworks.error.editor");
                    }
                }
            } catch (RuntimeException failure) { result.error = CloudworksMessages.errorKey(failure); }
            CloudworksNetwork.hopToMainThread(() -> {
                if (lifecycleClosed.get() || owner.openContainer != this || !canInteractWith(owner)) return;
                sendData(result.error);
                CloudworksNetwork.CHANNEL.sendTo(new PacketCloudworksBlueprint.Response(this, request.sequence, result), (EntityPlayerMP) owner);
            });
        });
        if (!queued) {
            PacketCloudworksBlueprint.Result result = new PacketCloudworksBlueprint.Result();
            result.operation = request.operation; result.error = "cloudworks.error.busy";
            CloudworksNetwork.CHANNEL.sendTo(new PacketCloudworksBlueprint.Response(this, request.sequence, result), (EntityPlayerMP) owner);
        }
    }

    public long editResultVersion() {
        return editResultVersion;
    }

    public void requestLineEdit(int operation, UUID line,
            mekanism.cloudworks.common.network.data.CloudworksLineDraft draft) {
        if (session == null) return;
        if (draft != null) {
            net.minecraft.network.PacketBuffer buffer = new net.minecraft.network.PacketBuffer(io.netty.buffer.Unpooled.buffer());
            try {
                mekanism.cloudworks.common.network.CloudworksLineDraftCodec.write(buffer, draft);
                if (buffer.readableBytes() > mekanism.cloudworks.common.network.CloudworksGuiCodec.MAX_BYTES - 256)
                    throw new IllegalArgumentException("cloudworks.error.editor_size");
            } catch (RuntimeException oversized) {
                editError = "cloudworks.error.editor_size";
                editResultOperation = operation;
                editResultVersion++;
                return;
            } finally {
                buffer.release();
            }
        }
        CloudworksNetwork.CHANNEL.sendToServer(new mekanism.cloudworks.common.network.PacketCloudworksLineEdit.Message(
                this, operation, line, editToken, draft));
        editRequest = clientSequence;
        editRequestPending = true;
        editRequestAt = System.currentTimeMillis();
    }

    public void acceptLineEdit(long sequence, int operation, UUID token,
            mekanism.cloudworks.common.network.data.CloudworksLineDraft draft, String error) {
        if (sequence != editRequest) return;
        editRequestPending = false;
        editRequestAt = 0;
        editToken = token;
        editSnapshot = draft;
        editError = error;
        editResultOperation = operation;
        editResultVersion++;
    }

    public void handleLineEdit(int operation, UUID line, UUID token,
            mekanism.cloudworks.common.network.data.CloudworksLineDraft draft, long sequence) {
        // Runtime §3.3: the editor's BEGIN/RELOAD/COMMIT do full-line NBT
        // decode/encode inside the manager; that work runs on the structural
        // command worker. Container and session fields are captured here on the
        // main thread and only written back inside the result hop.
        UUID cloudOwner = ownerId();
        int dimension = owner.dimension;
        mekanism.cloudworks.common.service.CloudworksLineEditSession currentSession = lineEditSession;
        boolean queued = mekanism.cloudworks.common.service.CloudworksServerTickService.INSTANCE
                .submitStructuralCommand(cloudOwner, () -> {
            String error = "";
            mekanism.cloudworks.common.network.data.CloudworksLineDraft snapshot = null;
            mekanism.cloudworks.common.service.CloudworksLineEditSession session = currentSession;
            try {
                CloudworksPlayerDataManager manager = CloudworksPlayerDataManager.INSTANCE;
                synchronized (manager.ownerLock(cloudOwner)) {
                    if (lifecycleClosed.get()) return;
                    if (operation == mekanism.cloudworks.common.network.PacketCloudworksLineEdit.BEGIN
                            || operation == mekanism.cloudworks.common.network.PacketCloudworksLineEdit.RELOAD) {
                        if (operation == mekanism.cloudworks.common.network.PacketCloudworksLineEdit.RELOAD
                                && session != null) {
                            try {
                                if (!manager.abortLineEdit(session))
                                    mekanism.common.Mekanism.logger.warn(
                                            "Cloudworks line {} changed during editing; leaving its current state unchanged",
                                            session.line);
                            } catch (RuntimeException failure) {
                                mekanism.common.Mekanism.logger.error(
                                        "Could not restore Cloudworks line {} after editor closure",
                                        session.line, failure);
                            }
                            session = null;
                        }
                        if (session != null && !session.line.equals(line))
                            throw new IllegalStateException("cloudworks.error.busy");
                        if (session == null) session = manager.beginLineEdit(cloudOwner, line);
                        snapshot = manager.lineEditSnapshot(session);
                    } else {
                        if (session == null || !session.token.equals(token)
                                || !session.line.equals(line)) throw new IllegalStateException("cloudworks.error.stale");
                        if (operation == mekanism.cloudworks.common.network.PacketCloudworksLineEdit.COMMIT) {
                            manager.updateLineDraft(session, draft, dimension);
                            manager.commitLineEdit(session, session.current, dimension);
                            session = null;
                        } else if (operation == mekanism.cloudworks.common.network.PacketCloudworksLineEdit.SYNC) {
                            manager.updateLineDraft(session, draft, dimension);
                        } else throw new IllegalArgumentException("cloudworks.error.editor");
                        if (session != null) snapshot = manager.lineEditSnapshot(session);
                    }
                    serverEditSession.set(session);
                    if (lifecycleClosed.get() && session != null) {
                        manager.abortLineEdit(session);
                        serverEditSession.compareAndSet(session, null);
                        session = null;
                    }
                }
            } catch (RuntimeException failure) {
                error = CloudworksMessages.errorKey(failure);
                if (session != null) snapshot = CloudworksPlayerDataManager.INSTANCE.lineEditSnapshot(session);
            }
            final mekanism.cloudworks.common.service.CloudworksLineEditSession result = session;
            final String resultError = error;
            final mekanism.cloudworks.common.network.data.CloudworksLineDraft resultSnapshot = snapshot;
            mekanism.cloudworks.common.network.CloudworksNetwork.hopToMainThread(() -> {
                if (owner.openContainer != this || !canInteractWith(owner))
                    return;
                lineEditSession = result;
                if (resultError.isEmpty() && (operation == mekanism.cloudworks.common.network.PacketCloudworksLineEdit.BEGIN
                        || operation == mekanism.cloudworks.common.network.PacketCloudworksLineEdit.RELOAD)) {
                    selectGraph(line);
                    selectCacheView(-1);
                }
                sendData(resultError);
                CloudworksNetwork.CHANNEL.sendTo(new mekanism.cloudworks.common.network.PacketCloudworksLineEditResult.Message(
                        this, sequence, operation, result == null ? null : result.token, resultSnapshot, resultError),
                        (EntityPlayerMP) owner);
            });
        });
        if (!queued) {
            // Saturated command queue: answer immediately so the client editor
            // never waits on a result that will not come.
            sendData("cloudworks.error.busy");
            CloudworksNetwork.CHANNEL.sendTo(new mekanism.cloudworks.common.network.PacketCloudworksLineEditResult.Message(
                    this, sequence, operation, lineEditSession == null ? null : lineEditSession.token, null,
                    "cloudworks.error.busy"), (EntityPlayerMP) owner);
        }
    }

    public ContainerCloudworks(InventoryPlayer inventory) {
        this(inventory, null);
    }

    /**
     * Full form: the optional host carries the five terminal functional slots
     * (terminal item NBT or dashboard tile NBT — never the cloud record).
     */
    public ContainerCloudworks(InventoryPlayer inventory, @Nullable CloudworksTerminalHost host) {
        super(inventory);
        owner = inventory.player;
        this.host = host;
        cloudOwner = host instanceof mekanism.cloudworks.common.tile.TileEntityCloudDashboard dashboard
                ? dashboard.getSecurity().getOwnerUUID() : owner.getUniqueID();
        if (!owner.world.isRemote) session = UUID.randomUUID();
        if (!owner.world.isRemote && host != null) {
            try { terminalSession = CloudworksPlayerDataManager.INSTANCE.openTerminal(ownerId(), host); }
            catch (RuntimeException unavailable) { status = "cloudworks.error.storage"; }
        }
        if (dashboard() != null) dashboard().addContainerTrackers(this);
        addSlotsAndOpen();
    }

    @Override
    protected void openInventory(@Nonnull InventoryPlayer inventory) {
        super.openInventory(inventory);
        if (dashboard() != null) {
            dashboard().open(inventory.player);
            dashboard().openInventory(inventory.player);
        }
    }

    @Override
    protected void closeInventory(@Nonnull EntityPlayer player) {
        super.closeInventory(player);
        if (dashboard() != null) {
            dashboard().close(player);
            dashboard().closeInventory(player);
        }
    }

    @Override
    protected void addSlots() {
        super.addSlots();
        if (host == null) return;
        List<IInventorySlot> slots = host.terminalSlots();
        for (int index = 0; index < slots.size(); index++) {
            IInventorySlot slot = slots.get(index);
            if (!(slot instanceof mekanism.common.inventory.slot.BasicInventorySlot basic)) continue;
            VirtualInventoryContainerSlot containerSlot = new VirtualInventoryContainerSlot(
                    basic, terminalWindowData(index), null, basic::setStackUnchecked);
            terminalSlots.add(containerSlot);
            addSlot(containerSlot);
        }
    }

    private static SelectedWindowData terminalWindowData(int slot) {
        return switch (slot) {
            case SLOT_MACHINE_IN, SLOT_MACHINE_OUT -> CloudworksWindowTypes.CLOUD_CACHE_DATA;
            case SLOT_ENERGY_IN -> CloudworksWindowTypes.CLOUD_ENERGY_DATA;
            case SLOT_UPGRADE_IN, SLOT_UPGRADE_OUT -> CloudworksWindowTypes.CLOUD_UPGRADE_DATA;
            default -> CloudworksWindowTypes.CLOUD_TERMINAL_DATA;
        };
    }

    public VirtualInventoryContainerSlot getTerminalSlot(int index) {
        return index >= 0 && index < terminalSlots.size() ? terminalSlots.get(index) : null;
    }

    public List<IVirtualSlot> getPlayerInventorySlots() {
        List<IVirtualSlot> slots = new ArrayList<>(36);
        for (MainInventorySlot slot : mainInventorySlots) {
            slots.add((IVirtualSlot) slot);
        }
        for (HotBarSlot slot : hotBarSlots) {
            slots.add((IVirtualSlot) slot);
        }
        return slots;
    }

    @Override
    public net.minecraft.item.ItemStack slotClick(int slotId, int dragType, net.minecraft.inventory.ClickType clickType, EntityPlayer player) {
        if (!player.world.isRemote && !canInteractWith(player)) return ItemStack.EMPTY;
        if (host instanceof mekanism.cloudworks.common.content.CloudworksTerminalItemHost itemHost
                && clickType == net.minecraft.inventory.ClickType.SWAP) {
            mekanism.common.inventory.container.item.ItemStackSlotAccess access = itemHost.itemAccess();
            // Same backing-slot protection as PortableQIODashboardContainer.
            if (access.getHand() == net.minecraft.util.EnumHand.OFF_HAND && dragType == 40
                    || access.getHand() == net.minecraft.util.EnumHand.MAIN_HAND
                    && dragType >= 0 && dragType < hotBarSlots.size()
                    && !hotBarSlots.get(dragType).canTakeStack(player)) return ItemStack.EMPTY;
        }
        if (slotId >= 0 && slotId < inventorySlots.size()) {
            SelectedWindowData selected = player.world.isRemote
                    ? getSelectedWindow() : getSelectedWindow(player.getUniqueID());
            if (inventorySlots.get(slotId) instanceof IInsertableSlot slot && !slot.exists(selected)) {
                return ItemStack.EMPTY;
            }
            if (inventorySlots.get(slotId) instanceof VirtualInventoryContainerSlot virtual
                    && terminalSlots.contains(virtual)) {
                if (clickType == net.minecraft.inventory.ClickType.SWAP) {
                    return net.minecraft.item.ItemStack.EMPTY;
                }
            }
        }
        if (owner.world.isRemote || host == null) return super.slotClick(slotId, dragType, clickType, player);
        CloudworksPlayerDataManager manager = CloudworksPlayerDataManager.INSTANCE;
        if (!manager.terminalReady(terminalSession)) return ItemStack.EMPTY;
        mekanism.cloudworks.common.data.CloudworksTerminalState before =
                mekanism.cloudworks.common.data.CloudworksTerminalState.capture(ownerId(), host);
        List<ItemStack> inventoryBefore = new ArrayList<>();
        for (int i = 0; i < player.inventory.getSizeInventory(); i++)
            inventoryBefore.add(player.inventory.getStackInSlot(i).copy());
        ItemStack cursorBefore = player.inventory.getItemStack().copy();
        try {
            clicking = true;
            ItemStack result = super.slotClick(slotId, dragType, clickType, player);
            host.persist();
            // Persist ordinary clicks in the host; cloud transfers alone own the durable journal.
            mekanism.cloudworks.common.data.CloudworksTerminalState after =
                    mekanism.cloudworks.common.data.CloudworksTerminalState.capture(ownerId(), host);
            boolean changed = !before.write().getTag("slots").equals(after.write().getTag("slots"));
            if (changed && !after.stack(SLOT_UPGRADE_IN).isEmpty()
                    && !ItemStack.areItemStacksEqual(before.stack(SLOT_UPGRADE_IN), after.stack(SLOT_UPGRADE_IN))) {
                manager.processTerminalUpgradeInput(terminalSession);
            }
            return result;
        } catch (RuntimeException refusal) {
            before.restoreSlots(host);
            for (int i = 0; i < inventoryBefore.size(); i++) player.inventory.setInventorySlotContents(i, inventoryBefore.get(i));
            player.inventory.setItemStack(cursorBefore);
            persistIdentityOnClose();
            return ItemStack.EMPTY;
        } finally { clicking = false; }
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int slotId) {
        if (!owner.world.isRemote && host != null && !clicking) return ItemStack.EMPTY;
        return super.transferStackInSlot(player, slotId);
    }

    @Override
    protected void addInventorySlots(@Nonnull InventoryPlayer inventory) {
        int x = getInventoryXOffset();
        int y = getInventoryYOffset();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(CloudworksPlayerInventorySlots.main(this, inventory,
                        9 + column + row * 9, x + column * 18, y + row * 18));
            }
        }
        for (int column = 0; column < InventoryPlayer.getHotbarSize(); column++) {
            addSlot(createHotBarSlot(inventory, column, x + column * 18, y + 58));
        }
    }

    @Override
    protected HotBarSlot createHotBarSlot(
            @Nonnull InventoryPlayer inventory, int index, int x, int y) {
        boolean locked = host instanceof mekanism.cloudworks.common.content.CloudworksTerminalItemHost itemHost
                && itemHost.itemAccess().getHand() == net.minecraft.util.EnumHand.MAIN_HAND
                && index == itemHost.itemAccess().getSlot();
        return CloudworksPlayerInventorySlots.hotbar(this, inventory, index, x, y, locked);
    }

    @Override
    public void onContainerClosed(EntityPlayer player) {
        lifecycleClosed.set(true);
        if (guiTask != null) guiTask.cancel(false);
        guiTask = null;
        runningGuiRequest = pendingGuiRequest = null;
        cancelResourceQuery();
        if (!player.world.isRemote) finishLineEditSession();
        CloudworksPlayerDataManager.INSTANCE.closeTerminal(terminalSession);
        super.onContainerClosed(player);
        if (!player.world.isRemote && host != null && host.available()) persistIdentityOnClose();
    }

    private void persistIdentityOnClose() {
        try { host.persistIdentity(); }
        catch (RuntimeException pendingOrFailed) {
            mekanism.common.Mekanism.logger.warn("Cloudworks terminal identity save deferred", pendingOrFailed);
        }
    }

    private void finishLineEditSession() {
        mekanism.cloudworks.common.service.CloudworksLineEditSession ending = serverEditSession.getAndSet(null);
        if (ending == null) ending = lineEditSession;
        lineEditSession = null;
        if (ending == null) return;
        final mekanism.cloudworks.common.service.CloudworksLineEditSession closedSession = ending;
        if (!CloudworksServerTickService.INSTANCE.submitStructuralCommand(closedSession.owner, () -> {
            try { CloudworksPlayerDataManager.INSTANCE.abortLineEdit(closedSession); }
            catch (RuntimeException failure) {
                mekanism.common.Mekanism.logger.error("Could not restore Cloudworks line after editor closure", failure);
            }
        })) CloudworksPlayerDataManager.INSTANCE.abortLineEdit(closedSession);
    }

    private void updateTerminalSession() {
        if (host == null || owner.world.isRemote || !canInteractWith(owner)) return;
        try {
            CloudworksPlayerDataManager manager = CloudworksPlayerDataManager.INSTANCE;
            if (terminalSession == null)
                terminalSession = manager.openTerminal(ownerId(), host);
            // Apply completed transfers before vanilla compares and syncs the virtual slots.
            if (manager.terminalReady(terminalSession)
                    && !host.terminalSlots().get(SLOT_UPGRADE_IN).isEmpty()) {
                manager.processTerminalUpgradeInput(terminalSession);
            }
        }
        catch (RuntimeException unavailable) { status = "cloudworks.error.storage"; }
    }

    public void downloadMachines(int typeIndex, int variantIndex, boolean shift) {
        if (host == null || owner.world.isRemote) return;
        CloudworksPlayerDataManager.INSTANCE.terminalDownloadMachines(terminalSession, typeIndex, variantIndex, shift);
    }

    public void uploadMachines() {
        if (host == null || owner.world.isRemote) return;
        try {
            CloudworksPlayerDataManager.INSTANCE.processTerminalMachineInput(terminalSession);
        } catch (RuntimeException refusal) {
            status = CloudworksMessages.errorKey(refusal);
        }
    }

    public void uploadEnergy() {
        if (host == null || owner.world.isRemote) return;
        try {
            CloudworksPlayerDataManager.INSTANCE.processTerminalEnergyInput(terminalSession);
        } catch (RuntimeException refusal) {
            status = CloudworksMessages.errorKey(refusal);
        }
    }

    public void uploadUpgrade() {
        if (host == null || owner.world.isRemote) return;
        try {
            CloudworksPlayerDataManager.INSTANCE.processTerminalUpgradeInput(terminalSession);
        } catch (RuntimeException refusal) {
            status = CloudworksMessages.errorKey(refusal);
        }
    }

    public void downloadUpgrade(boolean speed, boolean shift) {
        if (host == null || owner.world.isRemote) return;
        CloudworksPlayerDataManager.INSTANCE.terminalDownloadUpgrade(terminalSession, speed, shift);
    }

    public static boolean enabled(EntityPlayer player) {
        if (CloudworksCapabilities.ENABLED == null) return false;
        CloudworksEnabled cap = player.getCapability(CloudworksCapabilities.ENABLED, null);
        return cap != null && cap.isEnabled();
    }

    public void selectGraph(UUID id) {
        cancelResourceQuery();
        graphLine = id;
        resourceChoices = java.util.Collections.emptyList();
        resourceLine = resourceNode = null;
    }

    /** 机器缓存层视图: -2 关闭, -1 只显示类型, 否则显示选中类型的变体. */
    public void selectCacheView(int typeIndex) {
        cacheViewType = typeIndex;
    }

    /** 三仓查看视图开关. */
    public void setWarehousesView(boolean enabled) {
        warehousesView = enabled;
    }

    /** 回收站查看视图开关. */
    public void setRecycleView(boolean enabled) {
        recycleView = enabled;
    }

    public void sendEditorAction(CloudworksNetwork.Operation operation, UUID line, UUID node, String value) {
        if (session == null) return;
        CloudworksNetwork.CHANNEL.sendToServer(new CloudworksNetwork.Action(this, operation, line, node, value));
    }

    public mekanism.api.qio.resource.QIOResourceDescriptor resourceChoice(UUID line, UUID node, int index) {
        if (!java.util.Objects.equals(line, resourceLine) || !java.util.Objects.equals(node, resourceNode)
                || index < 0 || index >= resourceChoices.size())
            throw new IllegalArgumentException("cloudworks.error.stale");
        return resourceChoices.get(index);
    }

    public void loadResources(UUID line, UUID node, String query, int offset) {
        pendingResourceQuery = new mekanism.cloudworks.common.service.CloudworksResourceQuery(
                CloudworksPlayerDataManager.INSTANCE.guiSource(ownerId()), line, node, query, offset);
        resourceChoices = java.util.Collections.emptyList();
        resourceOffset = resourceTotal = 0;
        resourceLine = line;
        resourceNode = node;
        pumpResourceQuery();
    }

    private void cancelResourceQuery() {
        if (resourceTask != null) resourceTask.cancel(true);
        resourceTask = null;
        pendingResourceQuery = runningResourceQuery = null;
    }

    private void pumpResourceQuery() {
        if (resourceTask != null && resourceTask.isDone()) {
            try {
                mekanism.cloudworks.common.service.CloudworksResourceQuery.Result result = resourceTask.get();
                if (pendingResourceQuery == null && java.util.Objects.equals(resourceLine, runningResourceQuery.line)
                        && java.util.Objects.equals(resourceNode, runningResourceQuery.context)) {
                    resourceChoices = result.resources;
                    resourceOffset = result.offset;
                    resourceTotal = result.total;
                    sendData("");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException failure) {
                if (pendingResourceQuery == null) sendData("cloudworks.error.storage");
            } finally {
                resourceTask = null;
                runningResourceQuery = null;
            }
        }
        if (resourceTask == null && pendingResourceQuery != null) {
            java.util.concurrent.FutureTask<mekanism.cloudworks.common.service.CloudworksResourceQuery.Result> submitted =
                    CloudworksServerTickService.INSTANCE.submitResourceQuery(pendingResourceQuery);
            if (submitted != null) {
                resourceTask = submitted;
                runningResourceQuery = pendingResourceQuery;
                pendingResourceQuery = null;
            }
        }
    }

    public UUID ownerId() {
        return cloudOwner;
    }

    @Nullable
    public mekanism.cloudworks.common.tile.TileEntityCloudDashboard dashboard() {
        return host instanceof mekanism.cloudworks.common.tile.TileEntityCloudDashboard dashboard ? dashboard : null;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        if (lifecycleClosed.get()) return false;
        if (player != owner) return false;
        if (host != null && !host.available()) return false;
        if (host instanceof mekanism.cloudworks.common.tile.TileEntityCloudDashboard dashboard
                && (player.world != dashboard.getWorld()
                || player.getDistanceSq(dashboard.getPos()) > 64
                || !player.world.isRemote && (cloudOwner == null
                    || !cloudOwner.equals(dashboard.getSecurity().getOwnerUUID())
                    || !mekanism.common.util.SecurityUtils.canAccess(player, dashboard)))) return false;
        if (player.world.isRemote) return true;
        return enabled(player);
    }

    public boolean accepts(EntityPlayer player, int window, UUID token, long sequence) {
        if (player.openContainer != this || windowId != window || !canInteractWith(player)
                || session == null || !session.equals(token) || sequence <= lastRequest) return false;
        lastRequest = sequence;
        return true;
    }

    public UUID session() {
        return session;
    }

    public long nextSequence() {
        return ++clientSequence;
    }

    public long viewVersion() {
        return viewVersion;
    }

    public long revision() {
        return guiData.overview.revision;
    }

    public CloudworksGuiData guiData() {
        return guiData;
    }

    public int upgradePool(boolean speed) {
        return speed ? guiData.overview.speedUpgradePool : guiData.overview.energyUpgradePool;
    }

    public String status() {
        return status;
    }

    public void setOffset(int value) {
        offset = Math.max(0, value);
    }

    public int offset() {
        return offset;
    }

    public void sendAction(mekanism.cloudworks.common.network.CloudworksNetwork.Operation operation, UUID line, String name) {
        mekanism.cloudworks.common.network.CloudworksNetwork.CHANNEL.sendToServer(
                new mekanism.cloudworks.common.network.CloudworksNetwork.Action(this, operation, line, name));
    }

    public void sendNodeAction(mekanism.cloudworks.common.network.CloudworksNetwork.Operation operation, UUID line, UUID node) {
        mekanism.cloudworks.common.network.CloudworksNetwork.CHANNEL.sendToServer(
                new mekanism.cloudworks.common.network.CloudworksNetwork.Action(this, operation, line, node, ""));
    }

    public UUID lineIdAt(int index) {
        List<CloudworksGuiData.Line> list = guiData.overview.lines;
        return index < 0 || index >= list.size() ? null : list.get(index).id;
    }

    public String lineNameAt(int index) {
        List<CloudworksGuiData.Line> list = guiData.overview.lines;
        return index < 0 || index >= list.size() ? "" : list.get(index).name;
    }

    public java.util.List<String> nodeLabels() {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (CloudworksGuiData.Node node : guiData.graph.nodes) {
            values.add(node.name + " x" + node.count + " " + node.state);
        }
        return values;
    }

    public void setLineSelection(int selection) {
        linesSelection = selection;
    }

    public UUID nodeIdAt(int nodeIndex) {
        List<CloudworksGuiData.Node> nodes = guiData.graph.nodes;
        return nodeIndex < 0 || nodeIndex >= nodes.size() ? null : nodes.get(nodeIndex).id;
    }

    @Override
    public void detectAndSendChanges() {
        updateTerminalSession();
        super.detectAndSendChanges();
        if (!owner.world.isRemote) {
            pumpResourceQuery();
            pumpGuiData();
            int interval = CloudworksConfig.settings().guiRefreshIntervalTicks;
            if (initialGuiSync || ++syncTicks >= interval) {
                initialGuiSync = false;
                syncTicks = 0;
                sendData("");
            }
        }
    }

    public void sendData(String result) {
        if (!(owner instanceof EntityPlayerMP player) || !canInteractWith(player)) return;
        pumpGuiData();
        CloudworksGuiSource source;
        try {
            source = CloudworksPlayerDataManager.INSTANCE.guiSource(ownerId());
        } catch (RuntimeException error) {
            source = null;
            result = "cloudworks.error.storage";
        }
        if (result.isEmpty() && pendingGuiRequest != null && pendingGuiRequest.sequence == lastRequest)
            result = pendingGuiRequestStatus;
        if (result.isEmpty() && runningGuiRequest != null && runningGuiRequest.sequence == lastRequest)
            result = runningGuiRequest.status();
        pendingGuiRequestStatus = result;
        pendingGuiRequest = new CloudworksGuiRequest(source, offset, graphLine, cacheViewType, warehousesView,
                recycleView, resourceChoices, resourceOffset, resourceTotal, resourceNode,
                windowId, session, lastRequest, result);
        pumpGuiData();
    }

    private void pumpGuiData() {
        if (!(owner instanceof EntityPlayerMP player) || owner.openContainer != this || !canInteractWith(player)) return;
        if (guiTask != null && guiTask.isDone()) {
            try {
                CloudworksGuiService.Result completed = guiTask.get();
                boolean superseded = pendingGuiRequest != null && (pendingGuiRequest.sequence > runningGuiRequest.sequence
                        || !pendingGuiRequest.sameView(runningGuiRequest));
                if (!superseded) {
                    guiData = completed.data;
                    offset = guiData.overview.offset;
                    for (PacketCloudworksData.Message message : completed.packets)
                        CloudworksNetwork.CHANNEL.sendTo(message, player);
                    lastSentData = completed.encoded;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException failure) {
                status = "cloudworks.error.storage";
            } finally {
                guiTask = null;
                runningGuiRequest = null;
            }
        }
        if (guiTask == null && pendingGuiRequest != null) {
            java.util.concurrent.FutureTask<CloudworksGuiService.Result> submitted =
                    CloudworksServerTickService.INSTANCE.submitGui(pendingGuiRequest, sentDataGeneration + 1, lastSentData);
            if (submitted != null) {
                sentDataGeneration++;
                guiTask = submitted;
                runningGuiRequest = pendingGuiRequest;
                pendingGuiRequest = null;
                pendingGuiRequestStatus = "";
            }
        }
    }

    public void acceptData(int window, UUID token, long sequence, long generation,
            int chunkIndex, int chunkCount, CloudworksGuiData value, String message) {
        if (!owner.world.isRemote || owner.openContainer != this || windowId != window
                || session != null && !session.equals(token) || sequence < receivedSequence
                || generation <= receivedDataGeneration) return;
        if (chunkIndex == 0) {
            if (generation <= pendingDataGeneration) return;
            pendingDataGeneration = generation;
            pendingDataSequence = sequence;
            pendingDataSession = token;
            pendingDataChunk = 0;
            pendingDataChunks = chunkCount;
            pendingData = guiData;
            pendingGraphSample = false;
        }
        if (generation != pendingDataGeneration || chunkIndex != pendingDataChunk
                || chunkCount != pendingDataChunks || sequence != pendingDataSequence
                || !token.equals(pendingDataSession) || pendingData == null) return;
        pendingData = pendingData.apply(value);
        pendingGraphSample |= value.graph != null;
        if (++pendingDataChunk != pendingDataChunks) return;
        session = token;
        guiData = pendingData;
        pendingData = null;
        receivedDataGeneration = generation;
        receivedSequence = sequence;
        if (pendingGraphSample) sampleTick = owner.world.getTotalWorldTime();
        offset = guiData.overview.offset;
        // Commit a complete update before acknowledging its operation.
        if (sequence >= clientSequence) {
            status = message;
            viewVersion++;
        }
    }

    public long sampleTick() {
        return sampleTick;
    }

}
