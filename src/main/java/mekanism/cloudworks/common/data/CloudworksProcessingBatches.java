package mekanism.cloudworks.common.data;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stable persistence keys for processing cohorts, independent of mechanism branch count. */
public final class CloudworksProcessingBatches {
    private CloudworksProcessingBatches() {}
    public static final String PREFIX = "cloudworks:processing/";
    public static String prefix(String branch) {
        return PREFIX + UUID.nameUUIDFromBytes(branch.getBytes(StandardCharsets.UTF_8)) + "/";
    }
    public static String key(String branch, long sequence) {
        if (sequence < 0) throw new IllegalArgumentException("Negative processing batch sequence");
        return prefix(branch) + sequence;
    }
}
