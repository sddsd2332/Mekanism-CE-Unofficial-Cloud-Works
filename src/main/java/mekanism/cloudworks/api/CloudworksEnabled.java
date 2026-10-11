package mekanism.cloudworks.api;

import net.minecraft.nbt.NBTTagCompound;

public interface CloudworksEnabled {
    boolean isEnabled();

    void setEnabled(boolean enabled);

    NBTTagCompound serializeNBT();

    void deserializeNBT(NBTTagCompound tag);
}
