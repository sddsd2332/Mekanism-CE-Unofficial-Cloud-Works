package mekanism.cloudworks.common.runtime;

import java.util.Arrays;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/** Worker-local output aggregates, invalidated only by changes to their physical output group. */
final class CloudworksRuntimePromises {
    private final long[] amounts;
    // Sparse cache: do not allocate one codec vector for every idle/simple node.
    private final Int2ObjectOpenHashMap<Aggregate> groups = new Int2ObjectOpenHashMap<>();
    private int root = -1;
    private long version = -1;
    private boolean overflow;
    private long rebuilds, cacheHits;

    CloudworksRuntimePromises(int codecs) { amounts = new long[codecs]; }

    boolean prepare(int root, int end, CloudworksRuntimeOperationStore operations, int[] resourceCodec) {
        long currentVersion = operations.promiseVersion(root);
        if (this.root == root && version == currentVersion) { cacheHits++; return !overflow; }
        Aggregate cached = groups.get(root);
        if (cached != null && cached.version == currentVersion) {
            System.arraycopy(cached.amounts, 0, amounts, 0, amounts.length);
            overflow = cached.overflow;
            cacheHits++;
        } else {
            rebuilds++;
            Arrays.fill(amounts, 0);
            overflow = false;
            try {
                for (int node = root; node < end; node++)
                    for (CloudworksRuntimeBatch batch : operations.batches(node)) {
                        if (batch.outputs == null) continue;
                        for (int output = 0; output < batch.outputs.ids.length; output++) {
                            int codec = resourceCodec[batch.outputs.ids[output]];
                            amounts[codec] = Math.addExact(amounts[codec], batch.outputs.amounts[output]);
                        }
                    }
            } catch (ArithmeticException invalid) { overflow = true; }
            // Only overlapping branches or multiple cohorts justify retained aggregates.
            // Single-cohort nodes use the one reusable scratch vector.
            if (end > root + 1 || operations.batchCount(root) > 1) {
                if (cached == null) { cached = new Aggregate(amounts.length); groups.put(root, cached); }
                System.arraycopy(amounts, 0, cached.amounts, 0, amounts.length);
                cached.version = currentVersion;
                cached.overflow = overflow;
            } else if (cached != null) groups.remove(root);
        }
        this.root = root;
        version = currentVersion;
        return !overflow;
    }

    long amount(int codec) { return amounts[codec]; }
    long rebuilds() { return rebuilds; }
    long cacheHits() { return cacheHits; }

    boolean addTo(long[] candidate, int root, int end, CloudworksRuntimeOperationStore operations,
                  int[] resourceCodec) {
        if (!prepare(root, end, operations, resourceCodec)) return false;
        try {
            for (int codec = 0; codec < amounts.length; codec++)
                candidate[codec] = Math.addExact(candidate[codec], amounts[codec]);
            return true;
        } catch (ArithmeticException invalid) { return false; }
    }

    private static final class Aggregate {
        final long[] amounts;
        long version = -1;
        boolean overflow;
        Aggregate(int codecs) { amounts = new long[codecs]; }
    }
}
