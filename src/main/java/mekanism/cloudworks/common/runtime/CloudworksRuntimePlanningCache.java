package mekanism.cloudworks.common.runtime;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import java.util.IdentityHashMap;
import java.util.Map;

/** Epoch-local failed-planning stamps. Running work and dynamic hooks never sleep here. */
final class CloudworksRuntimePlanningCache {
    private final Stamp[] waiting;
    private final Int2ObjectOpenHashMap<Selection> candidates = new Int2ObjectOpenHashMap<>();
    private final Map<CloudworksRuntimeRecipe, OutputShape> simpleOutputs = new IdentityHashMap<>();
    private final Map<CloudworksRuntimeRecipe, int[]> batchHints = new IdentityHashMap<>();
    private long skipped;

    CloudworksRuntimePlanningCache(int nodes) { waiting = new Stamp[nodes]; }
    long skipped() { return skipped; }

    int batchHint(int node, CloudworksRuntimeRecipe recipe) {
        int[] hints = batchHints.get(recipe);
        return hints == null ? 0 : hints[node];
    }

    void rememberBatchHint(int node, CloudworksRuntimeRecipe recipe, int count) {
        if (count <= 0) return;
        int[] hints = batchHints.computeIfAbsent(recipe, ignored -> new int[waiting.length]);
        hints[node] = count;
    }

    CloudworksRuntimeRecipe[] candidates(int node, CloudworksRuntimeRecipe[] source, CloudworksGenerationContext environment) {
        Selection cached = candidates.get(node);
        return cached != null && cached.source == source && cached.environment == environment ? cached.result : null;
    }
    void candidates(int node, CloudworksRuntimeRecipe[] source, CloudworksGenerationContext environment,
                    CloudworksRuntimeRecipe[] result) { candidates.put(node, new Selection(source, environment, result)); }

    /**
     * Per-codec linear output coefficients for a deterministic simple recipe.
     * The resource dictionary is epoch-stable, so one immutable shape can be
     * shared by every node which uses this recipe.
     */
    OutputShape simpleOutputs(CloudworksRuntimeRecipe recipe, int[] resourceCodec, int codecCount) {
        OutputShape cached = simpleOutputs.get(recipe);
        if (cached != null) return cached;
        long[] totals = new long[codecCount];
        try {
            for (int output = 0; output < recipe.outputIds.length; output++) {
                int resource = recipe.outputIds[output];
                if (resource < 0 || resource >= resourceCodec.length) return null;
                int codec = resourceCodec[resource];
                if (codec < 0 || codec >= totals.length) return null;
                totals[codec] = Math.addExact(totals[codec], recipe.outputAmounts[output]);
            }
            int count = 0;
            long total = 0;
            for (long amount : totals) if (amount > 0) {
                count++;
                total = Math.addExact(total, amount);
            }
            int[] codecs = new int[count];
            long[] amounts = new long[count];
            int index = 0;
            for (int codec = 0; codec < totals.length; codec++) if (totals[codec] > 0) {
                codecs[index] = codec;
                amounts[index++] = totals[codec];
            }
            OutputShape shape = new OutputShape(codecs, amounts, total);
            simpleOutputs.put(recipe, shape);
            return shape;
        } catch (ArithmeticException overflow) {
            // Keep unusual overflow contracts on the established exact path.
            return null;
        }
    }

    boolean unchanged(int node, CloudworksRuntimeProgram program, CloudworksRuntimeMachineStore machines,
                      CloudworksRuntimeOperationStore operations, CloudworksRuntimeResourceStore stock) {
        Stamp stamp = waiting[node];
        if (stamp == null) return false;
        if (stamp.recipes == program.recipes[machines.machineType(node)] && stamp.state == machines.state(node)
                && operations.batchCount(node) == 0 && stamp.input == stock.version(machines.input(node))
                && stamp.output == stock.version(machines.output(node))
                && stamp.configuration == configurationVersion(node, machines, stock)) {
            skipped++;
            return true;
        }
        waiting[node] = null;
        return false;
    }

    void remember(int node, byte state, CloudworksRuntimeProgram program, CloudworksRuntimeMachineStore machines,
                  CloudworksRuntimeResourceStore stock) {
        if (program.physicalRoot[node] != node || program.physicalEnd[node] != node + 1
                || program.hasPreWork[node] || program.hasRecipeCandidate[node]
                || !program.postWorkModules[node].isEmpty()) return;
        CloudworksRuntimeRecipe[] recipes = program.recipes[machines.machineType(node)];
        for (CloudworksRuntimeRecipe recipe : recipes)
            if (!recipe.simpleBatch() || recipe.sunlightRequired || recipe.prepaidFuelUnits > 0
                    || recipe.energyPerInput > 0 || recipe.requiredEnvironmentAxis != null) return;
        switch (state) {
            case CloudworksRuntimeMachineStore.WAITING_INPUT:
            case CloudworksRuntimeMachineStore.INSUFFICIENT_INPUT:
            case CloudworksRuntimeMachineStore.MISSING_INPUT:
            case CloudworksRuntimeMachineStore.NO_MATCHING_RECIPE:
            case CloudworksRuntimeMachineStore.WAITING_TEMPLATE:
                break;
            case CloudworksRuntimeMachineStore.WAITING_OUTPUT:
                // With alternatives, a formerly unpowered recipe could become viable.
                if (recipes.length != 1) return;
                break;
            default: return;
        }
        waiting[node] = new Stamp(recipes, state, stock.version(machines.input(node)), stock.version(machines.output(node)),
                configurationVersion(node, machines, stock));
    }

    private static long configurationVersion(int node, CloudworksRuntimeMachineStore machines,
                                              CloudworksRuntimeResourceStore stock) {
        return machines.configuration(node) < 0 ? -1 : stock.version(machines.configuration(node));
    }

    private static final class Stamp {
        final CloudworksRuntimeRecipe[] recipes;
        final byte state;
        final long input, output, configuration;
        Stamp(CloudworksRuntimeRecipe[] recipes, byte state, long input, long output, long configuration) {
            this.recipes = recipes;
            this.state = state;
            this.input = input; this.output = output; this.configuration = configuration;
        }
    }

    private static final class Selection {
        final CloudworksRuntimeRecipe[] source, result;
        final CloudworksGenerationContext environment;
        Selection(CloudworksRuntimeRecipe[] source, CloudworksGenerationContext environment, CloudworksRuntimeRecipe[] result) {
            this.source = source; this.environment = environment; this.result = result;
        }
    }

    static final class OutputShape {
        final int[] codecs;
        final long[] amounts;
        final long totalPerOperation;

        OutputShape(int[] codecs, long[] amounts, long totalPerOperation) {
            this.codecs = codecs;
            this.amounts = amounts;
            this.totalPerOperation = totalPerOperation;
        }
    }
}
