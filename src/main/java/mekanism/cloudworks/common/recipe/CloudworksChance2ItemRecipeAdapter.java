package mekanism.cloudworks.common.recipe;

import java.util.*;
import mekanism.cloudworks.api.*;
import mekanism.cloudworks.api.card.*;
import mekanism.cloudworks.api.environment.*;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.api.node.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.cloudworks.api.transfer.*;
import mekanism.cloudworks.api.work.*;
import mekanism.common.recipe.inputs.ItemStackInput;
import mekanism.common.recipe.machines.Chance2MachineRecipe;
import mekanism.common.recipe.machines.RecyclerRecipe;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

public final class CloudworksChance2ItemRecipeAdapter implements CloudworksMachineAdapter {
    private final Map<ItemStackInput, ? extends Chance2MachineRecipe<?>> recipes;
    private final boolean fallback;

    public CloudworksChance2ItemRecipeAdapter(Map<ItemStackInput, ? extends Chance2MachineRecipe<?>> recipes) {
        this(recipes, false);
    }

    public CloudworksChance2ItemRecipeAdapter(Map<ItemStackInput, ? extends Chance2MachineRecipe<?>> recipes,
            boolean fallback) {
        this.recipes = Objects.requireNonNull(recipes);
        this.fallback = fallback;
    }

    @Override
    public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
        List<CloudworksRecipeSpec> result = new ArrayList<>();
        for (Chance2MachineRecipe<?> recipe : recipes.values())
            result.add(new CloudworksRecipeBatch().item(recipe.getInput().ingredient)
                    .chanceOutput(recipe.getOutput().getMaxPrimaryOutput(), recipe.getOutput().primaryChance)
                    .specification("recycler_" + result.size(), 0));
        if (fallback) {
            ItemStack fallbackOutput = RecyclerRecipe.DEFAULT_OUTPUT.copy();
            if (fallbackOutput.getItem().getRegistryName() == null) fallbackOutput = new ItemStack(Items.PAPER);
            result.add(new CloudworksRecipeBatch().anyItem()
                .chanceOutput(fallbackOutput, RecyclerRecipe.DEFAULT_CHANCE)
                .specification("recycler_fallback", 0));
        }
        return Collections.unmodifiableList(result);
    }
}
