package mekanism.cloudworks.common.card;

import java.util.Arrays;
import java.util.Objects;
import mekanism.cloudworks.common.config.CloudworksConfig;
import mekanism.cloudworks.common.data.CloudworksData;
import net.minecraft.util.ResourceLocation;
import mekanism.cloudworks.api.card.CloudworksCardType;
import mekanism.cloudworks.common.service.CloudworksOutputMultiplier;

/** A finite, server-declared effect on an existing cloud property; no script or native callbacks. */
public final class CloudworksCardEffect {
    public enum Target {
        LINE_LIMIT, NODE_LIMIT, WORK_RATE, OUTPUT_MULTIPLIER,
        WAREHOUSE_CAP_MULTIPLIER, OFFLINE_PROCESSING,
        /** Generic typed channel: the level lives in the cloud effect state map. */
        CLOUD_STATE;

        public boolean unlock() {
            return this == OFFLINE_PROCESSING;
        }

        /** Generic-channel effects carry their own unlock flag. */
        boolean unlock(boolean genericUnlock) {
            return this == CLOUD_STATE ? genericUnlock : unlock();
        }
    }

    public enum Status {
        APPLIED, NOT_ENABLED, AT_LIMIT, ALREADY_UNLOCKED, SERVER_DISABLED
    }

    public static final class Result {
        private final CloudworksCardEffect effect;
        private final Status status;
        private final int value;

        private Result(CloudworksCardEffect effect, Status status, int value) {
            this.effect = effect;
            this.status = status;
            this.value = value;
        }

        public boolean applied() { return status == Status.APPLIED; }
        public Status status() { return status; }
        public int value() { return value; }

        public String messageKey() {
            return switch (status) {
                case APPLIED -> effect.successKey;
                case NOT_ENABLED -> "chat.cloudworks.not_enabled";
                case AT_LIMIT, ALREADY_UNLOCKED -> effect.refusalKey;
                case SERVER_DISABLED -> "cloudworks.error.offline_disabled";
            };
        }

        public Object[] messageArguments() {
            if (effect.target.unlock(effect.genericUnlock)) return new Object[0];
            if (!applied())
                return status == Status.AT_LIMIT || status == Status.ALREADY_UNLOCKED
                        ? effect.leadingArguments.clone() : new Object[0];
            Object[] arguments = Arrays.copyOf(effect.leadingArguments, effect.leadingArguments.length + 1);
            arguments[effect.leadingArguments.length] = effect.target == Target.OUTPUT_MULTIPLIER
                    ? CloudworksOutputMultiplier.format(value) : value;
            return arguments;
        }

        public int legacyValue() {
            return switch (status) {
                case APPLIED -> value;
                case NOT_ENABLED -> -1;
                case AT_LIMIT, ALREADY_UNLOCKED -> -2;
                case SERVER_DISABLED -> -3;
            };
        }
    }

    private final ResourceLocation id;
    private final Target target;
    private final int amount;
    private final String successKey, refusalKey;
    /** Declared limit of generic-channel effects; -1 for settings-driven targets. */
    private final int declaredLimit;
    private final boolean genericUnlock;
    /** Static chat-message arguments (e.g. the axis word) prepended to the numeric value. */
    private final Object[] leadingArguments;

    private CloudworksCardEffect(ResourceLocation id, Target target, int amount,
            String successKey, String refusalKey) {
        this(id, target, amount, successKey, refusalKey, -1, false);
    }

    public static CloudworksCardEffect fromApi(CloudworksCardType type) {
        Target target = Target.valueOf(type.target.name());
        return new CloudworksCardEffect(type.id, target, type.amount, type.successKey, type.refusalKey,
                type.limit, type.unlock);
    }

    private CloudworksCardEffect(ResourceLocation id, Target target, int amount,
            String successKey, String refusalKey, int declaredLimit, boolean genericUnlock,
            Object... leadingArguments) {
        this.id = Objects.requireNonNull(id, "id");
        this.target = Objects.requireNonNull(target, "target");
        if (amount <= 0 || target.unlock(genericUnlock) && amount != 1)
            throw new IllegalArgumentException("Invalid cloud card increment");
        if (target == Target.CLOUD_STATE && declaredLimit < 1)
            throw new IllegalArgumentException("Generic cloud effects require a positive limit");
        if (target != Target.CLOUD_STATE && declaredLimit != -1)
            throw new IllegalArgumentException("Only generic cloud effects carry a declared limit");
        this.amount = amount;
        this.successKey = messageKey(successKey);
        this.refusalKey = messageKey(refusalKey);
        this.declaredLimit = declaredLimit;
        this.genericUnlock = genericUnlock;
        this.leadingArguments = leadingArguments.clone();
    }

    public static CloudworksCardEffect increase(ResourceLocation id, Target target, int amount,
            String successKey, String refusalKey) {
        if (Objects.requireNonNull(target).unlock() || target == Target.CLOUD_STATE)
            throw new IllegalArgumentException("Numeric target is not a dedicated field");
        return new CloudworksCardEffect(id, target, amount, successKey, refusalKey);
    }

    public static CloudworksCardEffect unlock(ResourceLocation id, Target target, String successKey, String refusalKey) {
        if (!Objects.requireNonNull(target).unlock()) throw new IllegalArgumentException("Numeric target is not an unlock");
        return new CloudworksCardEffect(id, target, 1, successKey, refusalKey);
    }

    /**
     * Generic numeric cloud effect: levels accumulate in the cloud effect
     * state map up to the server-declared limit. Runtime workers never
     * interpret the level; consumers read it through the query surfaces.
     */
    public static CloudworksCardEffect cloudState(ResourceLocation id, int amount, int limit,
            String successKey, String refusalKey, Object... leadingMessageArguments) {
        return new CloudworksCardEffect(id, Target.CLOUD_STATE, amount, successKey, refusalKey,
                limit, false, leadingMessageArguments);
    }

    /** Generic unlock: level goes 0 → 1 and stays. */
    public static CloudworksCardEffect unlockCloudState(ResourceLocation id,
            String successKey, String refusalKey) {
        return new CloudworksCardEffect(id, Target.CLOUD_STATE, 1, successKey, refusalKey, 1, true);
    }


    private static String messageKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 256 || !key.matches("[a-z0-9_.:-]+"))
            throw new IllegalArgumentException("Invalid card message key");
        return key;
    }

    public ResourceLocation id() { return id; }
    public Target target() { return target; }
    public int amount() { return amount; }

    /** Trusted server code may reuse a numeric template with another fixed increment. */
    public CloudworksCardEffect withAmount(int value) {
        if (target.unlock(genericUnlock)) throw new IllegalArgumentException("Unlock effects have no configurable increment");
        return new CloudworksCardEffect(id, target, value, successKey, refusalKey, declaredLimit, genericUnlock,
                leadingArguments);
    }

    /** The manager invokes this on a detached candidate, never on published state. */
    public Result apply(CloudworksData cloud, CloudworksConfig.Settings settings) {
        if (!cloud.enabled()) return result(Status.NOT_ENABLED, current(cloud));
        if (target == Target.OFFLINE_PROCESSING && !settings.offlineProcessingEnabled)
            return result(Status.SERVER_DISABLED, current(cloud));
        int previous = current(cloud);
        if (target.unlock(genericUnlock) && previous != 0) return result(Status.ALREADY_UNLOCKED, previous);
        int maximum = maximum(settings);
        if (previous >= maximum) return result(Status.AT_LIMIT, previous);
        int next = (int) Math.min(maximum, (long) previous + amount);
        switch (target) {
            case LINE_LIMIT -> cloud.setLimits(next, Math.max(cloud.maxLines(), next), cloud.workRate());
            case NODE_LIMIT -> cloud.setMaxMachineNodes(next);
            case WORK_RATE -> cloud.setLimits(cloud.unlockedLines(), cloud.maxLines(), next);
            case OUTPUT_MULTIPLIER -> cloud.setOutputMultiplierUnits(next);
            case WAREHOUSE_CAP_MULTIPLIER -> cloud.setWarehouseCapMultiplier(next);
            case OFFLINE_PROCESSING -> cloud.setOfflineProcessingUnlocked(true);
            case CLOUD_STATE -> cloud.cloudEffects().set(id, next);
        }
        return result(Status.APPLIED, next);
    }

    private int current(CloudworksData cloud) {
        return switch (target) {
            case LINE_LIMIT -> cloud.unlockedLines();
            case NODE_LIMIT -> cloud.maxMachineNodes();
            case WORK_RATE -> cloud.workRate();
            case OUTPUT_MULTIPLIER -> cloud.outputMultiplierUnits();
            case WAREHOUSE_CAP_MULTIPLIER -> cloud.warehouseCapMultiplier();
            case OFFLINE_PROCESSING -> cloud.offlineProcessingUnlocked() ? 1 : 0;
            case CLOUD_STATE -> (int) Math.min(Integer.MAX_VALUE, cloud.cloudEffects().level(id));
        };
    }

    private int maximum(CloudworksConfig.Settings settings) {
        return switch (target) {
            case LINE_LIMIT -> settings.maxLines;
            case NODE_LIMIT -> settings.maxMachineNodes;
            case WORK_RATE -> settings.maxWorkRate;
            case OUTPUT_MULTIPLIER -> settings.maxOutputMultiplierUnits;
            case WAREHOUSE_CAP_MULTIPLIER -> settings.maxWarehouseCapMultiplier;
            case OFFLINE_PROCESSING -> 1;
            case CLOUD_STATE -> declaredLimit;
        };
    }

    private Result result(Status status, int value) {
        return new Result(this, status, value);
    }
}
