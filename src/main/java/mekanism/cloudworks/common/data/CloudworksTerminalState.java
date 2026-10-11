package mekanism.cloudworks.common.data;

import mekanism.api.inventory.IInventorySlot;
import mekanism.cloudworks.common.content.CloudworksTerminalHost;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable inventory of one physical terminal, independent of the cloud record. */
public final class CloudworksTerminalState {
    private final UUID owner, terminalId, transferId;
    private final List<ItemStack> slots;

    private CloudworksTerminalState(UUID owner, UUID terminalId, UUID transferId, List<ItemStack> slots) {
        if (slots.size() != 5) throw new IllegalArgumentException("Invalid terminal slot count");
        this.owner = owner;
        this.terminalId = terminalId;
        this.transferId = transferId;
        this.slots = new ArrayList<>();
        for (ItemStack stack : slots) this.slots.add(stack.copy());
    }

    public static CloudworksTerminalState capture(UUID owner, CloudworksTerminalHost host) {
        List<ItemStack> slots = new ArrayList<>();
        for (IInventorySlot slot : host.terminalSlots()) slots.add(slot.getStack());
        return new CloudworksTerminalState(owner, host.terminalId(), UUID.randomUUID(), slots);
    }

    public CloudworksTerminalState withSlot(int index, ItemStack stack) {
        List<ItemStack> next = new ArrayList<>(slots);
        next.set(index, stack);
        return new CloudworksTerminalState(owner, terminalId, UUID.randomUUID(), next);
    }

    public UUID owner() { return owner; }
    public UUID terminalId() { return terminalId; }
    public UUID transferId() { return transferId; }
    public ItemStack stack(int index) { return slots.get(index).copy(); }

    public void apply(CloudworksTerminalHost host) {
        restoreSlots(host);
        host.acknowledgeTerminalTransfer(transferId);
        host.persist();
    }

    /** Restore a refused click without changing the last durable transfer acknowledgement. */
    public void restoreSlots(CloudworksTerminalHost host) {
        if (!terminalId.equals(host.terminalId()) || host.terminalSlots().size() != slots.size())
            throw new IllegalArgumentException("Foreign terminal");
        for (int i = 0; i < slots.size(); i++) {
            IInventorySlot slot = host.terminalSlots().get(i);
            if (slot instanceof mekanism.common.inventory.slot.BasicInventorySlot basic)
                basic.setStackUnchecked(slots.get(i).copy());
            else slot.setStack(slots.get(i).copy());
        }
        host.persist();
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", 1);
        tag.setString("owner", owner.toString());
        tag.setString("terminalId", terminalId.toString());
        tag.setString("transferId", transferId.toString());
        NBTTagList entries = new NBTTagList();
        for (ItemStack stack : slots) {
            NBTTagCompound entry = new NBTTagCompound();
            if (!stack.isEmpty()) entry.setTag("stack", stack.writeToNBT(new NBTTagCompound()));
            entries.appendTag(entry);
        }
        tag.setTag("slots", entries);
        return tag;
    }

    public static CloudworksTerminalState read(NBTTagCompound tag) {
        CloudworksDataChecks.fields(tag, 8, "owner", "terminalId", "transferId");

        CloudworksDataChecks.schema(tag, 1);
        CloudworksDataChecks.field(tag, "slots", 9);
        NBTTagList entries = CloudworksDataChecks.list(tag, "slots", 10);
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound entry = entries.getCompoundTagAt(i);
            ItemStack stack = ItemStack.EMPTY;
            if (entry.hasKey("stack")) {
                CloudworksDataChecks.field(entry, "stack", 10);
                stack = new ItemStack(entry.getCompoundTag("stack"));
                if (stack.isEmpty() || stack.getCount() > stack.getMaxStackSize())
                    throw new IllegalArgumentException("Invalid terminal stack");
            }
            slots.add(stack);
        }
        if (slots.size() != 5) throw new IllegalArgumentException("Invalid terminal slot count");
        for (int index : new int[]{0, 1})
            if (!slots.get(index).isEmpty()
                    && !mekanism.cloudworks.common.registry.CloudworksRegistries.isRegistered(slots.get(index)))
                throw new IllegalArgumentException("Unregistered terminal machine");
        ItemStack energy = slots.get(2);
        if (!energy.isEmpty() && (energy.getCount() != 1
                || !mekanism.cloudworks.common.content.CloudworksTerminalSlots.acceptsEnergy(energy)))
            throw new IllegalArgumentException("Invalid terminal energy item");
        for (int index : new int[]{3, 4}) {
            ItemStack upgrade = slots.get(index);
            if (!upgrade.isEmpty() && !mekanism.cloudworks.common.content.CloudworksTerminalSlots.acceptsUpgrade(upgrade))
                throw new IllegalArgumentException("Invalid terminal upgrade");
        }
        return new CloudworksTerminalState(UUID.fromString(tag.getString("owner")),
                UUID.fromString(tag.getString("terminalId")), UUID.fromString(tag.getString("transferId")), slots);
    }
}
