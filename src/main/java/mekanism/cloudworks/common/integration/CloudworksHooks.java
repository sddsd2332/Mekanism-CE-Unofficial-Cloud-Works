package mekanism.cloudworks.common.integration;

import mekanism.cloudworks.common.integration.ic2.CloudworksIc2Generators;
import mekanism.cloudworks.common.integration.ic2.CloudworksIc2Machines;
import mekanism.cloudworks.common.integration.moremachine.CloudworksMoreMachineIntegration;
import mekanism.cloudworks.common.integration.multiblockmachine.CloudworksMultiblockMachineIntegration;
import mekanism.cloudworks.common.integration.te.CloudworksTeAuxiliaries;
import mekanism.cloudworks.common.integration.te.CloudworksTeDevices;
import mekanism.cloudworks.common.integration.te.CloudworksTeDynamos;
import mekanism.cloudworks.common.integration.te.CloudworksTeMachines;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Optional;

/** Optional integrations follow Mekanism's loaded-mod gate and isolated entry points. */
public final class CloudworksHooks {
    public static final String MORE_MACHINE_MOD_ID = "mekceumoremachine";
    public static final String MULTIBLOCK_MACHINE_MOD_ID = "mekanismmultiblockmachine";
    public static final String CRAFTTWEAKER_MOD_ID = "crafttweaker";
    public static final String THERMAL_EXPANSION_MOD_ID = "thermalexpansion";
    public static final String IC2_MOD_ID = "ic2";

    private CloudworksHooks() {}

    public static void register() {
        register(Loader.isModLoaded(MORE_MACHINE_MOD_ID), Loader.isModLoaded(MULTIBLOCK_MACHINE_MOD_ID),
                Loader.isModLoaded("mekanismgenerators"), Loader.isModLoaded(THERMAL_EXPANSION_MOD_ID),
                Loader.isModLoaded(IC2_MOD_ID));
    }

    static void register(boolean moreMachineLoaded, boolean multiblockMachineLoaded, boolean generatorsLoaded,
            boolean thermalExpansionLoaded, boolean industrialCraftLoaded) {
        if (moreMachineLoaded && !multiblockMachineLoaded)
            mekanism.cloudworks.api.CloudworksAPI.registerMachineModule(
                    mekanism.cloudworks.common.integration.multiblockmachine.CloudworksThreadModule.type());
        if (moreMachineLoaded) registerMoreMachine();
        if (generatorsLoaded) registerGenerators();
        if (multiblockMachineLoaded) registerMultiblockMachine(generatorsLoaded);
        if (thermalExpansionLoaded) registerThermalExpansion();
        if (industrialCraftLoaded) registerIndustrialCraft2();
    }

    @Optional.Method(modid = MORE_MACHINE_MOD_ID)
    private static void registerMoreMachine() {
        CloudworksMoreMachineIntegration.register();
    }

    @Optional.Method(modid = "mekanismgenerators")
    private static void registerGenerators() {
        CloudworksGeneratorsIntegration.register();
    }

    @Optional.Method(modid = MULTIBLOCK_MACHINE_MOD_ID)
    private static void registerMultiblockMachine(boolean generatorsLoaded) {
        CloudworksMultiblockMachineIntegration.register(generatorsLoaded);
    }

    @Optional.Method(modid = THERMAL_EXPANSION_MOD_ID)
    private static void registerThermalExpansion() {
        CloudworksTeAuxiliaries.register();
        CloudworksTeMachines.register();
        CloudworksTeDynamos.register();
        CloudworksTeDevices.register();
    }

    @Optional.Method(modid = IC2_MOD_ID)
    private static void registerIndustrialCraft2() {
        CloudworksIc2Machines.register();
        CloudworksIc2Generators.register();
    }
}
