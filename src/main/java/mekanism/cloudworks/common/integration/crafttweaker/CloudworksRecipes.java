package mekanism.cloudworks.common.integration.crafttweaker;

import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import mekanism.cloudworks.common.integration.CloudworksScriptMachines;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;
import mekanism.cloudworks.api.recipe.CloudworksResourceMatcher;
import net.minecraft.item.ItemStack;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenClass("mods.cloudworks.Recipes")
@ZenRegister
public final class CloudworksRecipes {
    private final String id;
    private final int ticks;
    private final CloudworksRecipeSpec.Builder recipe;
    private boolean submitted;

    private CloudworksRecipes(String id, int ticks) {
        if (id == null || id.trim().isEmpty() || ticks < 1) throw new IllegalArgumentException("Invalid recipe id or duration");
        this.id = id;
        this.ticks = ticks;
        this.recipe = CloudworksRecipeSpec.builder(id).duration(ticks);
    }

    @ZenMethod
    public static CloudworksRecipes create(String id, int ticks) { return new CloudworksRecipes(id, ticks); }

    private void mutable() {
        if (submitted) throw new IllegalStateException("Recipe builder has already been submitted");
    }

    @ZenMethod
    public CloudworksRecipes input(CloudworksResources resource) { return input(resource, ""); }

    @ZenMethod
    public CloudworksRecipes input(CloudworksResources resource, String port) {
        mutable();
        recipe.matcher(CloudworksResourceMatcher.alternatives(resource.alternatives), resource.amount, port);
        return this;
    }

    @ZenMethod
    public CloudworksRecipes output(CloudworksResources resource) { return output(resource, ""); }

    @ZenMethod
    public CloudworksRecipes output(CloudworksResources resource, String port) {
        mutable();
        recipe.output(resource.single(), resource.amount, port);
        return this;
    }

    @ZenMethod
    public CloudworksRecipes unscaledOutput(CloudworksResources resource) { return unscaledOutput(resource, ""); }

    @ZenMethod
    public CloudworksRecipes unscaledOutput(CloudworksResources resource, String port) {
        mutable();
        recipe.unscaledOutput(resource.single(), resource.amount, port);
        return this;
    }

    @ZenMethod
    public CloudworksRecipes unscaledOutputs() {
        mutable();
        recipe.unscaledOutputs();
        return this;
    }

    @ZenMethod
    public CloudworksRecipes chanceOutput(CloudworksResources resource, double chance) {
        mutable();
        recipe.chanceOutput(resource.single(), resource.amount, chance, "");
        return this;
    }

    @ZenMethod
    public CloudworksRecipes template(CloudworksResources resource, String port) {
        mutable();
        recipe.configuration(resource.single(), port);
        return this;
    }

    @ZenMethod
    public CloudworksRecipes energy(double additionalEnergyPerTick) {
        mutable();
        recipe.extraEnergyPerTick(additionalEnergyPerTick);
        return this;
    }

    @ZenMethod
    public CloudworksRecipes sunlight() {
        mutable();
        recipe.requireSunlight();
        return this;
    }

    @ZenMethod
    public CloudworksRecipes operationsPerCycle(int count) { mutable(); recipe.operationsPerCycle(count); return this; }

    @ZenMethod
    public CloudworksRecipes powerDriven(double joulesPerInput, int inputsPerOutput) {
        mutable(); recipe.powerDriven(joulesPerInput, inputsPerOutput); return this;
    }

    @ZenMethod
    public CloudworksRecipes prepaidFuel(CloudworksResources canonicalFuel, int units, double joulesPerUnit) {
        mutable(); recipe.prepaidFuel(canonicalFuel.single(), units).energyPerOperation(joulesPerUnit); return this;
    }

    @ZenMethod
    public CloudworksRecipes secondaryFuelInputs(boolean gas, boolean fluid) {
        mutable(); if (gas) recipe.secondaryGasInputs(); if (fluid) recipe.secondaryFluidInputs(); return this;
    }

    @ZenMethod
    public void register(IItemStack item) {
        mutable();
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksRecipeSpec declaration = recipe.build();
        submitted = true;
        CloudworksScriptAction.submit("Cloudworks recipe " + id + " on " + machine,
                () -> CloudworksScriptMachines.recipe(machine, declaration));
    }

    @ZenMethod
    public static void remove(IItemStack item, String id) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks remove recipe " + id + " on " + machine,
                () -> CloudworksScriptMachines.removeRecipe(machine, id));
    }

    @ZenMethod
    public static void removeAll(IItemStack item) {
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksScriptAction.submit("Cloudworks remove all resource recipes on " + machine,
                () -> CloudworksScriptMachines.removeAllRecipes(machine));
    }
}
