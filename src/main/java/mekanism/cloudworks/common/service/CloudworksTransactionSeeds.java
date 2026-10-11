package mekanism.cloudworks.common.service;

/**
 * One-shot entropy source for virtual transaction seeds. The seed is drawn on
 * the owner (main server) thread when a snapshot is captured and becomes plain
 * immutable data of the transaction; workers derive per-node streams from it
 * and never touch this source. No random state is ever persisted: recovery and
 * replay only ever read the committed output promise.
 */
public final class CloudworksTransactionSeeds {
    private static final java.util.function.LongSupplier PRODUCTION = new java.util.function.LongSupplier() {
        private final java.util.SplittableRandom entropy =
                new java.util.SplittableRandom(new java.security.SecureRandom().nextLong());

        @Override
        public long getAsLong() {
            return entropy.nextLong();
        }
    };
    private static volatile java.util.function.LongSupplier source = PRODUCTION;

    private CloudworksTransactionSeeds() {
    }

    /**
     * Draws one transaction seed; owner thread only.
     */
    public static long next() {
        return source.getAsLong();
    }

    /**
     * Pins a deterministic source for tests; {@code null} restores production entropy.
     */
    public static void debugPin(java.util.function.LongSupplier pinned) {
        source = pinned == null ? PRODUCTION : pinned;
    }
}
