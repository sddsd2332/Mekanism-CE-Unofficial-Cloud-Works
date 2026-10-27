package mekanism.cloudworks.api.card;

import java.util.Objects;
import net.minecraft.util.ResourceLocation;

/** Declarative cloud effect. Addons cannot mutate a cloud or run callbacks in a card transaction. */
public final class CloudworksCardType {
    public enum Target {
        LINE_LIMIT, NODE_LIMIT, WORK_RATE, OUTPUT_MULTIPLIER,
        WAREHOUSE_CAP_MULTIPLIER, OFFLINE_PROCESSING, CLOUD_STATE
    }
    public final ResourceLocation id;
    public final Target target;
    public final int amount, limit;
    public final boolean unlock;
    public final String successKey, refusalKey;

    public CloudworksCardType(ResourceLocation id, Target target, int amount, int limit, boolean unlock,
            String successKey, String refusalKey) {
        this.id = Objects.requireNonNull(id);
        this.target = Objects.requireNonNull(target);
        if ("cloudworks".equals(id.getNamespace()) || "mekanism".equals(id.getNamespace())
                || amount < 1 || unlock && amount != 1
                || target == Target.CLOUD_STATE && limit < 1
                || target != Target.CLOUD_STATE && limit != -1
                || (target == Target.OFFLINE_PROCESSING) != unlock
                        && target != Target.CLOUD_STATE)
            throw new IllegalArgumentException("Invalid card declaration: " + id);
        this.amount = amount;
        this.limit = limit;
        this.unlock = unlock;
        this.successKey = key(successKey);
        this.refusalKey = key(refusalKey);
    }

    private static String key(String key) {
        if (key == null || key.length() > 256 || !key.matches("[a-z0-9_.:-]+"))
            throw new IllegalArgumentException("Invalid card message key");
        return key;
    }
}
