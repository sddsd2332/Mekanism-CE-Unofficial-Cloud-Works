package mekanism.cloudworks.common.integration;

import mekanism.cloudworks.api.*;
import mekanism.cloudworks.api.card.*;
import mekanism.cloudworks.api.environment.*;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.api.node.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.cloudworks.api.transfer.*;
import mekanism.cloudworks.api.work.*;
import mekanism.cloudworks.common.registries.CloudworksItems;
import net.minecraft.item.ItemStack;

/** Built-in content goes through the same item binding and upgrade declaration API as addons. */
public final class CloudworksEnvironmentModules {
    private CloudworksEnvironmentModules() {}
    public static void register() {
        CloudworksAPI.registerMachineModule(MachineModuleType.lavaHeat().withItem(new ItemStack(CloudworksItems.LavaHeatSimulationCard)));
        CloudworksAPI.registerMachineModule(MachineModuleType.height().withItem(new ItemStack(CloudworksItems.HeightSimulationCard)));
    }
}
