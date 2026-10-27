package mekanism.cloudworks.api.module;

import java.util.Map;
import java.util.UUID;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;

/** Immutable handle passed to one module callback for one target machine. */
public interface IMachineModule<MODULE extends ICustomMachineModule<MODULE>> {
    MachineModuleType<MODULE> getData();
    MODULE getCustomInstance();
    int getInstalledCount();
    int getEffectiveInstalledCount();
    MachineModuleSource getSource();
    UUID getSourceNodeId();
    UUID getMachineNodeId();
    /** Null only for registration-time prototypes without a placed line. */
    UUID getLineId();
    String getBranchId();
    ItemStack getMachineIdentity();
    long getMachineCount();
    String getRecipeId();
    long getInstalledCount(ResourceLocation moduleId);
    Map<ResourceLocation, Long> getAllInstalledCounts();
    CloudworksGenerationContext getEnvironment();
    MachineContextSnapshot getMachineContext();
}
