package mekanism.cloudworks.common.tile;

import mekanism.api.IContentsListener;
import mekanism.api.inventory.IInventorySlot;
import mekanism.cloudworks.common.content.CloudworksTerminalHost;
import mekanism.cloudworks.common.content.CloudworksTerminalPersistence;
import mekanism.cloudworks.common.content.CloudworksTerminalSlots;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.common.base.ISustainedData;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.tile.prefab.TileEntityContainerBlock;
import mekanism.common.security.ISecurityTile;
import mekanism.common.tile.component.TileComponentSecurity;
import mekanism.common.util.ItemDataUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.util.List;

/**
 * Dashboard-terminal tile: hosts the five terminal functional slots
 * ({@link CloudworksTerminalSlots}) in tile NBT — the block-side half of the
 * "slots are kept by the item/tile themselves" design (the portable terminal
 * keeps them in its item NBT). The cloud record is never involved.
 */
public class TileEntityCloudDashboard extends TileEntityContainerBlock
        implements CloudworksTerminalHost, ISecurityTile, ISustainedData {

    private final TileComponentSecurity securityComponent;
    private java.util.UUID terminalApplied;
    private java.util.UUID terminalIdentity = java.util.UUID.randomUUID();
    private net.minecraft.nbt.NBTTagCompound checkpoint;
    private java.util.concurrent.CompletableFuture<Void> checkpointConfirmation;

    @Override public java.util.UUID appliedTerminalTransfer() { return terminalApplied; }
    @Override public void acknowledgeTerminalTransfer(java.util.UUID id) { terminalApplied = id; }

    @Override
    public void writeCustomNBT(net.minecraft.nbt.NBTTagCompound tag) {
        super.writeCustomNBT(tag);
        tag.setString("cloudTerminalId", terminalIdentity.toString());
        if (terminalApplied != null) tag.setString("cloudTerminalApplied", terminalApplied.toString());
    }

    @Override
    public void readCustomNBT(net.minecraft.nbt.NBTTagCompound tag) {
        super.readCustomNBT(tag);
        if (tag.hasKey("cloudTerminalId", 8))
            terminalIdentity = java.util.UUID.fromString(tag.getString("cloudTerminalId"));
        terminalApplied = tag.hasKey("cloudTerminalApplied", 8)
                ? java.util.UUID.fromString(tag.getString("cloudTerminalApplied")) : null;
    }

    public TileEntityCloudDashboard() {
        super("cloudworks_dashboard");
        securityComponent = new TileComponentSecurity(this);
        initializeInventorySlots();
    }

    @Override
    public TileComponentSecurity getSecurity() {
        return securityComponent;
    }

    @Override
    public boolean available() {
        return world != null && !isInvalid() && world.isBlockLoaded(pos) && world.getTileEntity(pos) == this;
    }

    @Override
    public void invalidate() {
        if (world != null && !world.isRemote) CloudworksPlayerDataManager.INSTANCE.detachTerminal(this);
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        if (world != null && !world.isRemote) {
            CloudworksPlayerDataManager.INSTANCE.detachTerminal(this);
            getFrequencyComponent().invalidate();
        }
        super.onChunkUnload();
    }

    @Override
    public void writeSustainedData(ItemStack stack) {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("cloudTerminalId", terminalIdentity.toString());
        if (terminalApplied != null) data.setString("cloudTerminalApplied", terminalApplied.toString());
        ItemDataUtils.setCompound(stack, "cloudTerminal", data);
    }

    @Override
    public void readSustainedData(ItemStack stack) {
        NBTTagCompound data = ItemDataUtils.getCompound(stack, "cloudTerminal");
        if (data.hasKey("cloudTerminalId", 8))
            terminalIdentity = java.util.UUID.fromString(data.getString("cloudTerminalId"));
        terminalApplied = data.hasKey("cloudTerminalApplied", 8)
                ? java.util.UUID.fromString(data.getString("cloudTerminalApplied")) : null;
    }

    @Override
    public List<IInventorySlot> terminalSlots() {
        return getInventorySlots(null);
    }

    @Override
    public java.util.UUID terminalId() {
        return terminalIdentity;
    }

    // Functional slots are operated through the journalled terminal container.
    @Override
    public boolean hasCapability(net.minecraftforge.common.capabilities.Capability<?> capability, net.minecraft.util.EnumFacing side) {
        if (capability == net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) return false;
        return super.hasCapability(capability, side);
    }

    @Override
    public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> capability, net.minecraft.util.EnumFacing side) {
        if (capability == net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) return null;
        return super.getCapability(capability, side);
    }

    @Override
    public void persist() {
        markDirty();
    }

    @Override
    public void persistIdentity() {
        persist();
        if (world == null || world.isRemote) return;
        net.minecraft.nbt.NBTTagCompound current = writeToNBT(new net.minecraft.nbt.NBTTagCompound());
        if (current.equals(checkpoint) && checkpointConfirmation != null
                && !checkpointConfirmation.isCompletedExceptionally()) return;
        checkpoint = current;
        checkpointConfirmation = mekanism.cloudworks.common.content.CloudworksPhysicalCheckpointService.checkpoint(this);
    }

    @Override
    public boolean identityDurable() {
        if (checkpointConfirmation == null || !checkpointConfirmation.isDone()) return false;
        checkpointConfirmation.join(); // completed only; propagate a failed verification
        return true;
    }

    @Override
    protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
        InventorySlotHelper builder = createInventorySlotHelper();
        builder.addSlot(CloudworksTerminalSlots.machineInput(listener, 8, 18));
        builder.addSlot(CloudworksTerminalSlots.machineOutput(listener, 26, 18));
        builder.addSlot(CloudworksTerminalSlots.energyInput(listener, 44, 18));
        builder.addSlot(CloudworksTerminalSlots.upgradeInput(listener, 62, 18));
        builder.addSlot(CloudworksTerminalSlots.upgradeOutput(listener, 80, 18));
        return builder.build();
    }

    @Override
    public void onUpdate() {
        // pure slot host; no server-side state machine
    }
}

