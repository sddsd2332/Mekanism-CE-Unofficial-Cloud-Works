package mekanism.cloudworks.common.data;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import mekanism.api.inventory.IInventorySlot;
import mekanism.cloudworks.common.content.CloudworksHybridTank;
import mekanism.api.DataHandlerUtils;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.gas.GasStack;
import mekanism.api.gas.IExtendedGasTank;
import mekanism.api.NBTConstants;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.content.CloudworksPortInputSlot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

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
            NBTTagList entries = (NBTTagList) tag.getTag(key);
            if (!entries.isEmpty() && entries.getTagType() != 10)
                throw new IllegalArgumentException("Invalid port list type");
        }
        id = UUID.fromString(tag.getString("portId"));
        owner = UUID.fromString(tag.getString("owner"));
        input = tag.getBoolean("input");
        double energy = tag.getDouble("energy");
        if (!Double.isFinite(energy) || energy < 0)
            throw new IllegalArgumentException("Invalid Cloudworks port energy");
        validate(tag.getTagList("items", 10), NBTConstants.SLOT, 121, 0, null);
        boolean[] occupied = new boolean[36];
        validate(tag.getTagList("fluids", 10), NBTConstants.TANK, 36, 1, occupied);
        validate(tag.getTagList("gases", 10), NBTConstants.TANK, 36, 2, occupied);
        payload = tag.copy();
    }

    private static void validate(NBTTagList entries, String key, int limit,
            int kind, boolean[] occupied) {
        boolean[] seen = new boolean[limit];
        for (int index = 0; index < entries.tagCount(); index++) {
            NBTTagCompound entry = entries.getCompoundTagAt(index);
            int slot = entry.getInteger(key);
            if (!entry.hasKey(key, 3) || slot < 0 || slot >= limit || seen[slot]
                    || occupied != null && occupied[slot]) throw new IllegalArgumentException("Invalid port position");
            seen[slot] = true;
            if (kind == 0) {
                ItemStack stack = new ItemStack(entry.getCompoundTag(NBTConstants.ITEM));
                if (entry.hasKey(NBTConstants.SIZE_OVERRIDE, 3))
                    stack.setCount(entry.getInteger(NBTConstants.SIZE_OVERRIDE));
                if (stack.isEmpty() || stack.getCount() > CloudworksPortInputSlot.LIMIT)
                    throw new IllegalArgumentException("Invalid port item");
            } else if (kind == 1) {
                FluidStack fluid = FluidStack.loadFluidStackFromNBT(
                        entry.getCompoundTag(NBTConstants.STORED));
                if (fluid == null || fluid.amount <= 0 || fluid.amount > CloudworksHybridTank.FLUID_CAPACITY)
                    throw new IllegalArgumentException("Invalid port fluid");
            } else {
                GasStack gas = GasStack.readFromNBT(
                        entry.getCompoundTag(NBTConstants.STORED));
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
        return capture(id, owner, input, Collections.emptyList(), Collections.emptyList(), 0);
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

    /** Mount recovery merges the durable snapshot into locally buffered contents instead of erasing them. */
    public void restoreMerged(List<IInventorySlot> slots, List<CloudworksHybridTank> tanks) {
        NBTTagList missingItems = new NBTTagList();
        NBTTagList storedItems = payload.getTagList("items", 10);
        for (int index = 0; index < storedItems.tagCount(); index++) {
            NBTTagCompound entry = storedItems.getCompoundTagAt(index);
            int slot = entry.getInteger(NBTConstants.SLOT);
            if (slot >= 0 && slot < slots.size() && slots.get(slot).isEmpty())
                missingItems.appendTag(entry.copy());
        }
        DataHandlerUtils.readContainers(slots, missingItems);

        List<IExtendedFluidTank> fluidViews =
                tanks.stream().map(CloudworksHybridTank::fluidView).collect(Collectors.toList());
        DataHandlerUtils.readContainers(fluidViews, missingTankEntries(payload.getTagList("fluids", 10), tanks));
        List<IExtendedGasTank> gasViews =
                tanks.stream().map(CloudworksHybridTank::gasView).collect(Collectors.toList());
        DataHandlerUtils.readContainers(gasViews, missingTankEntries(payload.getTagList("gases", 10), tanks));
    }

    private static NBTTagList missingTankEntries(NBTTagList stored, List<CloudworksHybridTank> tanks) {
        NBTTagList missing = new NBTTagList();
        for (int index = 0; index < stored.tagCount(); index++) {
            NBTTagCompound entry = stored.getCompoundTagAt(index);
            int tank = entry.getInteger(NBTConstants.TANK);
            if (tank < 0 || tank >= tanks.size()) continue;
            CloudworksHybridTank current = tanks.get(tank);
            if (current.getFluid() == null && current.getGas() == null) missing.appendTag(entry.copy());
        }
        return missing;
    }

    public double energy() { return payload.getDouble("energy"); }
    public Map<QIOResourceDescriptor, Long> resources() {
        Map<QIOResourceDescriptor, Long> resources = new LinkedHashMap<>();
        NBTTagList items = payload.getTagList("items", 10);
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound entry = items.getCompoundTagAt(i);
            ItemStack stack = new ItemStack(entry.getCompoundTag(NBTConstants.ITEM));
            long amount = entry.hasKey(NBTConstants.SIZE_OVERRIDE, 3)
                    ? entry.getInteger(NBTConstants.SIZE_OVERRIDE) : stack.getCount();
            resources.merge(QIOResourceCodecs.item(stack), amount, Math::addExact);
        }
        NBTTagList fluids = payload.getTagList("fluids", 10);
        for (int i = 0; i < fluids.tagCount(); i++) {
            FluidStack fluid = FluidStack.loadFluidStackFromNBT(
                    fluids.getCompoundTagAt(i).getCompoundTag(NBTConstants.STORED));
            resources.merge(QIOResourceCodecs.fluid(fluid), (long) fluid.amount, Math::addExact);
        }
        NBTTagList gases = payload.getTagList("gases", 10);
        for (int i = 0; i < gases.tagCount(); i++) {
            GasStack gas = GasStack.readFromNBT(
                    gases.getCompoundTagAt(i).getCompoundTag(NBTConstants.STORED));
            resources.merge(QIOResourceCodecs.gas(gas), (long) gas.amount, Math::addExact);
        }
        return resources;
    }
    public NBTTagCompound write() { return payload.copy(); }
    public static CloudworksPortRecord read(NBTTagCompound tag) { return new CloudworksPortRecord(tag); }
}
