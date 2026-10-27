package mekanism.cloudworks.common.service;
import java.security.SecureRandom;
import java.util.function.LongSupplier;
import java.util.SplittableRandom;

/**
 * One-shot entropy source for virtual transaction seeds. The seed is drawn on
 * the owner (main server) thread when a snapshot is captured and becomes plain
 * immutable data of the transaction; workers derive per-node streams from it
 * and never touch this source. No random state is ever persisted: recovery and
 * replay only ever read the committed output promise.
 */
public final class CloudworksTransactionSeeds {
    private static final LongSupplier DEFAULT_PRODUCTION = new LongSupplier() {
        private final SplittableRandom entropy =
                new SplittableRandom(new SecureRandom().nextLong());

        @Override
        public long getAsLong() {
            return entropy.nextLong();
        }
    };
    private static volatile LongSupplier productionSource = DEFAULT_PRODUCTION;
    private static volatile LongSupplier source = DEFAULT_PRODUCTION;

    private CloudworksTransactionSeeds() {
    }

    /**
     * Draws one transaction seed; owner thread only.
     */
    public static long next() {
        return source.getAsLong();
    }

    /**
     * Installs the server-thread world-backed production source. A null source
     * restores the standalone entropy source used by tests and non-server code.
     */
    public static void production(LongSupplier worldSource) {
        productionSource = worldSource == null ? DEFAULT_PRODUCTION : worldSource;
        source = productionSource;
    }

    /**
     * Pins a deterministic source for tests; {@code null} restores production entropy.
     */
    public static void debugPin(LongSupplier pinned) {
        source = pinned == null ? productionSource : pinned;
    }
}
