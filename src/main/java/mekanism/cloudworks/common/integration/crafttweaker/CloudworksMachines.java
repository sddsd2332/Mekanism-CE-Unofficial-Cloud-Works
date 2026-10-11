package mekanism.cloudworks.common.integration.crafttweaker;

import crafttweaker.CraftTweakerAPI;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import mekanism.cloudworks.common.integration.CloudworksScriptMachines;
import mekanism.cloudworks.common.registry.CloudworksMachineAdapters;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.registry.MachineExecutionProfile;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenClass("mods.cloudworks.Machines")
@ZenRegister
public final class CloudworksMachines {
    private CloudworksMachines() {}

    static ItemStack stack(IItemStack source) {
        if (source == null) throw new IllegalArgumentException("Machine item cannot be null");
        ItemStack stack = CraftTweakerMC.getItemStack(source);
        if (stack == null || stack.isEmpty() || stack.getMetadata() == OreDictionary.WILDCARD_VALUE)
            throw new IllegalArgumentException("Machine identity requires a concrete item");
        stack = stack.copy();
        stack.setCount(1);
        return stack;
    }

    @ZenMethod
    public static void registerAlias(IItemStack alias, IItemStack target) {
        ItemStack from = stack(alias), to = stack(target);
        CloudworksScriptAction.submit("Cloudworks alias " + from + " -> " + to,
                () -> CloudworksRegistries.registerAlias(from, to), true);
    }

    @ZenMethod
    public static void register(IItemStack item, int parallel, double energyPerTick, double energyCapacity,
            int ticks, CloudworksSlots slots) {
        ItemStack machine = stack(item);
        MachineExecutionProfile profile = profile(parallel, energyPerTick, energyCapacity, ticks);
        MachineSlotLayout layout = java.util.Objects.requireNonNull(slots, "slots").snapshot();
        CloudworksScriptAction.submit("Cloudworks register machine " + machine,
                () -> CloudworksScriptMachines.register(machine, profile, layout));
    }

    @ZenMethod
    public static void setProfile(IItemStack item, int parallel, double energyPerTick, double energyCapacity, int ticks) {
        ItemStack machine = stack(item);
        MachineExecutionProfile profile = profile(parallel, energyPerTick, energyCapacity, ticks);
        CloudworksScriptAction.submit("Cloudworks machine profile " + machine,
                () -> CloudworksScriptMachines.profile(machine, profile));
    }

    @ZenMethod
    public static void registerFrom(IItemStack item, IItemStack target, int parallel,
            double energyPerTick, double energyCapacity, int ticks) {
        ItemStack machine = stack(item), template = stack(target);
        MachineExecutionProfile profile = profile(parallel, energyPerTick, energyCapacity, ticks);
        CloudworksScriptAction.submit("Cloudworks template " + machine + " from " + template,
                () -> CloudworksScriptMachines.registerFrom(machine, template, profile));
    }

    private static MachineExecutionProfile profile(int parallel, double energy, double capacity, int ticks) {
        if (parallel < 1 || ticks < 1) throw new IllegalArgumentException("Parallelism and ticks must be positive");
        return new MachineExecutionProfile(parallel, energy, capacity, ticks);
    }

    @ZenMethod
    public static boolean isRegistered(IItemStack item) { return CloudworksRegistries.isRegistered(stack(item)); }

    @ZenMethod
    public static IItemStack getAliasTarget(IItemStack item) {
        ItemStack target = CloudworksRegistries.aliasTarget(stack(item));
        return target.isEmpty() ? null : CraftTweakerMC.getIItemStack(target);
    }

    @ZenMethod
    public static String[] getPorts(IItemStack item) {
        MachineSlotLayout layout = CloudworksRegistries.slots(stack(item));
        if (layout == null) return new String[0];
        return layout.slots().stream().map(slot -> slot.id() + ":" + slot.role() + ":" + slot.codecId()
                + ":" + slot.capacity()).toArray(String[]::new);
    }

    @ZenMethod
    public static String getProfile(IItemStack item) {
        MachineExecutionProfile profile = CloudworksRegistries.profile(stack(item));
        return profile == null ? "" : "parallel=" + profile.threads() + ", energyPerTick=" + profile.energyPerTick()
                + ", energyCapacity=" + profile.energyCapacity() + ", ticks=" + profile.totalTicks();
    }

    @ZenMethod
    public static int getParallel(IItemStack item) {
        MachineExecutionProfile profile = CloudworksRegistries.profile(stack(item));
        return profile == null ? 0 : profile.threads();
    }

    @ZenMethod
    public static double getEnergyPerTick(IItemStack item) {
        MachineExecutionProfile profile = CloudworksRegistries.profile(stack(item));
        return profile == null ? 0 : profile.energyPerTick();
    }

    @ZenMethod
    public static double getEnergyCapacity(IItemStack item) {
        MachineExecutionProfile profile = CloudworksRegistries.profile(stack(item));
        return profile == null ? 0 : profile.energyCapacity();
    }

    @ZenMethod
    public static int getTicks(IItemStack item) {
        MachineExecutionProfile profile = CloudworksRegistries.profile(stack(item));
        return profile == null ? 0 : profile.totalTicks();
    }

    @ZenMethod
    public static void dump() {
        CloudworksScriptAction.submit("Cloudworks registered machine diagnostics", () ->
                CloudworksRegistries.profileSnapshot().forEach((key, profile) ->
                        CraftTweakerAPI.logInfo(key.displayStack() + " | " + getProfile(CraftTweakerMC.getIItemStack(key.displayStack()))
                                + " | adapters=" + CloudworksMachineAdapters.getAll(key).size()
                                + " | generator=" + (CloudworksRegistries.energyGenerator(key) != null))), true);
    }
}
