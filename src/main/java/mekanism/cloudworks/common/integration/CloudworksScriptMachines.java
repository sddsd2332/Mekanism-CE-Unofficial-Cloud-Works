package mekanism.cloudworks.common.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.machine.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.environment.CloudworksGenerationPlan;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.CloudworksMachineDefinition;
import mekanism.cloudworks.api.machine.CloudworksMachineInfo;
import mekanism.cloudworks.api.recipe.CloudworksRecipeMode;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineIdentityPolicy;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import net.minecraft.item.ItemStack;
import java.util.Collections;

/**
 * CraftTweaker declaration assembler; all registry access goes through CloudworksAPI.
 */
public final class CloudworksScriptMachines {
    private static final List<RecipeGroup> RECIPES = new ArrayList<>();

    private CloudworksScriptMachines() {
    }

    public static void register(ItemStack machine, MachineExecutionProfile profile, MachineSlotLayout layout) {
        CloudworksAPI.registerMachine(CloudworksMachineDefinition.deferred(machine, profile, layout,
                MachineIdentityPolicy.exactNbt()));
    }

    public static void profile(ItemStack machine, MachineExecutionProfile profile) {
        CloudworksMachineInfo info = requireRegisteredNonAlias(machine, "Profile requires a registered non-alias machine");
        CloudworksAPI.updateMachineProfile(info.machine(), profile);
    }

    public static void registerFrom(ItemStack machine, ItemStack target, MachineExecutionProfile profile) {
        requireRegisteredNonAlias(target, "Template requires a registered non-alias machine");
        if (CloudworksAPI.machineInfo(machine).registered())
            throw new IllegalArgumentException("Machine already registered");
        CloudworksAPI.registerMachineFrom(machine, target, profile);
    }

    public static void recipe(ItemStack machine, CloudworksRecipeSpec recipe) {
        CloudworksMachineInfo info = requireRegisteredNonAlias(machine, "Recipes require a registered non-alias machine");
        CloudworksThermalPlan thermal = info.thermal();
        if (thermal != null && thermal.kind != CloudworksThermalPlan.Kind.EVAPORATION)
            throw new IllegalArgumentException("This thermal machine owns a fixed resource contract; use an independent script machine");
        if (thermal != null && (recipe.extraEnergyPerTick() != 0 || recipe.energyPerOperation() != 0))
            throw new IllegalArgumentException("Evaporation recipes are driven by heat, not electric processing or fuel generation");
        RecipeGroup group = recipes(machine);
        if (group.entries.containsKey(recipe.id()))
            throw new IllegalArgumentException("Duplicate cloud recipe: " + recipe.id());
        if (recipe.energyPerOperation() > 0 && (recipe.duration() != 1 || recipe.extraEnergyPerTick() != 0))
            throw new IllegalArgumentException("Fuel contracts must have one tick and no processing energy");
        CloudworksScriptRecipeAdapter adapter = new CloudworksScriptRecipeAdapter(Collections.singletonList(recipe));
        CloudworksAPI.validateMachineAdapter(machine, adapter);
        group.entries.put(recipe.id(), recipe);
    }

    public static void removeRecipe(ItemStack machine, String id) {
        requireRegisteredNonAlias(machine, "Recipes require a registered non-alias machine");
        RecipeGroup group = findRecipes(machine);
        if (group != null) group.entries.remove(id);
    }

    public static void removeAllRecipes(ItemStack machine) {
        CloudworksMachineInfo info = requireRegisteredNonAlias(machine,
                "Cannot clear an unregistered or alias machine");
        CloudworksThermalPlan thermal = info.thermal();
        if (thermal != null && thermal.kind != CloudworksThermalPlan.Kind.EVAPORATION)
            throw new IllegalArgumentException("Fixed thermal contracts cannot be removed as ordinary recipes");
        RecipeGroup group = findRecipes(machine);
        if (group != null) group.entries.clear();
        CloudworksAPI.clearMachineAdapters(machine);
    }

    public static void finish() {
        try {
            for (RecipeGroup group : RECIPES) {
                if (group.entries.isEmpty()) continue;
                List<CloudworksRecipeSpec> specs = new ArrayList<>(group.entries.values());
                boolean hasFuel = CloudworksAPI.machineInfo(group.machine).hasEnergyGenerator();
                boolean hasProcessing = false;
                for (CloudworksRecipeSpec spec : specs) {
                    hasFuel |= spec.energyPerOperation() > 0;
                    hasProcessing |= spec.energyPerOperation() == 0;
                }
                if (hasFuel && hasProcessing)
                    throw new IllegalArgumentException("Mixed processing and generation contracts: " + group.machine);
                CloudworksMachineAdapter adapter = new CloudworksScriptRecipeAdapter(specs);
                CloudworksAPI.validateMachineAdapter(group.machine, adapter);
                CloudworksAPI.registerMachineAdapter(group.machine, adapter);
            }
        } finally {
            RECIPES.clear();
        }
    }

    public static void validate(ItemStack machine, CloudworksMachineAdapter adapter) {
        CloudworksAPI.validateMachineAdapter(machine, adapter);
    }

    public static void validate(MachineSlotLayout layout, CloudworksMachineAdapter adapter) {
        CloudworksAPI.validateMachineAdapter(layout, adapter);
    }

    public static void constantGenerator(ItemStack machine, double energy) {
        CloudworksMachineInfo info = requireRegisteredNonAlias(machine,
                "Generation requires a registered non-alias machine");
        RecipeGroup pending = findRecipes(machine);
        if (!info.recipeTypes().isEmpty() || info.adapterCount() != 0
                || pending != null && !pending.entries.isEmpty())
            throw new IllegalArgumentException("Constant generation requires an empty recipe machine");
        if (info.thermal() != null)
            throw new IllegalArgumentException("Thermal machines settle generation through their heat system");
        if (!Double.isFinite(energy) || energy < 0) throw new IllegalArgumentException("Invalid constant generation");
        CloudworksGenerationPlan.constant(energy);
        CloudworksEnergyGenerator generator = (node, operations, context) ->
                CloudworksGenerationPlan.constant(energy * operations);
        CloudworksAPI.registerEnergyGenerator(machine, generator);
    }

    private static CloudworksMachineInfo requireRegisteredNonAlias(ItemStack machine, String message) {
        CloudworksMachineInfo info = CloudworksAPI.machineInfo(machine);
        if (!info.registered() || !info.aliasTarget().isEmpty()) throw new IllegalArgumentException(message);
        return info;
    }

    private static RecipeGroup recipes(ItemStack machine) {
        RecipeGroup group = findRecipes(machine);
        if (group != null) return group;
        group = new RecipeGroup(machine);
        RECIPES.add(group);
        return group;
    }

    private static RecipeGroup findRecipes(ItemStack machine) {
        for (RecipeGroup group : RECIPES) if (same(group.machine, machine)) return group;
        return null;
    }

    private static boolean same(ItemStack first, ItemStack second) {
        return ItemStack.areItemsEqual(first, second) && ItemStack.areItemStackTagsEqual(first, second);
    }

    private static final class RecipeGroup {
        final ItemStack machine;
        final Map<String, CloudworksRecipeSpec> entries = new LinkedHashMap<>();

        RecipeGroup(ItemStack machine) {
            this.machine = machine.copy();
            this.machine.setCount(1);
        }
    }
}
