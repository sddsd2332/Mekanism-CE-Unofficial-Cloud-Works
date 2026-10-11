package mekanism.cloudworks.common.service;

import mekanism.cloudworks.api.CloudworksUploadReservation;
import mekanism.cloudworks.api.ICloudworksUploadSource;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.util.*;

/**
 * The first real {@link ICloudworksUploadSource}: the player's own inventory
 * slots (GPT design §7.1 semantics). The slot list is a LIVE reference (the
 * command passes the inventory's backing list); nothing is deducted at
 * reserve time — the physical deduction happens in {@link #acknowledge}, strictly
 * after the cloud side committed durably, and {@link #rollback} simply drops
 * the plan. Pending reservations keep counting as unavailable so two
 * concurrent uploads can never overdraw the same slots.
 *
 * <p>Instance methods run on the server thread only, matching the coordinator
 * contract; the class itself has no player dependency so the semantics are
 * unit-testable without a server.</p>
 */
public final class CloudworksPlayerInventorySource implements ICloudworksUploadSource {
    private final List<ItemStack> slots;
    private final UUID sourceId;
    private final UUID owner;
    private final Runnable changeNotifier;
    /**
     * Pending transfer plans: transferId -> per-slot deduction plan.
     */
    private final Map<UUID, Plan> pending = new LinkedHashMap<>();
    /**
     * Deductions that could not be fully served; diagnostics and audit.
     */
    private int shortfalls;

    public CloudworksPlayerInventorySource(List<ItemStack> slots, UUID sourceId, UUID owner,
                                           Runnable changeNotifier) {
        this.slots = slots;
        this.sourceId = sourceId;
        this.owner = owner;
        this.changeNotifier = changeNotifier;
    }

    /**
     * Exact identity: item + damage + full tag (ForgeCaps excluded), like node merges.
     */
    private static boolean sameMachine(ItemStack stored, ItemStack wanted) {
        return ItemStack.areItemsEqual(stored, wanted)
                && strip(stored).equals(strip(wanted));
    }

    private static NBTTagCompound strip(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) return new NBTTagCompound();
        NBTTagCompound copy = tag.copy();
        copy.removeTag("ForgeCaps");
        return copy;
    }

    private static long countOf(Plan plan) {
        long total = 0;
        for (int amount : plan.amounts) total += amount;
        return total;
    }

    @Override
    public UUID sourceId() {
        return sourceId;
    }

    @Override
    public UUID owner() {
        return owner;
    }

    @Override
    public UUID debitPlayer() {
        return sourceId;
    }

    /**
     * Deduction shortfalls observed at acknowledge; audit counter.
     */
    public int shortfalls() {
        return shortfalls;
    }

    /**
     * Matching slot indices in inventory order; reserved units are subtracted.
     */
    private List<Integer> matching(ItemStack requested) {
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < slots.size(); index++) {
            ItemStack stored = slots.get(index);
            if (stored != null && !stored.isEmpty() && sameMachine(stored, requested))
                indices.add(index);
        }
        return indices;
    }

    private int reservedOn(int slotIndex) {
        int reserved = 0;
        for (Plan plan : pending.values())
            for (int i = 0; i < plan.slotIndices.length; i++)
                if (plan.slotIndices[i] == slotIndex) reserved += plan.amounts[i];
        return reserved;
    }

    @Override
    public boolean canUpload(UUID lineId, ItemStack requested, int count) {
        if (requested == null || requested.isEmpty() || count <= 0) return false;
        int available = 0;
        for (int index : matching(requested))
            available += Math.max(0, slots.get(index).getCount() - reservedOn(index));
        return available >= count;
    }

    @Override
    public CloudworksUploadReservation reserve(UUID lineId, ItemStack requested,
                                               int count, UUID transferId) {
        if (!canUpload(lineId, requested, count)) return null;
        // Greedy plan in slot order: exactly `count` units across matching slots.
        List<Integer> plannedSlots = new ArrayList<>();
        List<Integer> plannedAmounts = new ArrayList<>();
        ItemStack template = null;
        int remaining = count;
        for (int index : matching(requested)) {
            if (remaining == 0) break;
            int free = Math.max(0, slots.get(index).getCount() - reservedOn(index));
            int take = Math.min(free, remaining);
            if (take <= 0) continue;
            if (template == null) template = slots.get(index).copy();
            plannedSlots.add(index);
            plannedAmounts.add(take);
            remaining -= take;
        }
        if (remaining != 0 || template == null) return null;
        int[] planSlots = new int[plannedSlots.size()];
        int[] planAmounts = new int[plannedAmounts.size()];
        for (int i = 0; i < planSlots.length; i++) {
            planSlots[i] = plannedSlots.get(i);
            planAmounts[i] = plannedAmounts.get(i);
        }
        pending.put(transferId, new Plan(planSlots, planAmounts, template));
        return new CloudworksUploadReservation(transferId, owner, sourceId, lineId,
                "inventory", template, count, CloudworksUploadReservation.State.RESERVED);
    }

    @Override
    public long acknowledge(UUID transferId) {
        Plan plan = pending.get(transferId);
        if (plan == null) return 0L;   // idempotent repeat
        pending.remove(transferId);
        // Identity-validated whole-inventory deduction (GPT review 4): the plan's
        // slot indices may be stale — the player can move, spend or replace the
        // machines while the acknowledgement is parked. Only stacks that STILL
        // match the reserved exact identity are ever touched, so unrelated items
        // can neither be destroyed nor short-counted silently.
        long remaining = countOf(plan);
        for (int index = 0; index < slots.size() && remaining > 0; index++) {
            ItemStack stored = slots.get(index);
            if (stored == null || stored.isEmpty() || !sameMachine(stored, plan.template)) continue;
            long take = Math.min(remaining, stored.getCount());
            if (take > 0) {
                stored.shrink((int) take);
                remaining -= take;
            }
        }
        if (remaining > 0) {
            shortfalls++;
            System.err.println("CLOUDWORKS-DEBIT shortfall transfer=" + transferId
                    + " missing=" + remaining + " machine=" + plan.template.getItem().getRegistryName());
        }
        if (changeNotifier != null) changeNotifier.run();
        return remaining;   // >0 keeps the durable entry alive for retry and audit
    }

    @Override
    public void rollback(UUID transferId) {
        Plan plan = pending.remove(transferId);
        if (plan == null) return;   // idempotent repeat
        // Nothing was ever deducted: the machines never left the slots.
    }

    /**
     * Pending reservation count; diagnostics and tests.
     */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * One upload's deduction plan over live slot references.
     */
    private static final class Plan {
        final int[] slotIndices;
        final int[] amounts;
        final ItemStack template;

        Plan(int[] slotIndices, int[] amounts, ItemStack template) {
            this.slotIndices = slotIndices;
            this.amounts = amounts;
            this.template = template;
        }
    }
}
