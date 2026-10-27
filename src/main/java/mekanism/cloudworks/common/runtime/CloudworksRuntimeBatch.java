package mekanism.cloudworks.common.runtime;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** One sparse cohort of equal-start, equal-duration work sharing its parent's inventories. */
final class CloudworksRuntimeBatch {
    net.minecraft.util.ResourceLocation requiredEnvironmentAxis;
    UUID id = newId();
    int operations, parallelUnits, duration, progress;
    long lifecycleId, sequence = -1;
    boolean started, completionPrepared, reservedEarly;
    byte state = CloudworksRuntimeMachineStore.READY;
    String recipeId = "";
    double extraEnergyPerTick, modulePower = 1D;
    CloudworksRuntimeRecipe.Allocation inputs, outputs, chanceOutputs;
    /**
     * One-tick module reservations owned by this batch until it actually starts.
     * Keeping the sparse boundary data on the batch avoids hashing every batch
     * in simulation-wide maps during the per-node settlement pass.
     */
    PendingModuleWork pendingModuleWork;

    PendingModuleWork pendingModuleWork() {
        if (pendingModuleWork == null) pendingModuleWork = new PendingModuleWork();
        return pendingModuleWork;
    }

    static final class PendingModuleWork {
        Map<Long, Long> costs;
        Map<String, Double> remainders;
    }

    private static UUID newId() {
        // Persistent promise/cancellation identity, never an authentication token.
        // Retain UUID v4/variant bits and the existing NBT representation.
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new UUID((random.nextLong() & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L,
                (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L);
    }
}
