package mekanism.cloudworks.common.integration.ic2;

import java.util.*;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.cloudworks.common.integration.CloudworksUpgradeCurves;

/** IC2 rounds cycle length and demand independently; operations in one cycle share its power. */
public final class CloudworksIc2Overclocker implements ICustomMachineModule<CloudworksIc2Overclocker> {
    private final double euToJ;
    public CloudworksIc2Overclocker(double factor) { euToJ = factor; }
    @Override public String installationGroup() { return "ic2:upgrade_slots"; }
    @Override public int installationSlotsPerMachine() { return 4; }
    @Override public int installationItemsPerSlot() { return 64; }
    @Override public AxisContribution onUpgradeAxis(IMachineModule<CloudworksIc2Overclocker> module, UpgradeAxis axis) {
        return AxisContribution.NEUTRAL; // The frozen profile/recipes already contain the native cycle.
    }
    @Override public RecipeContribution onRecipes(IMachineModule<CloudworksIc2Overclocker> module, RecipeFreezeContext context) {
        int installed = module.getEffectiveInstalledCount();
        CloudworksUpgradeCurves.Snapshot curves = CloudworksUpgradeCurves.snapshot();
        double time = Math.pow(curves.ic2OverclockerTime(), installed);
        MachineExecutionProfile base = context.machine().profile();
        double demand = Math.min(Integer.MAX_VALUE, Math.round(base.energyPerTick() / euToJ
                * Math.pow(curves.ic2OverclockerEnergy(), installed))) * euToJ;
        List<CloudworksRecipeSpec> recipes = new ArrayList<>();
        for (CloudworksRecipeSpec recipe : context.baseRecipes()) {
            int ticks = recipe.duration() > 0 ? recipe.duration() : base.totalTicks();
            int operations = operations(ticks, time);
            recipes.add(recipe.toBuilder(recipe.id()).duration(length(ticks, time, operations)).operationsPerCycle(operations).build());
        }
        int length = length(base.totalTicks(), time, operations(base.totalTicks(), time));
        double capacity = Math.min(Integer.MAX_VALUE * euToJ,
                Math.max(0, base.energyCapacity() - base.totalTicks() * base.energyPerTick()) + length * demand);
        MachineExecutionProfile profile = new MachineExecutionProfile(base.threads(), demand, capacity, length,
                base.upgradePolicy(), base.lineUpgradePolicy());
        return RecipeContribution.of(RecipeContribution.Installation.PER_MACHINE, 1,
                RecipeContribution.RecipeMode.REPLACE, recipes, new MachineSlotLayout()).withProfile(profile);
    }
    static int operations(int ticks, double time) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1D, Math.ceil(1D / (ticks * time))));
    }
    static int length(int ticks, double time, int operations) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(ticks * time * operations)));
    }
}
