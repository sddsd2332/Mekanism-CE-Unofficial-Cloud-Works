package mekanism.cloudworks.common.integration.crafttweaker;

import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import mekanism.cloudworks.common.integration.CloudworksScriptMachines;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;
import mekanism.cloudworks.api.recipe.CloudworksResourceMatcher;
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
        if (!Double.isFinite(joulesPerOperation) || joulesPerOperation <= 0D)
            throw new IllegalArgumentException("Fuel generation requires positive finite energy");
        CloudworksRecipeSpec.Builder builder = CloudworksRecipeSpec.builder(id).duration(1)
                .energyPerOperation(joulesPerOperation);
        for (CloudworksResources input : inputs)
            builder.matcher(CloudworksResourceMatcher.alternatives(input.alternatives), input.amount, "");
        for (CloudworksResources output : outputs) builder.output(output.single(), output.amount, "");
        CloudworksRecipeSpec recipe = builder.build();
        CloudworksScriptAction.submit("Cloudworks fuel " + id + " on " + machine, () ->
                CloudworksScriptMachines.recipe(machine, recipe));
    }
}
