package mekanism.cloudworks.common.recipe;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import mekanism.api.qio.resource.*;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.cloudworks.common.data.CloudworksOreRule;
import mekanism.cloudworks.common.registry.*;
import mekanism.cloudworks.common.service.CloudworksNativeContracts;
import mekanism.common.block.states.BlockStateMachine.MachineType;
import mekanism.common.recipe.RecipeHandler;
import mekanism.common.tile.machine.TileEntityOredictionificator;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

/** Owner-thread rule compiler shared by editing, admission and runtime projection. */
public final class CloudworksOredictionificator {
    private static final Map<String, CloudworksMachineAdapter> FROZEN = new ConcurrentHashMap<>();
    private static final CloudworksMachineAdapter MARKER = registration -> Collections.emptyList();
    private CloudworksOredictionificator() {}
    public static CloudworksMachineAdapter registrationAdapter() { return MARKER; }
    public static boolean isMachine(ItemStack stack) { return !stack.isEmpty() && CloudworksMachineAdapters.getAll(stack).contains(MARKER); }
    public static boolean allowed(String name) {
        for (String prefix : TileEntityOredictionificator.possibleFilters) if (!prefix.isEmpty() && name.startsWith(prefix)) return true;
        return false;
    }
    public static List<String> names() {
        List<String> names = new ArrayList<>(); for (String name : OreDictionary.getOreNames()) if (allowed(name)) names.add(name);
        Collections.sort(names); return names;
    }
    public static boolean validTarget(CloudworksOreRule rule) {
        ItemStack target = rule.target();
        if (!allowed(rule.ore) || target.isEmpty() || target.hasTagCompound() || target.getMetadata() == OreDictionary.WILDCARD_VALUE) return false;
        for (ItemStack candidate : OreDictionary.getOres(rule.ore, false)) if (OreDictionary.itemMatches(candidate, target, false)) return true;
        return false;
    }
    public static void validate(List<CloudworksOreRule> rules) {
        Set<String> names = new HashSet<>();
        for (CloudworksOreRule rule : rules)
            if (!names.add(rule.ore) || !allowed(rule.ore) || !rule.target().isEmpty() && !validTarget(rule))
                throw new IllegalArgumentException("cloudworks.error.ore_rule_invalid");
    }
    public static CloudworksMachineAdapter adapter(String key, MachineExecutionProfile profile, MachineSlotLayout slots,
            ItemStack machine, List<CloudworksOreRule> rules) {
        String shape = key + "|oreRules=" + CloudworksOreRule.writeList(rules) + "|v=" + RecipeHandler.getGlobalRecipeVersion();
        CloudworksMachineAdapter cached = FROZEN.get(shape);
        if (cached != null && CloudworksAsyncRecipeAdmission.peek(cached).isPresent()) return cached;
        if (CloudworksNativeContracts.isWorker()) throw new IllegalStateException("Ore rules were not frozen before dispatch");
        List<CloudworksRecipeSpec> recipes = new ArrayList<>();
        for (CloudworksOreRule rule : rules) {
            if (!rule.enabled || !validTarget(rule)) continue;
            List<QIOResourceDescriptor> matches = new ArrayList<>();
            for (ItemStack input : OreDictionary.getOres(rule.ore, false)) {
                if (input.isEmpty() || input.hasTagCompound() || input.getMetadata() == OreDictionary.WILDCARD_VALUE) continue;
                String first = "";
                for (int ore : OreDictionary.getOreIDs(input)) if (allowed(OreDictionary.getOreName(ore))) { first = OreDictionary.getOreName(ore); break; }
                if (rule.ore.equals(first)) matches.add(QIOResourceCodecs.item(input));
            }
            if (!matches.isEmpty()) recipes.add(CloudworksRecipeSpec.builder("cloudworks:ore/" + rule.ore).duration(1)
                    .alternatives(matches, 1, "item_input").unscaledOutput(QIOResourceCodecs.item(rule.target()), 1, "item_output")
                    .unscaledOutputs().build());
        }
        CloudworksMachineAdapter adapter = registration -> Collections.unmodifiableList(recipes);
        CloudworksMachineRegistration registration = new CloudworksMachineRegistration(machine, profile, MachineIdentityPolicy.none(), slots, "");
        CloudworksMachineAdapters.registerDerived(adapter, registration);
        CloudworksAsyncRecipeAdmission.admitDerived(adapter, registration); FROZEN.put(shape, adapter); return adapter;
    }
}
