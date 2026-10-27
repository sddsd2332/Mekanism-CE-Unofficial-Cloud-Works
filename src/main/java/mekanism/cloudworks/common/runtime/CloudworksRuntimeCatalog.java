package mekanism.cloudworks.common.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.machine.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.environment.CloudworksGenerationPlan;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.recipe.CloudworksAsyncRecipeAdmission;
import mekanism.cloudworks.common.recipe.CloudworksFrozenRecipeView;
import mekanism.cloudworks.common.recipe.CloudworksRecipeBatch;
import mekanism.cloudworks.common.registry.CloudworksMachineAdapters;
import mekanism.cloudworks.common.service.CloudworksDetachedState;
import mekanism.cloudworks.common.service.CloudworksQuantities;
import mekanism.cloudworks.common.service.CloudworksResourceSymbols;
import java.util.LinkedHashSet;
import java.util.Set;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.common.service.CloudworksNativeContracts;

/** Cold compiler for Runtime contracts; independent of the retired detached Tick engine. */
public final class CloudworksRuntimeCatalog {
    private static final CloudworksGenerationPlan ZERO_GENERATION = CloudworksGenerationPlan.constant(0);
    private final Map<String, List<CloudworksRuntimeRecipe>> recipes, fuels;
    private final Map<UUID, CloudworksGenerationPlan> generation;
    private final Map<String, String> unsupported;
    private final List<String> codecs;
    private final long seed;
    private final Map<String, CloudworksRuntimeHeatFuel> heat;
    private final Map<QIOResourceDescriptor, Integer> generationResources;

    private CloudworksRuntimeCatalog(Map<String, List<CloudworksRuntimeRecipe>> recipes,
            Map<String, List<CloudworksRuntimeRecipe>> fuels, Map<UUID, CloudworksGenerationPlan> generation,
            Map<String, String> unsupported, List<String> codecs, long seed,
            Map<String, CloudworksRuntimeHeatFuel> heat, Map<QIOResourceDescriptor, Integer> generationResources) {
        this.recipes = Collections.unmodifiableMap(recipes);
        this.fuels = Collections.unmodifiableMap(fuels);
        this.generation = Collections.unmodifiableMap(generation);
        this.unsupported = Collections.unmodifiableMap(unsupported);
        this.codecs = Collections.unmodifiableList(new ArrayList<>(codecs));
        this.seed = seed;
        this.heat = Collections.unmodifiableMap(heat);
        this.generationResources = Collections.unmodifiableMap(generationResources);
    }

    public static CloudworksRuntimeCatalog prepare(CloudworksDetachedState.Capture capture,
            CloudworksResourceSymbols symbols, List<ProductionLineData> lines) {
        return prepare(capture, symbols, lines, null);
    }

    public static CloudworksRuntimeCatalog prepare(CloudworksDetachedState.Capture capture,
            CloudworksResourceSymbols symbols, List<ProductionLineData> lines,
            CloudworksRuntimeHeatFuel frozenHeatFuel) {
        return prepare(capture, symbols, lines, frozenHeatFuel,
                CloudworksNativeContracts.generationPlans(
                        capture.originSnapshot(), capture.view().generationContext));
    }

    static CloudworksRuntimeCatalog prepare(CloudworksDetachedState.Capture capture,
            CloudworksResourceSymbols symbols, List<ProductionLineData> lines,
            CloudworksRuntimeHeatFuel frozenHeatFuel, Map<UUID, CloudworksGenerationPlan> generation) {
        Map<String, FrozenMachine> views = new LinkedHashMap<>();
        Set<String> heatTypes = new LinkedHashSet<>();
        for (ProductionLineData line : lines)
            for (MachineNodeData node : line.machines()) {
                if (node.frozenByMissingMod()) continue; // B5: reserved, never compiled
                MachineNodeData.MachineCapabilities machine = node.capabilities();
                if (machine.thermal() != null && (machine.thermal().kind == CloudworksThermalPlan.Kind.FUELWOOD
                        || machine.thermal().kind == CloudworksThermalPlan.Kind.HEAT_GENERATOR))
                    heatTypes.add(machine.keyString());
                if (!views.containsKey(machine.keyString())) views.put(machine.keyString(), collect(machine, symbols));
                for (CloudworksNodeMechanisms.Branch branch : machine.branches()) {
                    String key = machine.keyString() + "|branch=" + branch.id;
                    if (!views.containsKey(key)) views.put(key, collect(key, branch.adapters, symbols));
                    if (branch.thermal != null && (branch.thermal.kind == CloudworksThermalPlan.Kind.FUELWOOD
                            || branch.thermal.kind == CloudworksThermalPlan.Kind.HEAT_GENERATOR))
                        heatTypes.add(key);
                }
            }
        Map<QIOResourceDescriptor, Integer> generationResources = new LinkedHashMap<>();
        for (CloudworksGenerationPlan plan : generation.values())
            for (CloudworksGenerationPlan.ResourceOutput output : plan.resourceOutputs())
                generationResources.put(output.resource(), symbols.id(output.resource()));
        // Register every producer first so downstream matchers can see products
        // regardless of machine order, including chance outputs and fuel returns.
        for (FrozenMachine view : views.values())
            for (CloudworksFrozenRecipeView.Entry entry : view.entries) {
                if (entry.definition() != null && entry.definition().creditResource() != null) symbols.id(entry.definition().creditResource());
                for (CloudworksResourceStack output : entry.outputTemplate()) symbols.id(output.descriptor());
                for (CloudworksRecipeBatch.ChanceOutput output : entry.chanceOutputTemplate())
                    symbols.id(output.descriptor());
                for (CloudworksResourceStack output : entry.choiceOutputTemplate()) symbols.id(output.descriptor());
            }
        Map<String, CloudworksRuntimeHeatFuel> heat = new LinkedHashMap<>();
        if (!heatTypes.isEmpty()) {
            if (frozenHeatFuel == null)
                throw new IllegalStateException("Heat fuel contract was not pre-frozen on the server thread");
            CloudworksRuntimeHeatFuel plan = frozenHeatFuel;
            for (String type : heatTypes) heat.put(type, plan);
        }
        Map<String, List<CloudworksRuntimeRecipe>> recipes = new LinkedHashMap<>(), fuels = new LinkedHashMap<>();
        Map<String, String> unsupported = new LinkedHashMap<>();
        for (Map.Entry<String, FrozenMachine> machine : views.entrySet()) {
            List<CloudworksRuntimeRecipe> processing = new ArrayList<>(), fuel = new ArrayList<>();
            for (CloudworksFrozenRecipeView.Entry entry : machine.getValue().entries) {
                CloudworksRuntimeRecipe recipe = freeze(entry, symbols);
                recipe.definition = entry.definition();
                if (entry.energyPerOperation() > 0) fuel.add(recipe); else processing.add(recipe);
            }
            long continuous = processing.stream().filter(recipe -> recipe.energyPerInput > 0).count();
            if (continuous > 0 && (continuous != processing.size() || !fuel.isEmpty()))
                throw new IllegalArgumentException("Power-driven and ordinary processing contracts cannot share an execution entry");
            recipes.put(machine.getKey(), Collections.unmodifiableList(processing));
            fuels.put(machine.getKey(), Collections.unmodifiableList(fuel));
            if (processing.isEmpty() && fuel.isEmpty() && machine.getValue().refusal != null
                    && !heat.containsKey(machine.getKey()))
                unsupported.put(machine.getKey(), machine.getValue().refusal);
        }
        return new CloudworksRuntimeCatalog(recipes, fuels, generation, unsupported,
                symbols.codecs(), capture.originSnapshot().transactionSeed(), heat, generationResources);
    }

    private static FrozenMachine collect(MachineNodeData.MachineCapabilities machine, CloudworksResourceSymbols symbols) {
        return collect(machine.keyString(), machine.adapters(), symbols);
    }

    private static FrozenMachine collect(String key, List<CloudworksMachineAdapter> adapters, CloudworksResourceSymbols symbols) {
        List<CloudworksFrozenRecipeView.Entry> entries = new ArrayList<>();
        String refusal = null;
        for (CloudworksMachineAdapter adapter : adapters) {
            Optional<CloudworksFrozenRecipeView> view = symbols.cachedFreeze(key + ":"
                    + adapter.getClass().getName() + "#" + CloudworksMachineAdapters.registrationId(adapter), adapter);
            if (view.isPresent()) entries.addAll(view.get().entries());
            else refusal = CloudworksAsyncRecipeAdmission.rejectionReason(adapter);
        }
        return new FrozenMachine(entries, refusal);
    }

    private static CloudworksRuntimeRecipe freeze(CloudworksFrozenRecipeView.Entry entry, CloudworksResourceSymbols symbols) {
        // A valid continuous recipe can have no physical copy of its input yet.
        // Seed concrete declarations before matching, rather than refusing an idle alternative.
        if (entry.definition() != null && (entry.definition().energyPerInput() > 0 || entry.definition().prepaidFuelUnits() > 0)) {
            var primary = entry.definition().inputs().get(0).matcher().singleResource();
            if (primary != null) symbols.id(primary);
        }
        List<CloudworksResourceStack> output = entry.outputTemplate();
        int[] ids = new int[output.size()];
        long[] amounts = new long[ids.length];
        boolean[] scalable = new boolean[ids.length];
        for (int index = 0; index < ids.length; index++) {
            ids[index] = symbols.id(output.get(index).descriptor());
            amounts[index] = output.get(index).amount();
            scalable[index] = entry.scalableOutput(index);
        }
        List<CloudworksRecipeBatch.ChanceOutput> chance = entry.chanceOutputTemplate();
        int[] chanceIds = new int[chance.size()];
        long[] chanceAmounts = new long[chance.size()];
        double[] chances = new double[chance.size()];
        for (int index = 0; index < chance.size(); index++) {
            chanceIds[index] = symbols.id(chance.get(index).descriptor());
            chanceAmounts[index] = chance.get(index).amount();
            chances[index] = chance.get(index).chance();
        }
        long[] demands = new long[entry.inputCount()];
        boolean[] gasInputs = new boolean[demands.length];
        int[][] matches = new int[demands.length][];
        for (int ingredient = 0; ingredient < demands.length; ingredient++) {
            demands[ingredient] = entry.inputAmount(ingredient);
            gasInputs[ingredient] = QIOResourceCodecs.GAS_STACK_ID.equals(entry.inputCodec(ingredient));
            matches[ingredient] = symbols.matchingResources(entry, ingredient, entry.inputMatcher(ingredient));
        }
        List<CloudworksResourceStack> choices = entry.choiceOutputTemplate();
        int[] choiceIds = new int[choices.size()];
        long choiceAmount = choices.isEmpty() ? 0 : choices.get(0).amount();
        for (int index = 0; index < choices.size(); index++) {
            if (choices.get(index).amount() != choiceAmount)
                throw new IllegalArgumentException("Exclusive output candidates have different quantities");
            if (!choices.get(index).descriptor().getCodecId().equals(choices.get(0).descriptor().getCodecId()))
                throw new IllegalArgumentException("Exclusive output pool spans different codecs");
            choiceIds[index] = symbols.id(choices.get(index).descriptor());
        }
        int[][] configuration = new int[entry.configurationCount()][];
        for (int index = 0; index < configuration.length; index++)
            configuration[index] = symbols.matchingResources(entry, entry.inputCount() + index, entry.configurationMatcher(index));
        CloudworksRuntimeRecipe result = new CloudworksRuntimeRecipe(demands, matches, ids, amounts, chanceIds, chanceAmounts, chances,
                entry.fixedDuration(), entry.energyPerOperation(),
                choiceIds, choiceAmount, entry.sunlightRequired(), configuration, entry.decayAmount(),
                entry.extraEnergyPerTick(), gasInputs, scalable, entry.outputMultiplierEnabled(), entry.outputSeed(),
                entry.recipeIdentity());
        if (entry.definition() != null) result.operationsPerCycle = entry.definition().operationsPerCycle();
        if (entry.definition() != null) {
            result.requiredEnvironmentAxis = entry.definition().requiredEnvironmentAxis();
            result.selectionGroup = entry.definition().selectionGroup(); result.selectionPriority = entry.definition().selectionPriority();
            result.energyPerInput = entry.definition().energyPerInput(); result.unitsPerOutput = entry.definition().unitsPerOutput();
            result.secondaryGasInputs = entry.definition().secondaryGasInputs() || entry.definition().secondaryFluidInputs();
            result.nativeSecondaryMode = entry.definition().nativeSecondaryMode();
            result.secondaryInputs = new boolean[demands.length];
            for (int ingredient = 0; ingredient < demands.length; ingredient++) result.secondaryInputs[ingredient] =
                    entry.definition().secondaryGasInputs() && QIOResourceCodecs.GAS_STACK_ID.equals(entry.inputCodec(ingredient))
                    || entry.definition().secondaryFluidInputs() && QIOResourceCodecs.FLUID_STACK_ID.equals(entry.inputCodec(ingredient));
            result.prepaidFuelUnits = entry.definition().prepaidFuelUnits();
            if (entry.definition().creditResource() != null) result.creditResource = symbols.id(entry.definition().creditResource());
            if (result.energyPerInput > 0 || result.prepaidFuelUnits > 0) {
                if (demands.length < 1 || chanceIds.length != 0)
                    throw new IllegalArgumentException("Continuous resource contract must have an exact primary input");
                if (result.energyPerInput > 0 && (demands.length != 1 || configuration.length != 0 || matches[0].length != 1 || ids.length != 1
                        || choiceIds.length != 0 || entry.energyPerOperation() > 0))
                    throw new IllegalArgumentException("Invalid power-driven output");
                if (result.energyPerInput > 0) result.creditResource = matches[0][0];
            }
        }
        return result;
    }

    List<CloudworksRuntimeRecipe> recipes(String type, boolean fuel) {
        return (fuel ? fuels : recipes).getOrDefault(type, Collections.emptyList());
    }
    CloudworksGenerationPlan generation(UUID node) {
        return generation.getOrDefault(node, ZERO_GENERATION);
    }
    boolean generator(UUID node) { return generation.containsKey(node); }
    int generationResource(QIOResourceDescriptor resource) { return generationResources.get(resource); }
    Map<String, String> unsupported() { return unsupported; }
    String codec(int resource) { return codecs.get(resource); }
    long seed() { return seed; }
    CloudworksRuntimeHeatFuel heat(String type) { return heat.get(type); }

    private static final class FrozenMachine {
        final List<CloudworksFrozenRecipeView.Entry> entries;
        final String refusal;

        FrozenMachine(List<CloudworksFrozenRecipeView.Entry> entries, String refusal) {
            this.entries = entries;
            this.refusal = refusal;
        }
    }
}
