package mekanism.cloudworks.common.data;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import mekanism.api.inventory.IInventorySlot;
import mekanism.cloudworks.common.content.CloudworksHybridTank;
import mekanism.api.DataHandlerUtils;
import net.minecraft.nbt.NBTTagCompound;

/** Detached authoritative port payload. A physical port mounts exactly one record. */
public final class CloudworksPortRecord {
    private final NBTTagCompound payload;
    public final UUID id, owner;
    public final boolean input;

    private CloudworksPortRecord(NBTTagCompound tag) {
        if (tag.getInteger("schema") != 1 || !tag.hasKey("input", 1)
                || !tag.hasKey("items", 9) || !tag.hasKey("fluids", 9) || !tag.hasKey("gases", 9)
                || !tag.hasKey("energy", 6))
            throw new IllegalArgumentException("Invalid Cloudworks port record");
        for (String key : new String[]{"items", "fluids", "gases"}) {
            net.minecraft.nbt.NBTTagList entries = (net.minecraft.nbt.NBTTagList) tag.getTag(key);
            if (!entries.isEmpty() && entries.getTagType() != 10)
                throw new IllegalArgumentException("Invalid port list type");
        }
        id = UUID.fromString(tag.getString("portId"));
        owner = UUID.fromString(tag.getString("owner"));
        input = tag.getBoolean("input");
        double energy = tag.getDouble("energy");
        if (!Double.isFinite(energy) || energy < 0)
            throw new IllegalArgumentException("Invalid Cloudworks port energy");
        validate(tag.getTagList("items", 10), mekanism.api.NBTConstants.SLOT, 121, 0, null);
        boolean[] occupied = new boolean[36];
        validate(tag.getTagList("fluids", 10), mekanism.api.NBTConstants.TANK, 36, 1, occupied);
        validate(tag.getTagList("gases", 10), mekanism.api.NBTConstants.TANK, 36, 2, occupied);
        payload = tag.copy();
    }

    private static void validate(net.minecraft.nbt.NBTTagList entries, String key, int limit,
            int kind, boolean[] occupied) {
        boolean[] seen = new boolean[limit];
        for (int index = 0; index < entries.tagCount(); index++) {
            NBTTagCompound entry = entries.getCompoundTagAt(index);
            int slot = entry.getInteger(key);
            if (!entry.hasKey(key, 3) || slot < 0 || slot >= limit || seen[slot]
                    || occupied != null && occupied[slot]) throw new IllegalArgumentException("Invalid port position");
            seen[slot] = true;
            if (kind == 0) {
                net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(entry.getCompoundTag(mekanism.api.NBTConstants.ITEM));
                if (entry.hasKey(mekanism.api.NBTConstants.SIZE_OVERRIDE, 3))
                    stack.setCount(entry.getInteger(mekanism.api.NBTConstants.SIZE_OVERRIDE));
                if (stack.isEmpty() || stack.getCount() > mekanism.cloudworks.common.content.CloudworksPortInputSlot.LIMIT)
                    throw new IllegalArgumentException("Invalid port item");
            } else if (kind == 1) {
                net.minecraftforge.fluids.FluidStack fluid = net.minecraftforge.fluids.FluidStack.loadFluidStackFromNBT(
                        entry.getCompoundTag(mekanism.api.NBTConstants.STORED));
                if (fluid == null || fluid.amount <= 0 || fluid.amount > CloudworksHybridTank.FLUID_CAPACITY)
                    throw new IllegalArgumentException("Invalid port fluid");
            } else {
                mekanism.api.gas.GasStack gas = mekanism.api.gas.GasStack.readFromNBT(
                        entry.getCompoundTag(mekanism.api.NBTConstants.STORED));
                if (gas == null || gas.amount <= 0 || gas.amount > CloudworksHybridTank.GAS_CAPACITY)
                    throw new IllegalArgumentException("Invalid port gas");
            }
            if (occupied != null) occupied[slot] = true;
        }
    }

    public static CloudworksPortRecord capture(UUID id, UUID owner, boolean input,
            List<IInventorySlot> slots, List<CloudworksHybridTank> tanks, double energy) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", 1);
        tag.setString("portId", id.toString());
        tag.setString("owner", owner.toString());
        tag.setBoolean("input", input);
        tag.setDouble("energy", energy);
        tag.setTag("items", DataHandlerUtils.writeContainers(slots));
        tag.setTag("fluids", DataHandlerUtils.writeContainers(tanks.stream()
                .map(CloudworksHybridTank::fluidView).collect(Collectors.toList())));
        tag.setTag("gases", DataHandlerUtils.writeContainers(tanks.stream()
                .map(CloudworksHybridTank::gasView).collect(Collectors.toList())));
        return new CloudworksPortRecord(tag);
    }

    public static CloudworksPortRecord empty(UUID id, UUID owner, boolean input) {
        return capture(id, owner, input, java.util.Collections.emptyList(), java.util.Collections.emptyList(), 0);
    }

    public void restore(List<IInventorySlot> slots, List<CloudworksHybridTank> tanks) {
        for (IInventorySlot slot : slots) slot.setEmpty();
        for (CloudworksHybridTank tank : tanks) tank.drainAll();
        DataHandlerUtils.readContainers(slots, payload.getTagList("items", 10));
        DataHandlerUtils.readContainers(tanks.stream().map(CloudworksHybridTank::fluidView)
                .collect(Collectors.toList()), payload.getTagList("fluids", 10));
        DataHandlerUtils.readContainers(tanks.stream().map(CloudworksHybridTank::gasView)
                .collect(Collectors.toList()), payload.getTagList("gases", 10));
    }

    public double energy() { return payload.getDouble("energy"); }
    public NBTTagCompound write() { return payload.copy(); }
    public static CloudworksPortRecord read(NBTTagCompound tag) { return new CloudworksPortRecord(tag); }
}
