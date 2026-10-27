package mekanism.cloudworks.common.content;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import mekanism.api.gas.GasStack;
import mekanism.common.util.StackUtils;

/**
 * Pure buffer/batch transfer math shared by the port tiles. No world access, no
 * manager access, and no buffer mutation before the caller commits: {@link #collect}
 * only reads, {@link #buildRequest} only plans, and {@link #insertPlanned}
 * executes the planned amount into the output-only buffer.
 *
 * <p>Resources are dispatched by QIO family: {@code item} goes to the item slots,
 * {@code fluid}/{@code gas} to the hybrid tanks, and any other registered family
 * (other mods, following the QIO codec structure) is cleanly refused — the cloud
 * side stays descriptor-generic, the port buffer stores the three built-in families.</p>
 */
public final class CloudworksPortIo {
    // Transfers are energy-free (design decision): energy fed into the ports
    // exists to feed production lines or the cloud, never to pay a transfer fee.

    private CloudworksPortIo() {
    }

    /**
     * Merges buffer contents (items + tanks) into per-resource amounts without mutation.
     */
    @Nullable
    public static Map<QIOResourceDescriptor, Long> collect(List<IInventorySlot> itemSlots, List<CloudworksHybridTank> tanks) {
        Map<QIOResourceDescriptor, Long> amounts = null;
        for (IInventorySlot slot : itemSlots) {
            ItemStack stored = slot.getStack();
            if (stored.isEmpty()) continue;
            if (amounts == null) amounts = new LinkedHashMap<>();
            amounts.merge(QIOResourceCodecs.item(stored), (long) stored.getCount(), Math::addExact);
        }
        for (CloudworksHybridTank tank : tanks) {
            FluidStack storedFluid = tank.getFluid();
            if (storedFluid != null && storedFluid.amount > 0) {
                if (amounts == null) amounts = new LinkedHashMap<>();
                amounts.merge(QIOResourceCodecs.fluid(storedFluid), (long) storedFluid.amount, Math::addExact);
            }
            GasStack storedGas = tank.getGas();
            if (storedGas != null && storedGas.amount > 0) {
                if (amounts == null) amounts = new LinkedHashMap<>();
                amounts.merge(QIOResourceCodecs.gas(storedGas), (long) storedGas.amount, Math::addExact);
            }
        }
        return amounts;
    }

    /**
     * Durable removal step: clears every non-empty item slot and tank of a collected batch.
     */
    public static void clearBuffers(List<IInventorySlot> itemSlots, List<CloudworksHybridTank> tanks) {
        for (IInventorySlot slot : itemSlots) {
            if (!slot.isEmpty()) slot.setEmpty();
        }
        for (CloudworksHybridTank tank : tanks) {
            tank.drainAll();
        }
    }

    /**
     * Capacity-bounded request: asks for at most what the buffer could accept right
     * now (item slots top up then free slots fill; tanks top up matching content,
     * free tanks are reserved in snapshot order for whichever family asks first).
     */
    public static Map<QIOResourceDescriptor, Long> buildRequest(Map<QIOResourceDescriptor, Long> available,
                                                         List<IInventorySlot> itemSlots, List<CloudworksHybridTank> tanks) {
        Map<QIOResourceDescriptor, Long> request = new LinkedHashMap<>();
        QIOResourceDescriptor[] plannedSlots = new QIOResourceDescriptor[itemSlots.size()];
        long[] plannedSlotCounts = new long[itemSlots.size()];
        QIOResourceDescriptor[] plannedTanks = new QIOResourceDescriptor[tanks.size()];
        long[] plannedTankCounts = new long[tanks.size()];
        for (Map.Entry<QIOResourceDescriptor, Long> entry : available.entrySet()) {
            long amount = entry.getValue();
            if (amount <= 0) continue;
            long leftover = plan(entry.getKey(), amount, itemSlots, plannedSlots, plannedSlotCounts,
                    tanks, plannedTanks, plannedTankCounts);
            long planned = amount - leftover;
            if (planned > 0) request.put(entry.getKey(), planned);
        }
        return request;
    }

    /**
     * Whether a descriptor belongs to one of the resource families this output
     * buffer can store and has a resolvable template for execution.
     */
    public static boolean isSupportedDownloadResource(@Nullable QIOResourceDescriptor descriptor) {
        if (descriptor == null) return false;
        String family = descriptor.getFamily();
        if (QIOResourceCodecs.ITEM_FAMILY.equals(family)) {
            return descriptor.resolveItemStackTemplate() != null;
        }
        if (QIOResourceCodecs.FLUID_FAMILY.equals(family)) {
            return descriptor.resolveFluidStackTemplate() != null;
        }
        if (QIOResourceCodecs.GAS_FAMILY.equals(family)) {
            return descriptor.resolveGasStackTemplate() != null;
        }
        return false;
    }

    /**
     * Executes one resource amount after the output buffer has been capacity-planned.
     * The download buffer is output-only to players and external automation, so no
     * external mutation can invalidate this plan between the cloud extraction and
     * this server-thread insertion.
     */
    public static void insertPlanned(List<IInventorySlot> itemSlots, List<CloudworksHybridTank> tanks,
                                     QIOResourceDescriptor descriptor, long amount) {
        if (descriptor == null || amount <= 0) return;
        QIOResourceDescriptor[] plannedSlots = new QIOResourceDescriptor[itemSlots.size()];
        long[] plannedSlotCounts = new long[itemSlots.size()];
        QIOResourceDescriptor[] plannedTanks = new QIOResourceDescriptor[tanks.size()];
        long[] plannedTankCounts = new long[tanks.size()];
        plan(descriptor, amount, itemSlots, plannedSlots, plannedSlotCounts,
                tanks, plannedTanks, plannedTankCounts);
        for (int i = 0; i < itemSlots.size(); i++) {
            if (plannedSlots[i] != null) {
                executeSlot(itemSlots.get(i), plannedSlots[i], plannedSlotCounts[i]);
            }
        }
        for (int i = 0; i < tanks.size(); i++) {
            if (plannedTanks[i] != null) {
                executeTank(tanks.get(i), plannedTanks[i], plannedTankCounts[i]);
            }
        }
    }

    public static long totalUnits(Map<QIOResourceDescriptor, Long> amounts) {
        long total = 0;
        for (long amount : amounts.values()) total = Math.addExact(total, amount);
        return total;
    }

    // ------------------------------------------------ 协议储存箱 upload planning

    /**
     * Stored amount of one buffer position (items then tanks, tank fluid before gas).
     */
    public static long bufferContent(List<IInventorySlot> itemSlots, List<CloudworksHybridTank> tanks, int index) {
        if (index < itemSlots.size()) {
            ItemStack stored = itemSlots.get(index).getStack();
            return stored.isEmpty() ? 0 : stored.getCount();
        }
        CloudworksHybridTank tank = tanks.get(index - itemSlots.size());
        FluidStack fluid = tank.getFluid();
        if (fluid != null && fluid.amount > 0) return fluid.amount;
        return tank.getGasAmount();
    }

    @Nullable
    public static QIOResourceDescriptor bufferDescriptor(List<IInventorySlot> itemSlots, List<CloudworksHybridTank> tanks, int index) {
        if (index < itemSlots.size()) {
            ItemStack stored = itemSlots.get(index).getStack();
            return stored.isEmpty() ? null : QIOResourceCodecs.item(stored);
        }
        CloudworksHybridTank tank = tanks.get(index - itemSlots.size());
        FluidStack fluid = tank.getFluid();
        if (fluid != null && fluid.amount > 0) return QIOResourceCodecs.fluid(fluid);
        GasStack gas = tank.getGas();
        return gas == null || gas.amount <= 0 ? null : QIOResourceCodecs.gas(gas);
    }

    /**
     * Plans one upload round in buffer order (item slots first, then tanks): every
     * position uploads min(content, remaining per-type room); a zero-progress
     * position is skipped for one grace round and pauses the port on the second
     * consecutive occurrence. Any earlier fitting takes stay in the plan and
     * are committed before the port pauses. Slots sharing one resource type
     * share the room budget.
     */
    public static UploadPlan planUpload(List<IInventorySlot> itemSlots, List<CloudworksHybridTank> tanks,
                                 ToLongFunction<QIOResourceDescriptor> roomOf, boolean[] failedLastRound) {
        return planUpload(itemSlots, tanks, roomOf, failedLastRound, Long.MAX_VALUE);
    }

    public static UploadPlan planUpload(List<IInventorySlot> itemSlots, List<CloudworksHybridTank> tanks,
            ToLongFunction<QIOResourceDescriptor> roomOf, boolean[] failedLastRound, long totalRoom) {
        if (totalRoom < 0) throw new IllegalArgumentException("Negative upload room");
        int bufferSize = itemSlots.size() + tanks.size();
        Map<QIOResourceDescriptor, Long> remainingRoom = new LinkedHashMap<>();
        UploadPlan plan = new UploadPlan(bufferSize, -1);
        for (int index = 0; index < bufferSize; index++) {
            long content = bufferContent(itemSlots, tanks, index);
            if (content <= 0) {
                failedLastRound[index] = false;   // empty positions never block
                continue;
            }
            QIOResourceDescriptor descriptor = bufferDescriptor(itemSlots, tanks, index);
            if (descriptor == null) {
                failedLastRound[index] = false;   // unresolved content: skip without failing
                continue;
            }
            long room = remainingRoom.getOrDefault(descriptor, -1L);
            if (room < 0) {
                room = Math.max(0L, roomOf.applyAsLong(descriptor));
                remainingRoom.put(descriptor, room);
            }
            long take = Math.min(content, Math.min(room, totalRoom));
            if (take == 0) {
                if (failedLastRound[index]) {
                    // Keep earlier positions' fitting transfers; pause after this partial round commits.
                    plan.pauseIndex = index;
                    return plan;
                }
                failedLastRound[index] = true;
                continue;
            }
            remainingRoom.put(descriptor, room - take);
            totalRoom -= take;
            plan.takes[index] = take;
            plan.descriptors[index] = descriptor;
            plan.fitting.merge(descriptor, take, Math::addExact);
            failedLastRound[index] = false;   // any progress this round restarts the grace
        }
        return plan;
    }

    /**
     * Commit step: drains every planned take from its buffer position.
     */
    public static void applyDrains(List<IInventorySlot> itemSlots, List<CloudworksHybridTank> tanks, long[] takes) {
        for (int index = 0; index < takes.length; index++) {
            if (takes[index] <= 0) continue;
            if (index < itemSlots.size()) {
                IInventorySlot slot = itemSlots.get(index);
                ItemStack stored = slot.getStack();
                long left = stored.getCount() - takes[index];
                if (left <= 0) slot.setEmpty();
                else slot.setStack(StackUtils.size(stored, (int) left));
            } else {
                CloudworksHybridTank tank = tanks.get(index - itemSlots.size());
                FluidStack fluid = tank.getFluid();
                if (fluid != null && fluid.amount > 0) tank.shrinkFluid((int) takes[index]);
                else tank.shrinkGas((int) takes[index]);
            }
        }
    }

    /**
     * QIO-importer remainder path: returns a shortfall straight back into the
     * buffer position it was drained from (the position just yielded exactly
     * this many, so the return always fits; a refusal is defensive-only).
     */
    public static void refillPosition(List<IInventorySlot> itemSlots, List<CloudworksHybridTank> tanks, int index,
                               QIOResourceDescriptor descriptor, long amount) {
        if (amount <= 0 || descriptor == null) return;
        if (index < itemSlots.size()) {
            ItemStack template = descriptor.resolveItemStackTemplate();
            if (template == null) return;   // unresolved: nothing to give back (planner guarantees resolution)
            long remaining = amount;
            while (remaining > 0) {
                ItemStack chunk = template.copy();
                chunk.setCount((int) Math.min(remaining, chunk.getMaxStackSize()));
                int offered = chunk.getCount();
                ItemStack leftover = itemSlots.get(index).insertItem(chunk, Action.EXECUTE, AutomationType.INTERNAL);
                int accepted = offered - leftover.getCount();
                if (accepted <= 0) return;   // defensive: slot refused its own yield back
                remaining -= accepted;
            }
            return;
        }
        CloudworksHybridTank tank = tanks.get(index - itemSlots.size());
        String family = descriptor.getFamily();
        if (QIOResourceCodecs.FLUID_FAMILY.equals(family)) {
            FluidStack template = descriptor.resolveFluidStackTemplate();
            if (template == null) return;
            if (tank.getFluid() == null) tank.setFluid(new FluidStack(template, (int) amount));
            else tank.growFluid((int) amount);
        } else if (QIOResourceCodecs.GAS_FAMILY.equals(family)) {
            GasStack template = descriptor.resolveGasStackTemplate();
            if (template == null) return;
            if (tank.getGas() == null) tank.setGas(new GasStack(template.getGas(), (int) amount));
            else tank.growGas((int) amount);
        }
    }

    /**
     * Plans placement of {@code amount} units of one resource across both buffer
     * kinds. A slot/tank already planned for another resource in the same pass is
     * skipped. Returns the leftover (0 when it fits); resources of families the
     * port cannot store are refused entirely.
     */
    private static long plan(QIOResourceDescriptor descriptor, long amount,
                             List<IInventorySlot> itemSlots, @Nullable QIOResourceDescriptor[] plannedSlots, long[] plannedSlotCounts,
                             List<CloudworksHybridTank> tanks, @Nullable QIOResourceDescriptor[] plannedTanks, long[] plannedTankCounts) {
        String family = descriptor.getFamily();
        long remaining = amount;
        if (QIOResourceCodecs.ITEM_FAMILY.equals(family)) {
            if (descriptor.resolveItemStackTemplate() == null) return remaining;
            for (int i = 0; i < itemSlots.size() && remaining > 0; i++) {
                if (plannedSlots[i] != null && !plannedSlots[i].equals(descriptor)) continue;
                int probe = (int) Math.min(Integer.MAX_VALUE, remaining);
                int capacity = itemSlots.get(i).getResourceCapacity(descriptor, Integer.MAX_VALUE,
                        true, AutomationType.INTERNAL);
                capacity = (int) Math.max(0, capacity - plannedSlotCounts[i]);
                if (capacity <= 0) continue;
                long take = Math.min((long) capacity, probe);
                plannedSlots[i] = descriptor;
                plannedSlotCounts[i] = Math.addExact(plannedSlotCounts[i], take);
                remaining -= take;
            }
            return remaining;
        }
        if (QIOResourceCodecs.FLUID_FAMILY.equals(family) || QIOResourceCodecs.GAS_FAMILY.equals(family)) {
            boolean fluid = QIOResourceCodecs.FLUID_FAMILY.equals(family);
            // An unresolved template can never be executed; refuse the whole amount.
            if (fluid ? descriptor.resolveFluidStackTemplate() == null : descriptor.resolveGasStackTemplate() == null) {
                return remaining;
            }
            for (int i = 0; i < tanks.size() && remaining > 0; i++) {
                if (plannedTanks[i] != null && !plannedTanks[i].equals(descriptor)) continue;
                CloudworksHybridTank tank = tanks.get(i);
                int capacity = fluid
                        ? tank.fluidCapacityFor(descriptor.resolveFluidStackTemplate())
                        : tank.gasCapacityFor(descriptor.resolveGasStackTemplate());
                capacity = (int) Math.max(0, capacity - plannedTankCounts[i]);
                if (capacity <= 0) continue;
                long take = Math.min((long) capacity, remaining);
                plannedTanks[i] = descriptor;
                plannedTankCounts[i] = Math.addExact(plannedTankCounts[i], take);
                remaining -= take;
            }
            return remaining;
        }
        return remaining;   // other mod families: this port variant has no container for them
    }

    private static void executeSlot(IInventorySlot slot, @Nullable QIOResourceDescriptor descriptor, long amount) {
        if (descriptor == null || amount <= 0) return;
        ItemStack template = descriptor.resolveItemStackTemplate();
        if (template == null) return;
        long remaining = amount;
        while (remaining > 0) {
            ItemStack chunk = template.copy();
            chunk.setCount((int) Math.min(remaining, slot.getLimit(template)));
            slot.insertItem(chunk, Action.EXECUTE, AutomationType.INTERNAL);
            remaining -= chunk.getCount();
        }
    }

    private static void executeTank(CloudworksHybridTank tank, @Nullable QIOResourceDescriptor descriptor, long amount) {
        if (descriptor == null || amount <= 0) return;
        String family = descriptor.getFamily();
        if (QIOResourceCodecs.FLUID_FAMILY.equals(family)) {
            FluidStack template = descriptor.resolveFluidStackTemplate();
            if (template == null) return;
            if (tank.getFluid() == null) tank.setFluid(new FluidStack(template, (int) amount));
            else tank.growFluid((int) amount);
            return;
        }
        if (QIOResourceCodecs.GAS_FAMILY.equals(family)) {
            GasStack template = descriptor.resolveGasStackTemplate();
            if (template == null) return;
            if (tank.getGas() == null) tank.setGas(new GasStack(template.getGas(), (int) amount));
            else tank.growGas((int) amount);
            return;
        }
    }

    /**
     * One planned upload round: per-slot takes plus, when a slot fails twice in a
     * row, the pause trigger. Takes planned before the trigger remain committed.
     */
    public static final class UploadPlan {
        public final Map<QIOResourceDescriptor, Long> fitting = new LinkedHashMap<>();
        public final long[] takes;
        public final QIOResourceDescriptor[] descriptors;
        /**
         * -1, or the buffer index whose second consecutive zero-progress round pauses the port.
         */
        public int pauseIndex;

        UploadPlan(int bufferSize, int pauseIndex) {
            this.takes = new long[bufferSize];
            this.descriptors = new QIOResourceDescriptor[bufferSize];
            this.pauseIndex = pauseIndex;
        }

        public boolean pauses() {
            return pauseIndex >= 0;
        }
    }
}
