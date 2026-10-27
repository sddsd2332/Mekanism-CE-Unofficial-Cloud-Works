package mekanism.cloudworks.common.special;

import net.minecraft.nbt.NBTTagCompound;

/** Basic parser for resource data; unknown fields remain in the original compound. */
public final class CloudworksInfiniteResourceRules {
    public final long outputAmount, nodeLimit;
    public final boolean unlimitedPlacement;

    private CloudworksInfiniteResourceRules(long outputAmount, boolean unlimitedPlacement, long nodeLimit) {
        this.outputAmount = outputAmount;
        this.unlimitedPlacement = unlimitedPlacement;
        this.nodeLimit = nodeLimit;
    }

    public static CloudworksInfiniteResourceRules parse(NBTTagCompound data) {
        long output = positiveInteger(data, "outputAmount");
        boolean unlimited = true;
        if (data.hasKey("unlimitedPlacement")) {
            if (!data.hasKey("unlimitedPlacement", 1)
                    || data.getByte("unlimitedPlacement") < 0 || data.getByte("unlimitedPlacement") > 1)
                throw new IllegalArgumentException("Invalid unlimitedPlacement");
            unlimited = data.getBoolean("unlimitedPlacement");
        }
        if (!unlimited && !data.hasKey("nodeLimit"))
            throw new IllegalArgumentException("Missing nodeLimit");
        long limit = positiveInteger(data, "nodeLimit");
        return new CloudworksInfiniteResourceRules(output, unlimited, limit);
    }

    public static boolean improves(CloudworksInfiniteResourceRules previous, CloudworksInfiniteResourceRules next) {
        return next.outputAmount > previous.outputAmount
                || !previous.unlimitedPlacement && (next.unlimitedPlacement || next.nodeLimit > previous.nodeLimit);
    }

    public static CloudworksInfiniteResourceRules best(CloudworksInfiniteResourceRules previous,
            CloudworksInfiniteResourceRules next) {
        return new CloudworksInfiniteResourceRules(Math.max(previous.outputAmount, next.outputAmount),
                previous.unlimitedPlacement || next.unlimitedPlacement,
                Math.max(previous.nodeLimit, next.nodeLimit));
    }

    /** Keep all addon data from the existing asset while changing only built-in control fields. */
    public NBTTagCompound applyTo(NBTTagCompound previousData) {
        NBTTagCompound result = previousData.copy();
        result.setLong("outputAmount", outputAmount);
        result.setBoolean("unlimitedPlacement", unlimitedPlacement);
        result.setLong("nodeLimit", nodeLimit);
        return result;
    }

    private static long positiveInteger(NBTTagCompound data, String key) {
        if (!data.hasKey(key)) return Long.MAX_VALUE;
        int type = data.getTagId(key);
        if (type < 1 || type > 4 || data.getLong(key) <= 0)
            throw new IllegalArgumentException("Invalid " + key);
        return data.getLong(key);
    }
}
