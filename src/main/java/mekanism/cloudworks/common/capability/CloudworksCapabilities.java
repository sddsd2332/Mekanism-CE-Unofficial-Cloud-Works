package mekanism.cloudworks.common.capability;

import mekanism.cloudworks.api.CloudworksEnabled;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;

public final class CloudworksCapabilities {
    @CapabilityInject(CloudworksEnabled.class)
    public static Capability<CloudworksEnabled> ENABLED = null;
    private static boolean registered;

    private CloudworksCapabilities() {
    }

    public static synchronized void register() {
        if (registered) return;
        CapabilityManager.INSTANCE.register(CloudworksEnabled.class, new Capability.IStorage<>() {
            @Override
            public NBTBase writeNBT(Capability<CloudworksEnabled> capability, CloudworksEnabled instance,
                                    EnumFacing side) {
                return instance == null ? new NBTTagCompound() : instance.serializeNBT();
            }

            @Override
            public void readNBT(Capability<CloudworksEnabled> capability, CloudworksEnabled instance,
                                EnumFacing side, NBTBase nbt) {
                instance.deserializeNBT(nbt instanceof NBTTagCompound ? (NBTTagCompound) nbt : new NBTTagCompound());
            }
        }, DefaultCloudworksEnabled::new);
        registered = true;
    }
}
