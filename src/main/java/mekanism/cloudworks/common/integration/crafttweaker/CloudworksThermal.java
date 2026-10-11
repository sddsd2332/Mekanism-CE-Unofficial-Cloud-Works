package mekanism.cloudworks.common.integration.crafttweaker;

import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import mekanism.cloudworks.api.CloudworksThermalPlan;
import mekanism.cloudworks.common.integration.CloudworksScriptMachines;
import mekanism.cloudworks.common.integration.CloudworksThermalContracts;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.registry.MachineExecutionProfile;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import mekanism.api.qio.resource.QIOResourceCodecs;
import net.minecraft.item.ItemStack;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenClass("mods.cloudworks.Thermal")
@ZenRegister
public final class CloudworksThermal {
    private CloudworksThermal() {}

    @ZenMethod
    public static void capacitor(IItemStack item, double capacity, double inverseConduction, double inverseInsulation) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks thermal capacitor on " + machine, () -> {
            CloudworksThermalPlan plan = require(machine);
            CloudworksRegistries.registerThermal(machine, plan.withCapacitor(capacity, inverseConduction, inverseInsulation));
        }, true);
    }

    @ZenMethod
    public static void solarPanels(IItemStack item, int panels) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks evaporation solar panels on " + machine, () -> {
            CloudworksThermalPlan plan = require(machine);
            if (plan.kind != CloudworksThermalPlan.Kind.EVAPORATION)
                throw new IllegalArgumentException("Solar panels require an evaporation tower");
            CloudworksRegistries.registerThermal(machine,
                    plan.withEnvironment(plan.lavaEnvironmentHeat, plan.netherEnvironmentHeat, panels));
        }, true);
    }

    @ZenMethod
    public static void resistive(IItemStack item, double energyPerTick, double energyCapacity) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks resistive heater " + machine, () -> {
            CloudworksScriptMachines.register(machine, new MachineExecutionProfile(1, energyPerTick, energyCapacity, 1),
                    new MachineSlotLayout());
            CloudworksRegistries.registerThermal(machine, CloudworksThermalContracts.resistive());
        });
    }

    @ZenMethod
    public static void fuelwood(IItemStack item, int itemCapacity) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks fuelwood heater " + machine, () -> {
            CloudworksScriptMachines.register(machine, new MachineExecutionProfile(1, 0, 0, 1),
                    new MachineSlotLayout()
                            .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, itemCapacity)
                            .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, itemCapacity));
            CloudworksRegistries.registerThermal(machine, CloudworksThermalContracts.fuelwood());
        });
    }

    private static CloudworksThermalPlan require(ItemStack machine) {
        CloudworksThermalPlan plan = CloudworksRegistries.thermal(CloudworksRegistries.identityKey(machine));
        if (plan == null || !CloudworksRegistries.aliasTarget(machine).isEmpty())
            throw new IllegalArgumentException("A registered non-alias thermal machine is required");
        return plan;
    }
}
