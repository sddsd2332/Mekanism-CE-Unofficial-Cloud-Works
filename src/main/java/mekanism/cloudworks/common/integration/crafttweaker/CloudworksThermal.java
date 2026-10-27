package mekanism.cloudworks.common.integration.crafttweaker;

import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.machine.CloudworksMachineDefinition;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.cloudworks.api.machine.CloudworksThermalContracts;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineIdentityPolicy;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.api.qio.resource.QIOResourceCodecs;
import net.minecraft.item.ItemStack;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;
import java.util.Collections;
import mekanism.cloudworks.api.machine.CloudworksMachineInfo;
import mekanism.cloudworks.api.module.MachineUpgradePolicy;

@ZenClass("mods.cloudworks.Thermal")
@ZenRegister
public final class CloudworksThermal {
    private CloudworksThermal() {}

    @ZenMethod
    public static void capacitor(IItemStack item, double capacity, double inverseConduction, double inverseInsulation) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks thermal capacitor on " + machine, () -> {
            CloudworksThermalPlan plan = require(machine);
            CloudworksAPI.registerThermalPlan(machine, plan.withCapacitor(capacity, inverseConduction, inverseInsulation));
        }, true);
    }

    @ZenMethod
    public static void solarPanels(IItemStack item, int panels) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks evaporation solar panels on " + machine, () -> {
            CloudworksThermalPlan plan = require(machine);
            if (plan.kind != CloudworksThermalPlan.Kind.EVAPORATION)
                throw new IllegalArgumentException("Solar panels require an evaporation tower");
            CloudworksAPI.registerThermalPlan(machine,
                    plan.withEnvironment(plan.lavaEnvironmentHeat, plan.netherEnvironmentHeat, panels));
        }, true);
    }

    @ZenMethod
    public static void resistive(IItemStack item, double energyPerTick, double energyCapacity) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks resistive heater " + machine, () -> {
            CloudworksAPI.registerMachine(new CloudworksMachineDefinition(machine,
                    new MachineExecutionProfile(1, energyPerTick, energyCapacity, 1,
                            MachineUpgradePolicy.NONE),
                    new MachineSlotLayout(), MachineIdentityPolicy.exactNbt(),
                    Collections.emptyList(), null, CloudworksThermalContracts.resistive()));
        });
    }

    @ZenMethod
    public static void fuelwood(IItemStack item, int itemCapacity) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks fuelwood heater " + machine, () -> {
            CloudworksAPI.registerMachine(new CloudworksMachineDefinition(machine,
                    new MachineExecutionProfile(1, 0, 0, 1, MachineUpgradePolicy.NONE),
                    new MachineSlotLayout()
                            .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, itemCapacity)
                            .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, itemCapacity),
                    MachineIdentityPolicy.exactNbt(), Collections.emptyList(), null,
                    CloudworksThermalContracts.fuelwood()));
        });
    }

    private static CloudworksThermalPlan require(ItemStack machine) {
        CloudworksMachineInfo info = CloudworksAPI.machineInfo(machine);
        CloudworksThermalPlan plan = info.thermal();
        if (!info.registered() || plan == null || !info.aliasTarget().isEmpty())
            throw new IllegalArgumentException("A registered non-alias thermal machine is required");
        return plan;
    }
}
