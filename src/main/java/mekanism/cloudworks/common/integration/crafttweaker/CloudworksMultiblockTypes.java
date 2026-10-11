package mekanism.cloudworks.common.integration.crafttweaker;

import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import mekanism.cloudworks.common.integration.CloudworksMultiblocks;
import net.minecraft.item.ItemStack;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenClass("mods.cloudworks.Multiblocks")
@ZenRegister
public final class CloudworksMultiblockTypes {
    private CloudworksMultiblockTypes() {}

    @ZenMethod
    public static String[] getTypes() { return CloudworksMultiblocks.types(); }

    @ZenMethod
    public static void evaporation(IItemStack item, int parallel, int ticks, int inputCapacity, int outputCapacity) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks evaporation tower " + machine, () ->
                CloudworksMultiblocks.evaporation(machine, parallel, ticks, inputCapacity, outputCapacity));
    }

    @ZenMethod
    public static void sps(IItemStack item, int ticks, double energyCapacity) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks SPS " + machine, () ->
                CloudworksMultiblocks.sps(machine, ticks, energyCapacity));
    }

    @ZenMethod
    public static void boiler(IItemStack item, int waterPerOperation, int ticks, int fluidCapacity, int gasCapacity) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks boiler " + machine, () ->
                CloudworksMultiblocks.boiler(machine, waterPerOperation, ticks, fluidCapacity, gasCapacity));
    }

    @ZenMethod
    public static void fission(IItemStack item, boolean sodiumCooling, int ticks, int fuelCapacity, int coolantCapacity) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks fission reactor " + machine, () ->
                CloudworksMultiblocks.fission(machine, sodiumCooling, ticks, fuelCapacity, coolantCapacity));
    }

    @ZenMethod
    public static void turbine(IItemStack item, int steamPerTick, int blades, int coils, int condensers,
            int steamCapacity, int waterCapacity, double energyCapacity) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks industrial turbine " + machine, () ->
                CloudworksMultiblocks.turbine(machine, steamPerTick, blades, coils, condensers,
                        steamCapacity, waterCapacity, energyCapacity));
    }

    @ZenMethod
    public static void fusion(IItemStack item, int injectionRate, boolean activelyCooled, int gasCapacity,
            int fluidCapacity, double energyCapacity) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks fusion reactor " + machine, () ->
                CloudworksMultiblocks.fusion(machine, injectionRate, activelyCooled, gasCapacity, fluidCapacity, energyCapacity));
    }
}
