package mekanism.cloudworks.common.integration.crafttweaker;

import crafttweaker.CraftTweakerAPI;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.machine.CloudworksMachineDefinition;
import mekanism.cloudworks.api.machine.MachineIdentityPolicy;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;
import java.util.Objects;

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
                () -> CloudworksAPI.registerMachineAlias(from, to), true);
    }

    @ZenMethod
    public static void register(IItemStack item, int parallel, double energyPerTick, double energyCapacity,
            int ticks, CloudworksSlots slots) {
        ItemStack machine = stack(item);
        MachineExecutionProfile profile = profile(parallel, energyPerTick, energyCapacity, ticks);
        MachineSlotLayout layout = Objects.requireNonNull(slots, "slots").snapshot();
        CloudworksScriptAction.submit("Cloudworks register machine " + machine, () -> {
            if (CloudworksAPI.machineInfo(machine).registered())
                throw new IllegalArgumentException("Machine already registered");
            CloudworksAPI.registerMachine(CloudworksMachineDefinition.deferred(machine, profile, layout,
                    MachineIdentityPolicy.exactNbt()));
        });
    }

    @ZenMethod
    public static void setProfile(IItemStack item, int parallel, double energyPerTick, double energyCapacity, int ticks) {
        ItemStack machine = stack(item);
        MachineExecutionProfile profile = profile(parallel, energyPerTick, energyCapacity, ticks);
        CloudworksScriptAction.submit("Cloudworks machine profile " + machine,
                () -> CloudworksAPI.updateMachineProfile(machine, profile));
    }

    @ZenMethod
    public static void registerFrom(IItemStack item, IItemStack target, int parallel,
            double energyPerTick, double energyCapacity, int ticks) {
        ItemStack machine = stack(item), template = stack(target);
        MachineExecutionProfile profile = profile(parallel, energyPerTick, energyCapacity, ticks);
        CloudworksScriptAction.submit("Cloudworks template " + machine + " from " + template,
                () -> CloudworksAPI.registerMachineFrom(machine, template, profile), true);
    }

    /** Per-physical-machine line allowance; zero refuses that axis. */
    @ZenMethod
    public static void setLineUpgradePolicy(IItemStack item, int speedUnits, int energyUnits) {
        ItemStack machine = stack(item);
        if (speedUnits < 0 || energyUnits < 0) throw new IllegalArgumentException("Negative line upgrade allowance");
        var policy = mekanism.cloudworks.api.machine.MachineLineUpgradePolicy.of(
                speedUnits > 0, energyUnits > 0, speedUnits, energyUnits);
        CloudworksScriptAction.submit("Cloudworks line upgrade policy " + machine, () -> {
            MachineExecutionProfile current = CloudworksAPI.machineInfo(machine).profile();
            if (current == null) throw new IllegalArgumentException("Machine is not registered");
            CloudworksAPI.updateMachineProfile(machine, current.withLineUpgradePolicy(policy));
        });
    }

    private static MachineExecutionProfile profile(int parallel, double energy, double capacity, int ticks) {
        if (parallel < 1 || ticks < 1) throw new IllegalArgumentException("Parallelism and ticks must be positive");
        return new MachineExecutionProfile(parallel, energy, capacity, ticks);
    }

    @ZenMethod
    public static boolean isRegistered(IItemStack item) { return CloudworksAPI.machineInfo(stack(item)).registered(); }

    @ZenMethod
    public static IItemStack getAliasTarget(IItemStack item) {
        ItemStack target = CloudworksAPI.machineInfo(stack(item)).aliasTarget();
        return target.isEmpty() ? null : CraftTweakerMC.getIItemStack(target);
    }

    @ZenMethod
    public static String[] getPorts(IItemStack item) {
        MachineSlotLayout layout = CloudworksAPI.machineInfo(stack(item)).slots();
        if (layout == null) return new String[0];
        return layout.slots().stream().map(slot -> slot.id() + ":" + slot.role() + ":" + slot.codecId()
                + ":" + slot.capacity()).toArray(String[]::new);
    }

    @ZenMethod
    public static String getProfile(IItemStack item) {
        MachineExecutionProfile profile = CloudworksAPI.machineInfo(stack(item)).profile();
        return profile == null ? "" : "parallel=" + profile.threads() + ", energyPerTick=" + profile.energyPerTick()
                + ", energyCapacity=" + profile.energyCapacity() + ", ticks=" + profile.totalTicks();
    }

    @ZenMethod
    public static int getParallel(IItemStack item) {
        MachineExecutionProfile profile = CloudworksAPI.machineInfo(stack(item)).profile();
        return profile == null ? 0 : profile.threads();
    }

    @ZenMethod
    public static double getEnergyPerTick(IItemStack item) {
        MachineExecutionProfile profile = CloudworksAPI.machineInfo(stack(item)).profile();
        return profile == null ? 0 : profile.energyPerTick();
    }

    @ZenMethod
    public static double getEnergyCapacity(IItemStack item) {
        MachineExecutionProfile profile = CloudworksAPI.machineInfo(stack(item)).profile();
        return profile == null ? 0 : profile.energyCapacity();
    }

    @ZenMethod
    public static int getTicks(IItemStack item) {
        MachineExecutionProfile profile = CloudworksAPI.machineInfo(stack(item)).profile();
        return profile == null ? 0 : profile.totalTicks();
    }

    @ZenMethod
    public static void dump() {
        CloudworksScriptAction.submit("Cloudworks registered machine diagnostics", () ->
                CloudworksAPI.machineInfos().forEach(machine -> CraftTweakerAPI.logInfo(
                        machine.machine() + " | " + describe(machine.profile())
                                + " | adapters=" + machine.adapterCount()
                                + " | generator=" + machine.hasEnergyGenerator())), true);
    }

    private static String describe(MachineExecutionProfile profile) {
        return profile == null ? "" : "parallel=" + profile.threads() + ", energyPerTick=" + profile.energyPerTick()
                + ", energyCapacity=" + profile.energyCapacity() + ", ticks=" + profile.totalTicks();
    }
}
