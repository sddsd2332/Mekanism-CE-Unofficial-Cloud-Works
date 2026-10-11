package mekanism.cloudworks.common.integration.crafttweaker;

import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import mekanism.cloudworks.common.integration.CloudworksScriptMachines;
import mekanism.cloudworks.common.recipe.CloudworksContractRecipeAdapter;
import mekanism.cloudworks.common.recipe.CloudworksRecipeBatch;
import net.minecraft.item.ItemStack;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenClass("mods.cloudworks.Generators")
@ZenRegister
public final class CloudworksGenerators {
    private CloudworksGenerators() {}

    @ZenMethod
    public static void constant(IItemStack item, double joulesPerTick) {
        ItemStack machine = CloudworksMachines.stack(item);
        if (!Double.isFinite(joulesPerTick) || joulesPerTick < 0)
            throw new IllegalArgumentException("Invalid constant generation");
        CloudworksScriptAction.submit("Cloudworks constant generation on " + machine, () ->
                CloudworksScriptMachines.constantGenerator(machine, joulesPerTick));
    }

    @ZenMethod
    public static void fuel(IItemStack item, String id, CloudworksResources[] inputs,
            CloudworksResources[] outputs, double joulesPerOperation) {
        ItemStack machine = CloudworksMachines.stack(item);
        if (inputs == null || inputs.length == 0 || outputs == null)
            throw new IllegalArgumentException("Fuel generation requires inputs and an output array");
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch().energy(joulesPerOperation);
        for (CloudworksResources input : inputs) batch.resources(input.alternatives, input.amount, "");
        for (CloudworksResources output : outputs) batch.output(output.single(), output.amount, "");
        CloudworksContractRecipeAdapter.Contract recipe = new CloudworksContractRecipeAdapter.Contract(id, batch, 1);
        CloudworksScriptAction.submit("Cloudworks fuel " + id + " on " + machine, () ->
                CloudworksScriptMachines.recipe(machine, recipe));
    }
}
