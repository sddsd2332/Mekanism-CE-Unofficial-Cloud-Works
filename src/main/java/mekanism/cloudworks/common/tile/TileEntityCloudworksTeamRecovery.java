package mekanism.cloudworks.common.tile;

import mekanism.api.Action;
import mekanism.api.gas.GasStack;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.api.qio.resource.QIOResourceTransferAdapter;
import mekanism.api.qio.resource.QIOResourceTransferAdapterRegistry;
import mekanism.api.transmitters.TransmissionType;
import mekanism.cloudworks.common.content.CloudworksHybridTank;
import mekanism.cloudworks.common.data.CloudworksInventory;
import mekanism.cloudworks.common.tile.prefab.TileEntityCloudworksPort;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.content.qio.QIOResourceEntry;
import mekanism.common.inventory.slot.gas.GasInventorySlot;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.util.CapabilityUtils;
import mekanism.common.util.FluidContainerUtils;
import mekanism.common.util.MekanismUtils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.FluidTankProperties;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import io.netty.buffer.ByteBuf;
import mekanism.api.gas.Gas;
import mekanism.api.gas.GasTankInfo;
import mekanism.api.gas.IGasHandler;
import mekanism.api.TileNetworkList;
import mekanism.cloudworks.common.content.CloudworksRecoveryInventoryTransfer;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.common.PacketHandler;
import mekanism.common.util.ItemDataUtils;
import mekanism.common.util.SecurityUtils;
import net.minecraft.block.Block;
import net.minecraftforge.common.capabilities.Capability;

/**
 * Team recovery machine: an output-port tile bound to a DISSOLVED team's cloud
 * domain instead of its owner's. The dissolved estate is drained batch by batch
 * into a descriptor-generic buffer (QIO storage semantics — every family,
 * including addon codecs, is preserved), and everything is exposed through
 * a take-only dynamic resource grid plus QIO-exporter style side output
 * (items/fluids/gases through adjacent capabilities, custom families through
 * {@link QIOResourceTransferAdapterRegistry}).
 */
public final class TileEntityCloudworksTeamRecovery extends TileEntityCloudworksPort {

    /**
     * Per dynamic grid slot interaction cap (user ruling): items 8192, chemicals 8192000.
     */
    public static final long ITEM_SLOT_CAP = 8_192L;
    public static final long CHEMICAL_SLOT_CAP = 8_192_000L;
    public static final int DRAIN_INTERVAL_TICKS = 20;
    public static final int DRAIN_BATCH_TYPES = 64;
    public static final int EXPORT_INTERVAL_TICKS = 10;
    public static final int EXPORT_MAX_TYPES = 8;

    private final CloudworksInventory buffer =
            CloudworksInventory.perResourceCapacity(Long.MAX_VALUE);
    private UUID teamOwner;
    private String recoveryStatus = "";
    private boolean estateDrained;
    private int recoveryVersion;
    private int drainDelay, exportDelay;
    private boolean drainActive = true;
    private boolean recoveryMountHeld, bufferLoaded, recoveryWriteBlocked;

    // Client mirror of the paged grid view (filled by PacketCloudworksTeamRecovery.State).
    private List<QIOResourceEntry> recoveryEntries = Collections.emptyList();
    private int recoveryOffset = -1, recoveryTotal, recoveryViewVersion = -1;

    public TileEntityCloudworksTeamRecovery() {
        super("team_cloudworks_recovery", false);
        configComponent.addItemSlotInfo(DataType.OUTPUT, getInventorySlots(null));
        configComponent.addFluidSlotInfo(DataType.OUTPUT, portTanks().stream()
                .map(CloudworksHybridTank::fluidView).collect(Collectors.toList()));
        configComponent.addGasSlotInfo(DataType.OUTPUT, portTanks().stream()
                .map(CloudworksHybridTank::gasView).collect(Collectors.toList()));
        ConfigInfo energyConfig = configComponent.getConfigInfo(TransmissionType.ENERGY);
        if (energyConfig != null) {
            energyConfig.addSlotInfo(DataType.OUTPUT, TileComponentConfig.createInfo(TransmissionType.ENERGY, false, true, Collections.singletonList(getMainEnergyContainer())));
            energyConfig.fill(DataType.OUTPUT);
        }
        for (TransmissionType transmission : new TransmissionType[]{TransmissionType.ITEM, TransmissionType.FLUID, TransmissionType.GAS}) {
            ConfigInfo config = configComponent.getConfigInfo(transmission);
            if (config != null) config.fill(DataType.OUTPUT);
        }
        ejectorComponent.setOutputData(configComponent, TransmissionType.ITEM, TransmissionType.ENERGY, TransmissionType.FLUID, TransmissionType.GAS);
    }

    public UUID recipient() {
        return getSecurity().getOwnerUUID();
    }


    public int recoveryVersion() {
        return recoveryVersion;
    }

    /**
     * Server-side buffer access; guarded by this tile (main thread + packet handlers).
     */
    public CloudworksInventory recoveryBuffer() {
        return buffer;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getCapability(@Nonnull Capability<T> capability,
                               @Nullable EnumFacing side) {
        if (super.hasCapability(capability, side) && !isCapabilityDisabled(capability, side)) {
            if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY && canExtractItemBuffer(side))
                return (T) new RecoveryItemHandler(side);
            if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY && canExtractFluid(side))
                return (T) new RecoveryFluidHandler(side);
            if (capability == Capabilities.GAS_HANDLER_CAPABILITY && canExtractGas(side))
                return (T) new RecoveryGasHandler(side);
        }
        return super.getCapability(capability, side);
    }

    private boolean canExtractItemBuffer(EnumFacing side) {
        return teamTransferAllowed()
                && !isCapabilityDisabled(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, side);
    }

    private List<QIOResourceDescriptor> descriptors(String family) {
        List<QIOResourceDescriptor> result = new ArrayList<>();
        for (QIOResourceDescriptor descriptor : buffer.snapshot().keySet())
            if (family.equals(descriptor.getFamily())) result.add(descriptor);
        return result;
    }

    private final class RecoveryItemHandler implements IItemHandler {
        private final EnumFacing side;

        private RecoveryItemHandler(EnumFacing side) {
            this.side = side;
        }

        @Override
        public int getSlots() {
            return descriptors(QIOResourceCodecs.ITEM_FAMILY).size();
        }

        @Nonnull
        @Override
        public ItemStack getStackInSlot(int slot) {
            List<QIOResourceDescriptor> entries = descriptors(QIOResourceCodecs.ITEM_FAMILY);
            if (slot < 0 || slot >= entries.size()) return ItemStack.EMPTY;
            ItemStack stack = entries.get(slot).resolveItemStackTemplate();
            if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
            stack.setCount((int) Math.min(stack.getMaxStackSize(), buffer.amount(entries.get(slot))));
            return stack;
        }

        @Nonnull
        @Override
        public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
            return stack;
        }

        @Nonnull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (!canExtractItemBuffer(side)) return ItemStack.EMPTY;
            List<QIOResourceDescriptor> entries = descriptors(QIOResourceCodecs.ITEM_FAMILY);
            if (slot < 0 || slot >= entries.size() || amount <= 0) return ItemStack.EMPTY;
            QIOResourceDescriptor descriptor = entries.get(slot);
            ItemStack template = descriptor.resolveItemStackTemplate();
            if (template == null || template.isEmpty()) return ItemStack.EMPTY;
            int count = (int) Math.min(Math.min(amount, ITEM_SLOT_CAP), Math.min(template.getMaxStackSize(), buffer.amount(descriptor)));
            if (count <= 0) return ItemStack.EMPTY;
            if (!simulate && reserveForDelivery(descriptor, count) != count) return ItemStack.EMPTY;
            template.setCount(count);
            return template;
        }

        @Override
        public int getSlotLimit(int slot) {
            return (int) ITEM_SLOT_CAP;
        }
    }

    private final class RecoveryFluidHandler implements IFluidHandler {
        private final EnumFacing side;

        private RecoveryFluidHandler(EnumFacing side) {
            this.side = side;
        }

        @Override
        public IFluidTankProperties[] getTankProperties() {
            if (!canExtractFluid(side)) return new IFluidTankProperties[0];
            List<QIOResourceDescriptor> entries = descriptors(QIOResourceCodecs.FLUID_FAMILY);
            IFluidTankProperties[] properties = new IFluidTankProperties[entries.size()];
            for (int index = 0; index < entries.size(); index++) {
                QIOResourceDescriptor descriptor = entries.get(index);
                FluidStack stack = descriptor.resolveFluidStackTemplate();
                if (stack != null) {
                    stack.amount = (int) Math.min(CHEMICAL_SLOT_CAP, buffer.amount(descriptor));
                    properties[index] = new FluidTankProperties(stack, (int) CHEMICAL_SLOT_CAP, false, true);
                }
            }
            return properties;
        }

        @Override
        public int fill(FluidStack resource, boolean doFill) {
            return 0;
        }

        @Override
        public FluidStack drain(FluidStack resource, boolean doDrain) {
            if (!canExtractFluid(side)) return null;
            if (resource == null || resource.amount <= 0) return null;
            for (QIOResourceDescriptor descriptor : descriptors(QIOResourceCodecs.FLUID_FAMILY)) {
                FluidStack template = descriptor.resolveFluidStackTemplate();
                if (template != null && template.isFluidEqual(resource))
                    return drainFluid(descriptor, template, Math.min(resource.amount, CHEMICAL_SLOT_CAP), doDrain);
            }
            return null;
        }

        @Override
        public FluidStack drain(int maxDrain, boolean doDrain) {
            if (!canExtractFluid(side)) return null;
            if (maxDrain <= 0) return null;
            for (QIOResourceDescriptor descriptor : descriptors(QIOResourceCodecs.FLUID_FAMILY)) {
                FluidStack template = descriptor.resolveFluidStackTemplate();
                if (template != null && buffer.amount(descriptor) > 0)
                    return drainFluid(descriptor, template, Math.min(maxDrain, CHEMICAL_SLOT_CAP), doDrain);
            }
            return null;
        }

        private FluidStack drainFluid(QIOResourceDescriptor descriptor, FluidStack template, long requested, boolean execute) {
            int count = (int) Math.min(requested, buffer.amount(descriptor));
            if (count <= 0 || execute && reserveForDelivery(descriptor, count) != count) return null;
            return new FluidStack(template, count);
        }
    }

    private final class RecoveryGasHandler implements IGasHandler {
        private final EnumFacing side;

        private RecoveryGasHandler(EnumFacing side) {
            this.side = side;
        }

        @Override
        public int receiveGas(EnumFacing side, GasStack stack, boolean doTransfer) {
            return 0;
        }

        @Override
        public GasStack drawGas(EnumFacing side, int amount, boolean doTransfer) {
            if (!canExtractGas(this.side)) return null;
            if (amount <= 0) return null;
            for (QIOResourceDescriptor descriptor : descriptors(QIOResourceCodecs.GAS_FAMILY)) {
                GasStack template = descriptor.resolveGasStackTemplate();
                if (template == null) continue;
                int count = (int) Math.min(Math.min(amount, CHEMICAL_SLOT_CAP), buffer.amount(descriptor));
                if (count <= 0) continue;
                if (doTransfer && reserveForDelivery(descriptor, count) != count) return null;
                return new GasStack(template.getGas(), count);
            }
            return null;
        }

        @Override
        public boolean canReceiveGas(EnumFacing side, Gas gas) {
            return false;
        }

        @Override
        public boolean canDrawGas(EnumFacing side, Gas gas) {
            if (!canExtractGas(this.side)) return false;
            for (QIOResourceDescriptor descriptor : descriptors(QIOResourceCodecs.GAS_FAMILY)) {
                GasStack stack = descriptor.resolveGasStackTemplate();
                if (stack != null && stack.getGas() == gas && buffer.amount(descriptor) > 0) return true;
            }
            return false;
        }

        @Nonnull
        @Override
        public GasTankInfo[] getTankInfo() {
            List<QIOResourceDescriptor> entries = descriptors(QIOResourceCodecs.GAS_FAMILY);
            GasTankInfo[] info = new GasTankInfo[entries.size()];
            for (int index = 0; index < entries.size(); index++) {
                QIOResourceDescriptor descriptor = entries.get(index);
                GasStack template = descriptor.resolveGasStackTemplate();
                int amount = (int) Math.min(CHEMICAL_SLOT_CAP, buffer.amount(descriptor));
                final GasStack contents = template == null ? null : new GasStack(template.getGas(), amount);
                info[index] = new GasTankInfo() {
                    @Override
                    public GasStack getGas() {
                        return contents == null ? null : new GasStack(contents.getGas(), contents.amount);
                    }

                    @Override
                    public int getStored() {
                        return contents == null ? 0 : contents.amount;
                    }

                    @Override
                    public int getMaxGas() {
                        return (int) CHEMICAL_SLOT_CAP;
                    }
                };
            }
            return info;
        }
    }

    /**
     * Display cap for one dynamic slot interaction, by descriptor family.
     */
    public static long slotCapOf(QIOResourceDescriptor descriptor) {
        String family = descriptor.getFamily();
        if (QIOResourceCodecs.FLUID_FAMILY.equals(family) || QIOResourceCodecs.GAS_FAMILY.equals(family))
            return CHEMICAL_SLOT_CAP;
        if (QIOResourceCodecs.ITEM_FAMILY.equals(family)) return ITEM_SLOT_CAP;
        // Addon families follow the fluid/gas magnitude unless their per-unit scale is item-like.
        return descriptor.getStorageUnitsPerUnit() >= 1000 ? CHEMICAL_SLOT_CAP : ITEM_SLOT_CAP;
    }

    @Override
    public boolean canPlayerAccess(EntityPlayer player) {
        return player != null && recipient() != null && recipient().equals(player.getUniqueID())
                && SecurityUtils.canAccess(player, this);
    }

    @Override
    protected boolean teamTransferAllowed() {
        return super.teamTransferAllowed() && (world == null || world.isRemote || recipient() != null);
    }

    // -----------------------------------------------------------------------------------------
    // Server tick: bind → incremental drain → exporter output → completion.
    // -----------------------------------------------------------------------------------------

    @Override
    protected void serverTick() {
        if (world == null || world.isRemote) return;
        recoveryWriteBlocked = false;
        CloudworksPlayerDataManager manager =
                CloudworksPlayerDataManager.INSTANCE;
        UUID recipient = recipient();
        if (recipient == null) return;
        if (teamOwner == null) {
            UUID candidate = manager.findDissolvedTeam(recipient);
            if (candidate == null) {
                recoveryStatus = "";
                return;
            }
            teamOwner = candidate;
        }
        if (!recoveryMountHeld) {
            recoveryMountHeld = manager.mountTeamRecovery(teamOwner, this);
            if (!recoveryMountHeld) return;
        }
        if (!bufferLoaded) {
            try {
                manager.loadTeamRecoveryBuffer(teamOwner, buffer);
                bufferLoaded = true;
                recoveryVersion++;
            } catch (RuntimeException busy) {
                manager.unmountTeamRecovery(teamOwner, this);
                recoveryMountHeld = false;
                teamOwner = null;
                return;
            }
            markDirty();
        }
        if (drainActive && --drainDelay <= 0) {
            drainDelay = DRAIN_INTERVAL_TICKS;
            try {
                if (manager.drainTeamRecoveryBatch(teamOwner, recipient, buffer, DRAIN_BATCH_TYPES)) {
                    recoveryVersion++;
                    markDirty();
                }
                double room = Math.max(0D, getMaxEnergy() - getEnergy());
                if (room > 0D) {
                    double pulled = manager.pullTeamRecoveryEnergy(teamOwner, room);
                    if (pulled > 0D) {
                        setEnergy(getEnergy() + pulled);
                        // The recovery battery is a normal output-port payload;
                        // persist its port snapshot on the same pull tick.
                        binding.flush();
                        recoveryVersion++;
                        markDirty();
                    }
                }
                recoveryStatus = manager.teamRecoveryStatus(teamOwner);
                estateDrained = manager.teamRecoveryEstateDrained(teamOwner);
                if (estateDrained && buffer.total() == 0
                        && manager.teamRecoveryEnergyRemaining(teamOwner) <= 1.0E-9D) {
                    manager.finishTeamRecovery(teamOwner, recipient);
                    releaseRecoveryMount();
                    teamOwner = null;
                    bufferLoaded = false;
                    recoveryStatus = "";
                    estateDrained = false;
                    markDirty();
                }
            } catch (RuntimeException busy) {
                // Busy domain transactions retry on the next drain interval.
            }
        }
        if (--exportDelay <= 0) {
            exportDelay = EXPORT_INTERVAL_TICKS;
            if (MekanismUtils.canFunction(this) && buffer.total() > 0) exportToAdjacent();
        }
    }

    // -----------------------------------------------------------------------------------------
    // Take path (grid interactions).
    // -----------------------------------------------------------------------------------------

    /**
     * Extracts up to {@code amount} of one resource for a player. Item families
     * materialize into the player's cursor or inventory. Fluid and gas families
     * use the native QIO container rules. Custom families have no generic 1.12
     * container interaction and therefore remain untouched.
     * Returns the amount actually delivered.
     */
    public long takeFor(EntityPlayer player, QIOResourceDescriptor descriptor, long amount) {
        return takeFor(player, descriptor, amount, false);
    }

    /**
     * Server-authoritative take path. {@code shift} selects the inventory
     * destination for item resources; fluids and gases always use the QIO
     * container rules (cursor first, otherwise main inventory plus offhand).
     */
    public long takeFor(EntityPlayer player, QIOResourceDescriptor descriptor, long amount, boolean shift) {
        if (world == null || world.isRemote || descriptor == null || amount <= 0 || buffer.amount(descriptor) <= 0)
            return 0;
        long requested = Math.min(amount, Math.min(slotCapOf(descriptor), buffer.amount(descriptor)));
        if (QIOResourceCodecs.ITEM_FAMILY.equals(descriptor.getFamily())) {
            return takeItem(player, descriptor, requested, shift);
        } else if (QIOResourceCodecs.FLUID_FAMILY.equals(descriptor.getFamily())) {
            return takeFluid(player, descriptor, requested);
        } else if (QIOResourceCodecs.GAS_FAMILY.equals(descriptor.getFamily())) {
            return takeGas(player, descriptor, requested);
        }
        // There is no safe generic container protocol for custom families in
        // 1.12; recovery is take-only and must not silently route them away.
        return 0;
    }

    private long takeItem(EntityPlayer player, QIOResourceDescriptor descriptor, long requested, boolean shift) {
            ItemStack template = descriptor.resolveItemStackTemplate();
            if (template == null || template.isEmpty() || teamOwner == null) return 0;
            int amountToReserve = (int) Math.min(Integer.MAX_VALUE, requested);
            ItemStack cursor = player.inventory.getItemStack();
            boolean toCursor = !shift && amountToReserve <= template.getMaxStackSize();
            int accepted;
            if (toCursor) {
                if (cursor.isEmpty()) accepted = Math.min(amountToReserve, template.getMaxStackSize());
                else if (ItemHandlerHelper.canItemStacksStack(cursor, template))
                    accepted = Math.min(amountToReserve, Math.max(0, template.getMaxStackSize() - cursor.getCount()));
                else return 0;
            } else {
                PlayerMainInvWrapper inventory = new PlayerMainInvWrapper(player.inventory);
                accepted = CloudworksRecoveryInventoryTransfer
                        .accepted(inventory, template, amountToReserve);
            }
            if (accepted <= 0) return 0;
            long reserved = buffer.extract(descriptor, accepted);
            if (!persistReservedBuffer()) {
                buffer.insertIgnoringTypeCap(descriptor, reserved);
                return 0;
            }
            int delivered;
            if (toCursor) {
                if (cursor.isEmpty()) {
                    ItemStack pickedUp = template.copy();
                    pickedUp.setCount((int) reserved);
                    player.inventory.setItemStack(pickedUp);
                } else {
                    cursor.grow((int) reserved);
                }
                delivered = (int) reserved;
            } else {
                PlayerMainInvWrapper inventory = new PlayerMainInvWrapper(player.inventory);
                delivered = CloudworksRecoveryInventoryTransfer
                        .insert(inventory, template, (int) reserved);
            }
            if (delivered < reserved) {
                buffer.insertIgnoringTypeCap(descriptor, reserved - delivered);
                persistReservedBuffer();
            }
            if (delivered > 0) {
                recoveryVersion++;
                markDirty();
                player.inventory.markDirty();
            }
            return delivered;
    }

    private long takeFluid(EntityPlayer player, QIOResourceDescriptor descriptor, long requested) {
        FluidStack template = descriptor.resolveFluidStackTemplate();
        if (template == null || teamOwner == null) return 0;
        ItemStack held = player.inventory.getItemStack();
        return held.isEmpty()
                ? takeFluidToInventory(player.inventory, descriptor, template, requested)
                : takeFluidToCursor(player, descriptor, template, requested);
    }

    private long takeGas(EntityPlayer player, QIOResourceDescriptor descriptor, long requested) {
        GasStack template = descriptor.resolveGasStackTemplate();
        if (template == null || teamOwner == null) return 0;
        ItemStack held = player.inventory.getItemStack();
        return held.isEmpty()
                ? takeGasToInventory(player.inventory, descriptor, template, requested)
                : takeGasToCursor(player, descriptor, template, requested);
    }

    private long takeFluidToCursor(EntityPlayer player, QIOResourceDescriptor descriptor,
                                   FluidStack template, long requested) {
        ItemStack held = player.inventory.getItemStack();
        if (held.isEmpty()) return 0;
        IFluidHandlerItem simulated = FluidContainerUtils.getUnstackedFluidHandlerCapability(held);
        int amount = boundedChemicalAmount(requested);
        if (simulated == null || amount <= 0) return 0;
        int accepted = simulated.fill(new FluidStack(template, amount), false);
        if (accepted <= 0) return 0;

        ItemStack preview = held.copy();
        preview.setCount(1);
        IFluidHandlerItem previewHandler = FluidContainerUtils.getFluidHandlerCapability(preview);
        int previewFilled = previewHandler == null ? 0
                : Math.max(0, Math.min(accepted,
                        previewHandler.fill(new FluidStack(template, accepted), true)));
        if (previewFilled <= 0 || !canReturnContainer(player, held,
                previewHandler == null ? ItemStack.EMPTY : previewHandler.getContainer())) {
            return 0;
        }

        long extracted = buffer.extract(descriptor, previewFilled);
        if (extracted <= 0 || !persistReservedBuffer()) {
            if (extracted > 0) restoreAfterFailedDelivery(descriptor, extracted);
            return 0;
        }
        ItemStack working = held.copy();
        working.setCount(1);
        IFluidHandlerItem handler = FluidContainerUtils.getFluidHandlerCapability(working);
        int filled = handler == null ? 0 : Math.max(0, Math.min((int) extracted,
                handler.fill(new FluidStack(template, (int) extracted), true)));
        if (filled < extracted) {
            restoreAfterFailedDelivery(descriptor, extracted - filled);
        }
        if (filled <= 0) {
            return 0;
        }
        ItemStack result = handler == null ? ItemStack.EMPTY : handler.getContainer();
        if (!commitContainerResult(player, held, result)) {
            restoreAfterFailedDelivery(descriptor, filled);
            return 0;
        }
        return filled;
    }

    private long takeGasToCursor(EntityPlayer player, QIOResourceDescriptor descriptor,
                                 GasStack template, long requested) {
        ItemStack held = player.inventory.getItemStack();
        if (held.isEmpty()) return 0;
        int amount = boundedChemicalAmount(requested);
        if (amount <= 0) return 0;
        ItemStack simulated = held.copy();
        simulated.setCount(1);
        int accepted = GasInventorySlot.insertGas(simulated,
                new GasStack(template.getGas(), amount), false);
        if (accepted <= 0) return 0;

        ItemStack preview = held.copy();
        preview.setCount(1);
        int previewFilled = Math.max(0, Math.min(accepted,
                GasInventorySlot.insertGas(preview,
                        new GasStack(template.getGas(), accepted), true)));
        if (previewFilled <= 0 || !canReturnContainer(player, held, preview)) {
            return 0;
        }

        long extracted = buffer.extract(descriptor, previewFilled);
        if (extracted <= 0 || !persistReservedBuffer()) {
            if (extracted > 0) restoreAfterFailedDelivery(descriptor, extracted);
            return 0;
        }
        ItemStack working = held.copy();
        working.setCount(1);
        int filled = Math.max(0, Math.min((int) extracted,
                GasInventorySlot.insertGas(working,
                        new GasStack(template.getGas(), (int) extracted), true)));
        if (filled < extracted) {
            restoreAfterFailedDelivery(descriptor, extracted - filled);
        }
        if (filled <= 0) {
            return 0;
        }
        if (!commitContainerResult(player, held, working)) {
            restoreAfterFailedDelivery(descriptor, filled);
            return 0;
        }
        return filled;
    }

    private long takeFluidToInventory(InventoryPlayer inventory, QIOResourceDescriptor descriptor,
                                      FluidStack template, long requested) {
        long remaining = Math.min(requested, buffer.amount(descriptor));
        long delivered = 0;
        for (int slot = 0; slot < getContainerSlotCount(inventory) && remaining > 0; slot++) {
            while (remaining > 0) {
                ItemStack container = getContainerSlot(inventory, slot);
                if (container.isEmpty()) break;
                IFluidHandlerItem simulated = FluidContainerUtils.getUnstackedFluidHandlerCapability(container);
                if (simulated == null) break;
                int amount = boundedChemicalAmount(remaining);
                int accepted = Math.max(0, Math.min(amount,
                        simulated.fill(new FluidStack(template, amount), false)));
                if (accepted <= 0) break;
                ItemStack working = container.copy();
                working.setCount(1);
                IFluidHandlerItem actual = FluidContainerUtils.getFluidHandlerCapability(working);
                int filled = actual == null ? 0 : Math.max(0, Math.min(accepted,
                        actual.fill(new FluidStack(template, accepted), true)));
                if (filled <= 0) break;
                long extracted = buffer.extract(descriptor, filled);
                if (extracted <= 0 || !persistReservedBuffer()) {
                    if (extracted > 0) restoreAfterFailedDelivery(descriptor, extracted);
                    return delivered;
                }
                if (filled < extracted) {
                    restoreAfterFailedDelivery(descriptor, extracted - filled);
                }
                if (!commitInventoryContainerResult(inventory, slot, container,
                        actual.getContainer())) {
                    restoreAfterFailedDelivery(descriptor, filled);
                    return delivered;
                }
                remaining -= filled;
                delivered += filled;
            }
        }
        return delivered;
    }

    private long takeGasToInventory(InventoryPlayer inventory, QIOResourceDescriptor descriptor,
                                    GasStack template, long requested) {
        long remaining = Math.min(requested, buffer.amount(descriptor));
        long delivered = 0;
        for (int slot = 0; slot < getContainerSlotCount(inventory) && remaining > 0; slot++) {
            while (remaining > 0) {
                ItemStack container = getContainerSlot(inventory, slot);
                if (container.isEmpty()) break;
                ItemStack simulation = container.copy();
                simulation.setCount(1);
                int amount = boundedChemicalAmount(remaining);
                int accepted = Math.max(0, Math.min(amount,
                        GasInventorySlot.insertGas(simulation,
                                new GasStack(template.getGas(), amount), false)));
                if (accepted <= 0) break;
                ItemStack working = container.copy();
                working.setCount(1);
                int filled = Math.max(0, Math.min(accepted,
                        GasInventorySlot.insertGas(working,
                                new GasStack(template.getGas(), accepted), true)));
                if (filled <= 0) break;
                long extracted = buffer.extract(descriptor, filled);
                if (extracted <= 0 || !persistReservedBuffer()) {
                    if (extracted > 0) restoreAfterFailedDelivery(descriptor, extracted);
                    return delivered;
                }
                if (filled < extracted) {
                    restoreAfterFailedDelivery(descriptor, extracted - filled);
                }
                if (!commitInventoryContainerResult(inventory, slot, container, working)) {
                    restoreAfterFailedDelivery(descriptor, filled);
                    return delivered;
                }
                remaining -= filled;
                delivered += filled;
            }
        }
        return delivered;
    }

    private static int boundedChemicalAmount(long amount) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, amount));
    }

    private static int getContainerSlotCount(InventoryPlayer inventory) {
        return inventory.mainInventory.size() + inventory.offHandInventory.size();
    }

    private static ItemStack getContainerSlot(InventoryPlayer inventory, int index) {
        if (index < inventory.mainInventory.size()) {
            return inventory.mainInventory.get(index);
        }
        int offhand = index - inventory.mainInventory.size();
        return offhand < inventory.offHandInventory.size()
                ? inventory.offHandInventory.get(offhand) : ItemStack.EMPTY;
    }

    private static void setContainerSlot(InventoryPlayer inventory, int index, ItemStack stack) {
        if (index < inventory.mainInventory.size()) {
            inventory.mainInventory.set(index, stack);
        } else {
            int offhand = index - inventory.mainInventory.size();
            if (offhand >= 0 && offhand < inventory.offHandInventory.size()) {
                inventory.offHandInventory.set(offhand, stack);
            }
        }
    }

    private static boolean canMergeInto(ItemStack existing, ItemStack requested, int max) {
        return !existing.isEmpty() && existing.isStackable()
                && ItemHandlerHelper.canItemStacksStack(existing, requested)
                && existing.getCount() < Math.min(max, existing.getMaxStackSize());
    }

    private static boolean canReturnContainer(EntityPlayer player, ItemStack held, @Nullable ItemStack result) {
        if (held == null || held.isEmpty()) return false;
        ItemStack returned = result == null ? ItemStack.EMPTY : result.copy();
        if (returned.isEmpty()) return true;
        ItemStack cursor = held.copy();
        cursor.shrink(1);
        int inventoryLimit = player.inventory.getInventoryStackLimit();
        int cursorLimit = Math.min(inventoryLimit, returned.getMaxStackSize());
        if (cursor.isEmpty()) {
            int toCursor = Math.min(returned.getCount(), cursorLimit);
            returned.shrink(toCursor);
        } else if (canMergeInto(cursor, returned,
                Math.min(inventoryLimit, cursor.getMaxStackSize()))) {
            int max = Math.min(inventoryLimit, cursor.getMaxStackSize());
            int toCursor = Math.min(returned.getCount(), max - cursor.getCount());
            returned.shrink(toCursor);
        }
        return returned.isEmpty() || canAddToInventory(player.inventory, returned);
    }

    private static boolean commitContainerResult(EntityPlayer player, ItemStack held, @Nullable ItemStack result) {
        ItemStack current = player.inventory.getItemStack();
        if (current.isEmpty() || held == null || held.isEmpty()
                || current.getCount() != held.getCount()
                || !ItemStack.areItemStacksEqual(current, held)
                || !canReturnContainer(player, held, result)) {
            return false;
        }
        ItemStack cursor = current.copy();
        cursor.shrink(1);
        ItemStack returned = result == null ? ItemStack.EMPTY : result.copy();
        if (!returned.isEmpty()) {
            int inventoryLimit = player.inventory.getInventoryStackLimit();
            if (cursor.isEmpty()) {
                int toCursor = Math.min(returned.getCount(),
                        Math.min(inventoryLimit, returned.getMaxStackSize()));
                ItemStack cursorResult = returned.copy();
                cursorResult.setCount(toCursor);
                cursor = cursorResult;
                returned.shrink(toCursor);
            } else if (canMergeInto(cursor, returned,
                    Math.min(inventoryLimit, cursor.getMaxStackSize()))) {
                int max = Math.min(inventoryLimit, cursor.getMaxStackSize());
                int toCursor = Math.min(returned.getCount(), max - cursor.getCount());
                cursor.grow(toCursor);
                returned.shrink(toCursor);
            }
            if (!returned.isEmpty() && !addFullyToInventory(player.inventory, returned)) {
                return false;
            }
        }
        player.inventory.setItemStack(cursor);
        player.inventory.markDirty();
        if (player.openContainer != null) {
            player.openContainer.detectAndSendChanges();
        }
        return true;
    }

    private static boolean commitInventoryContainerResult(InventoryPlayer inventory, int index,
                                                           ItemStack expected, @Nullable ItemStack result) {
        if (index < 0 || index >= getContainerSlotCount(inventory)
                || expected == null || expected.isEmpty()) {
            return false;
        }
        ItemStack current = getContainerSlot(inventory, index);
        if (current.isEmpty() || current.getCount() != expected.getCount()
                || !ItemStack.areItemStacksEqual(current, expected)) {
            return false;
        }

        List<ItemStack> mainSnapshot = copyInventory(inventory.mainInventory);
        List<ItemStack> offhandSnapshot = copyInventory(inventory.offHandInventory);
        ItemStack remainingContainers = current.copy();
        remainingContainers.shrink(1);
        setContainerSlot(inventory, index,
                remainingContainers.isEmpty() ? ItemStack.EMPTY : remainingContainers);

        ItemStack returned = result == null ? ItemStack.EMPTY : result.copy();
        if (!returned.isEmpty()) {
            ItemStack slotStack = getContainerSlot(inventory, index);
            int inventoryLimit = inventory.getInventoryStackLimit();
            int slotLimit = Math.min(inventoryLimit, returned.getMaxStackSize());
            if (slotStack.isEmpty()) {
                int toSlot = Math.min(returned.getCount(), slotLimit);
                if (toSlot > 0) {
                    ItemStack slotResult = returned.copy();
                    slotResult.setCount(toSlot);
                    setContainerSlot(inventory, index, slotResult);
                    returned.shrink(toSlot);
                }
            } else if (canMergeInto(slotStack, returned,
                    Math.min(inventoryLimit, slotStack.getMaxStackSize()))) {
                int max = Math.min(inventoryLimit, slotStack.getMaxStackSize());
                int toSlot = Math.min(returned.getCount(), max - slotStack.getCount());
                slotStack.grow(toSlot);
                returned.shrink(toSlot);
            }
            if (!returned.isEmpty() && !addFullyToInventory(inventory, returned)) {
                restoreInventory(inventory, mainSnapshot, offhandSnapshot);
                return false;
            }
        }
        inventory.markDirty();
        return true;
    }

    private static boolean addFullyToInventory(InventoryPlayer inventory, ItemStack stack) {
        if (stack.isEmpty() || !canAddToInventory(inventory, stack)) return false;
        List<ItemStack> mainSnapshot = copyInventory(inventory.mainInventory);
        List<ItemStack> offhandSnapshot = copyInventory(inventory.offHandInventory);
        ItemStack remaining = stack.copy();
        inventory.addItemStackToInventory(remaining);
        if (remaining.isEmpty()) return true;
        restoreInventory(inventory, mainSnapshot, offhandSnapshot);
        return false;
    }

    private static boolean canAddToInventory(InventoryPlayer inventory, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ItemStack remaining = stack.copy();
        int inventoryLimit = inventory.getInventoryStackLimit();
        int max = Math.min(remaining.getMaxStackSize(), inventoryLimit);
        int left = remaining.getCount();
        int current = inventory.currentItem;
        ItemStack selected = current >= 0 && current < inventory.mainInventory.size()
                ? inventory.mainInventory.get(current) : ItemStack.EMPTY;
        if (canMergeInto(selected, remaining, max)) {
            left -= Math.min(left, max - selected.getCount());
        }
        ItemStack offhand = inventory.offHandInventory.isEmpty()
                ? ItemStack.EMPTY : inventory.offHandInventory.get(0);
        if (left > 0 && canMergeInto(offhand, remaining, max)) {
            left -= Math.min(left, max - offhand.getCount());
        }
        for (int slot = 0; left > 0 && slot < inventory.mainInventory.size(); slot++) {
            if (slot == current) continue;
            ItemStack existing = inventory.mainInventory.get(slot);
            if (canMergeInto(existing, remaining, max)) {
                left -= Math.min(left, max - existing.getCount());
            }
        }
        for (int slot = 0; left > 0 && slot < inventory.mainInventory.size(); slot++) {
            if (inventory.mainInventory.get(slot).isEmpty()) {
                left -= Math.min(left, max);
            }
        }
        for (ItemStack existing : inventory.offHandInventory) {
            if (left <= 0) break;
            if (existing.isEmpty()) left -= Math.min(left, max);
        }
        return left <= 0;
    }

    private static List<ItemStack> copyInventory(List<ItemStack> inventory) {
        List<ItemStack> copy = new ArrayList<>(inventory.size());
        for (ItemStack stack : inventory) copy.add(stack.copy());
        return copy;
    }

    private static void restoreInventory(InventoryPlayer inventory, List<ItemStack> main,
                                         List<ItemStack> offhand) {
        inventory.mainInventory.clear();
        inventory.mainInventory.addAll(main);
        inventory.offHandInventory.clear();
        inventory.offHandInventory.addAll(offhand);
    }

    private boolean persistReservedBuffer() {
        if (teamOwner == null || recoveryWriteBlocked) return false;
        boolean saved = CloudworksPlayerDataManager.INSTANCE
                .saveTeamRecoveryBuffer(teamOwner, buffer);
        recoveryWriteBlocked = !saved;
        if (saved) {
            recoveryVersion++;
            markDirty();
        }
        return saved;
    }

    private void restoreAfterFailedDelivery(QIOResourceDescriptor descriptor, long amount) {
        if (amount <= 0) return;
        buffer.insertIgnoringTypeCap(descriptor, amount);
        persistReservedBuffer();
    }

    // -----------------------------------------------------------------------------------------
    // QIO-exporter style side output.
    // -----------------------------------------------------------------------------------------

    private void exportToAdjacent() {
        EnumFacing orientation = facing == null ? EnumFacing.NORTH : facing;
        exportConfiguredItems(orientation);
        exportConfiguredFluids(orientation);
        exportConfiguredGases(orientation);
        exportConfiguredCustomResources(orientation);
    }

    private void exportConfiguredItems(EnumFacing orientation) {
        ConfigInfo config = configComponent.getConfigInfo(TransmissionType.ITEM);
        if (!ejectorComponent.isEjecting(config, TransmissionType.ITEM)) return;
        for (EnumFacing side : configComponent.getSidesForData(
                TransmissionType.ITEM, orientation, DataType.OUTPUT)) {
            if (recoveryWriteBlocked) return;
            TileEntity adjacent = MekanismUtils.getTileEntity(world, getPos().offset(side));
            if (adjacent == null) continue;
            EnumFacing targetFace = side.getOpposite();
            IItemHandler items = CapabilityUtils.getCapability(adjacent,
                    CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, targetFace);
            if (items != null) exportItems(items);
        }
    }

    private void exportConfiguredFluids(EnumFacing orientation) {
        ConfigInfo config = configComponent.getConfigInfo(TransmissionType.FLUID);
        if (!ejectorComponent.isEjecting(config, TransmissionType.FLUID)) return;
        for (EnumFacing side : configComponent.getSidesForData(
                TransmissionType.FLUID, orientation, DataType.OUTPUT)) {
            if (recoveryWriteBlocked) return;
            TileEntity adjacent = MekanismUtils.getTileEntity(world, getPos().offset(side));
            if (adjacent == null) continue;
            IFluidHandler fluids = CapabilityUtils.getCapability(adjacent,
                    CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, side.getOpposite());
            if (fluids != null) exportFluids(fluids);
        }
    }

    private void exportConfiguredGases(EnumFacing orientation) {
        ConfigInfo config = configComponent.getConfigInfo(TransmissionType.GAS);
        if (!ejectorComponent.isEjecting(config, TransmissionType.GAS)) return;
        for (EnumFacing side : configComponent.getSidesForData(
                TransmissionType.GAS, orientation, DataType.OUTPUT)) {
            if (recoveryWriteBlocked) return;
            TileEntity adjacent = MekanismUtils.getTileEntity(world, getPos().offset(side));
            if (adjacent == null) continue;
            EnumFacing targetFace = side.getOpposite();
            IGasHandler gases = CapabilityUtils.getCapability(adjacent,
                    Capabilities.GAS_HANDLER_CAPABILITY, targetFace);
            if (gases != null) exportGases(gases, targetFace);
        }
    }

    private void exportConfiguredCustomResources(EnumFacing orientation) {
        ConfigInfo config = configComponent.getConfigInfo(TransmissionType.ENERGY);
        if (!ejectorComponent.isEjecting(config, TransmissionType.ENERGY)) return;
        for (EnumFacing side : configComponent.getSidesForData(
                TransmissionType.ENERGY, orientation, DataType.OUTPUT)) {
            if (recoveryWriteBlocked) return;
            TileEntity adjacent = MekanismUtils.getTileEntity(world, getPos().offset(side));
            if (adjacent != null) exportCustomResources(adjacent, side.getOpposite());
        }
    }

    private boolean bufferChangedDuring(long before) {
        return buffer.total() != before;
    }

    private long reserveForDelivery(QIOResourceDescriptor descriptor, long requested) {
        if (teamOwner == null || requested <= 0) return 0;
        long reserved = buffer.extract(descriptor, Math.min(requested, buffer.amount(descriptor)));
        if (reserved > 0 && !persistReservedBuffer()) {
            buffer.insertIgnoringTypeCap(descriptor, reserved);
            return 0;
        }
        return reserved;
    }

    private void exportItems(IItemHandler handler) {
        long before = buffer.total();
        int types = 0;
        for (QIOResourceDescriptor descriptor : buffer.snapshot().keySet()) {
            if (recoveryWriteBlocked) break;
            if (types >= EXPORT_MAX_TYPES) break;
            if (!QIOResourceCodecs.ITEM_FAMILY.equals(descriptor.getFamily())) continue;
            ItemStack template = descriptor.resolveItemStackTemplate();
            if (template == null || template.isEmpty()) continue;
            long limit = Math.min(buffer.amount(descriptor), ITEM_SLOT_CAP);
            long fitting = moveItems(handler, template, limit, true);
            long reserved = reserveForDelivery(descriptor, fitting);
            if (reserved <= 0) continue;
            long moved = moveItems(handler, template, reserved, false);
            if (moved < reserved) restoreAfterFailedDelivery(descriptor, reserved - moved);
            if (moved > 0) types++;
        }
        if (bufferChangedDuring(before)) markDirty();
    }

    /**
     * Moves items into the handler one stack at a time. With {@code simulate}
     * this is a capacity probe; otherwise the movement is committed and any
     * handler remainder stays in the caller's buffer (extract-after-commit).
     */
    private long moveItems(IItemHandler handler, ItemStack template, long limit, boolean simulate) {
        long total = 0;
        while (total < limit) {
            int one = (int) Math.min(template.getMaxStackSize(), limit - total);
            ItemStack stack = template.copy();
            stack.setCount(one);
            for (int slot = 0; slot < handler.getSlots() && !stack.isEmpty(); slot++) {
                if (handler.isItemValid(slot, stack)) {
                    stack = handler.insertItem(slot, stack, simulate);
                }
            }
            int moved = one - stack.getCount();
            if (moved <= 0) break;
            total += moved;
        }
        return total;
    }

    private void exportFluids(IFluidHandler handler) {
        long before = buffer.total();
        int types = 0;
        for (QIOResourceDescriptor descriptor : buffer.snapshot().keySet()) {
            if (recoveryWriteBlocked) break;
            if (types >= EXPORT_MAX_TYPES) break;
            if (!QIOResourceCodecs.FLUID_FAMILY.equals(descriptor.getFamily())) continue;
            FluidStack template = descriptor.resolveFluidStackTemplate();
            if (template == null) continue;
            int requested = (int) Math.min(buffer.amount(descriptor), CHEMICAL_SLOT_CAP);
            FluidStack probe = new FluidStack(template, requested);
            int accepted = handler.fill(probe, false);
            if (accepted <= 0) continue;
            long extracted = reserveForDelivery(descriptor, accepted);
            if (extracted <= 0) continue;
            int filled = handler.fill(new FluidStack(template, (int) extracted), true);
            if (filled < extracted) restoreAfterFailedDelivery(descriptor, extracted - filled);
            if (filled > 0) types++;
        }
        if (bufferChangedDuring(before)) markDirty();
    }

    private void exportGases(IGasHandler handler, EnumFacing targetFace) {
        long before = buffer.total();
        int types = 0;
        for (QIOResourceDescriptor descriptor : buffer.snapshot().keySet()) {
            if (recoveryWriteBlocked) break;
            if (types >= EXPORT_MAX_TYPES) break;
            if (!QIOResourceCodecs.GAS_FAMILY.equals(descriptor.getFamily())) continue;
            GasStack template = descriptor.resolveGasStackTemplate();
            if (template == null) continue;
            int requested = (int) Math.min(buffer.amount(descriptor), CHEMICAL_SLOT_CAP);
            GasStack probe = new GasStack(template.getGas(), requested);
            int accepted = handler.receiveGas(targetFace, probe, false);
            if (accepted <= 0) continue;
            long extracted = reserveForDelivery(descriptor, accepted);
            if (extracted <= 0) continue;
            int sent = handler.receiveGas(targetFace, new GasStack(template.getGas(), (int) extracted), true);
            if (sent < extracted) restoreAfterFailedDelivery(descriptor, extracted - sent);
            if (sent > 0) types++;
        }
        if (bufferChangedDuring(before)) markDirty();
    }

    /**
     * Custom families (addon codecs, e.g. mana) bridge through the QIO transfer adapter registry.
     */
    private void exportCustomResources(TileEntity adjacent, EnumFacing targetFace) {
        long before = buffer.total();
        int types = 0;
        for (QIOResourceDescriptor descriptor : buffer.snapshot().keySet()) {
            if (recoveryWriteBlocked) break;
            if (types >= EXPORT_MAX_TYPES) break;
            if (QIOResourceCodecs.ITEM_FAMILY.equals(descriptor.getFamily())
                    || QIOResourceCodecs.FLUID_FAMILY.equals(descriptor.getFamily())
                    || QIOResourceCodecs.GAS_FAMILY.equals(descriptor.getFamily())) continue;
            if (!descriptor.isResolved()) continue;
            QIOResourceTransferAdapter adapter = QIOResourceTransferAdapterRegistry.INSTANCE.get(descriptor.getCodecId());
            if (adapter == null || !adapter.supports(adjacent, targetFace)) continue;
            long requested = Math.min(buffer.amount(descriptor), slotCapOf(descriptor));
            long accepted = Math.min(requested, Math.max(0,
                    adapter.insert(adjacent, targetFace, descriptor, requested, Action.SIMULATE)));
            if (accepted <= 0) continue;
            long extracted = reserveForDelivery(descriptor, accepted);
            if (extracted <= 0) continue;
            long inserted = Math.min(extracted, Math.max(0,
                    adapter.insert(adjacent, targetFace, descriptor, extracted, Action.EXECUTE)));
            if (inserted < extracted) restoreAfterFailedDelivery(descriptor, extracted - inserted);
            if (inserted > 0) types++;
        }
        if (bufferChangedDuring(before)) markDirty();
    }

    private void releaseRecoveryMount() {
        if (recoveryMountHeld && teamOwner != null) {
            CloudworksPlayerDataManager.INSTANCE
                    .unmountTeamRecovery(teamOwner, this);
        }
        recoveryMountHeld = false;
        bufferLoaded = false;
    }

    @Override
    public void invalidate() {
        releaseRecoveryMount();
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        releaseRecoveryMount();
        super.onChunkUnload();
    }

    // -----------------------------------------------------------------------------------------
    // Client mirror + persistence.
    // -----------------------------------------------------------------------------------------

    public void applyRecoveryState(List<QIOResourceEntry> entries, int offset, int total, int version) {
        recoveryEntries = entries == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(entries));
        recoveryOffset = offset;
        recoveryTotal = total;
        recoveryViewVersion = version;
    }

    public List<QIOResourceEntry> getRecoveryEntries() {
        return recoveryEntries;
    }

    public int getRecoveryOffset() {
        return recoveryOffset;
    }

    public int getRecoveryTotal() {
        return recoveryTotal;
    }

    public int getRecoveryViewVersion() {
        return recoveryViewVersion;
    }

    @Override
    public int getBlockGuiID(Block block, int metadata) {
        return 5;
    }

    @Override
    public void readCustomNBT(NBTTagCompound tag) {
        super.readCustomNBT(tag);
        teamOwner = tag.hasKey("cwTeamOwner", 8) ? UUID.fromString(tag.getString("cwTeamOwner")) : null;
        recoveryStatus = tag.getString("cwRecoveryStatus");
        drainActive = !tag.hasKey("cwDrainActive") || tag.getBoolean("cwDrainActive");
    }

    @Override
    public void writeCustomNBT(NBTTagCompound tag) {
        super.writeCustomNBT(tag);
        if (teamOwner != null) tag.setString("cwTeamOwner", teamOwner.toString());
        tag.setString("cwRecoveryStatus", recoveryStatus);
        tag.setBoolean("cwDrainActive", drainActive);
    }

    @Override
    public void writeSustainedData(ItemStack stack) {
        super.writeSustainedData(stack);
        NBTTagCompound data = ItemDataUtils.getDataMap(stack);
        if (teamOwner != null) data.setString("cwTeamOwner", teamOwner.toString());
    }

    @Override
    public void readSustainedData(ItemStack stack) {
        super.readSustainedData(stack);
        NBTTagCompound data = ItemDataUtils.getDataMap(stack);
        teamOwner = data.hasKey("cwTeamOwner", 8) ? UUID.fromString(data.getString("cwTeamOwner")) : null;
    }

    @Override
    protected void readPortPacket(ByteBuf data) {
        recoveryVersion = data.readInt();
        estateDrained = data.readBoolean();
        boolean bound = data.readBoolean();
        recoveryStatus = bound ? PacketHandler.readString(data) : "";
    }

    @Override
    protected void writePortPacket(TileNetworkList data) {
        data.add(recoveryVersion);
        data.add(estateDrained);
        data.add(teamOwner != null);
        if (teamOwner != null) data.add(recoveryStatus);
    }
}
