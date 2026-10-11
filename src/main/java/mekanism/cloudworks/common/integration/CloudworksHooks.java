package mekanism.cloudworks.common.integration;

import mekanism.cloudworks.common.integration.moremachine.CloudworksMoreMachineIntegration;
import mekanism.cloudworks.common.integration.multiblockmachine.CloudworksMultiblockMachineIntegration;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Optional;

/** Optional integrations follow Mekanism's loaded-mod gate and isolated entry points. */
public final class CloudworksHooks {
    public static final String MORE_MACHINE_MOD_ID = "mekceumoremachine";
    public static final String MULTIBLOCK_MACHINE_MOD_ID = "mekanismmultiblockmachine";
    public static final String CRAFTTWEAKER_MOD_ID = "crafttweaker";

    private CloudworksHooks() {}

    public static void register() {
        register(Loader.isModLoaded(MORE_MACHINE_MOD_ID), Loader.isModLoaded(MULTIBLOCK_MACHINE_MOD_ID),
                Loader.isModLoaded("mekanismgenerators"));
    }

    static void register(boolean moreMachineLoaded, boolean multiblockMachineLoaded, boolean generatorsLoaded) {
        if (moreMachineLoaded) registerMoreMachine();
        if (multiblockMachineLoaded) registerMultiblockMachine(generatorsLoaded);
    }

    @Optional.Method(modid = MORE_MACHINE_MOD_ID)
    private static void registerMoreMachine() {
        CloudworksMoreMachineIntegration.register();
    }

    @Optional.Method(modid = MULTIBLOCK_MACHINE_MOD_ID)
    private static void registerMultiblockMachine(boolean generatorsLoaded) {
        CloudworksMultiblockMachineIntegration.register(generatorsLoaded);
    }
}
