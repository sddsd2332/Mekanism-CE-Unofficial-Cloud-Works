package mekanism.cloudworks.common.capability;

import mekanism.cloudworks.api.CloudworksEnabled;
import net.minecraft.nbt.NBTTagCompound;

public final class DefaultCloudworksEnabled implements CloudworksEnabled {
    private boolean enabled;

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public NBTTagCompound serializeNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("enabled", enabled);
        return tag;
    }

    @Override
    public void deserializeNBT(NBTTagCompound tag) {
        enabled = tag != null && tag.getBoolean("enabled");
    }
}
