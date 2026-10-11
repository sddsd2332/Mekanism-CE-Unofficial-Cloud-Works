package mekanism.cloudworks.common.integration.crafttweaker;

import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import mekanism.cloudworks.common.integration.CloudworksScriptMachines;
import mekanism.cloudworks.common.recipe.CloudworksContractRecipeAdapter;
import mekanism.cloudworks.common.recipe.CloudworksRecipeBatch;
import net.minecraft.item.ItemStack;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenClass("mods.cloudworks.Recipes")
@ZenRegister
public final class CloudworksRecipes {
    private final String id;
    private final int ticks;
    private final CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
    private boolean submitted;

    private CloudworksRecipes(String id, int ticks) {
        if (id == null || id.trim().isEmpty() || ticks < 1) throw new IllegalArgumentException("Invalid recipe id or duration");
        this.id = id;
        this.ticks = ticks;
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
        batch.resources(resource.alternatives, resource.amount, port);
        return this;
    }

    @ZenMethod
    public CloudworksRecipes output(CloudworksResources resource) { return output(resource, ""); }

    @ZenMethod
    public CloudworksRecipes output(CloudworksResources resource, String port) {
        mutable();
        batch.output(resource.single(), resource.amount, port);
        return this;
    }

    @ZenMethod
    public CloudworksRecipes chanceOutput(CloudworksResources resource, double chance) {
        mutable();
        batch.chanceOutput(resource.single(), resource.amount, chance);
        return this;
    }

    @ZenMethod
    public CloudworksRecipes template(CloudworksResources resource, String port) {
        mutable();
        batch.template(resource.single(), port);
        return this;
    }

    @ZenMethod
    public CloudworksRecipes energy(double additionalEnergyPerTick) {
        mutable();
        batch.extraEnergy(additionalEnergyPerTick);
        return this;
    }

    @ZenMethod
    public CloudworksRecipes sunlight() {
        mutable();
        batch.requireSunlight();
        return this;
    }

    @ZenMethod
    public void register(IItemStack item) {
        mutable();
        ItemStack machine = CloudworksMachines.stack(item);
        CloudworksContractRecipeAdapter.Contract recipe = new CloudworksContractRecipeAdapter.Contract(id, batch, ticks);
        submitted = true;
        CloudworksScriptAction.submit("Cloudworks recipe " + id + " on " + machine,
                () -> CloudworksScriptMachines.recipe(machine, recipe));
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
