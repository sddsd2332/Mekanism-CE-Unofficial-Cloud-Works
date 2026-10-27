package mekanism.cloudworks.common.runtime;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.Arrays;
import java.util.SplittableRandom;

import mekanism.cloudworks.common.service.CloudworksOutputMultiplier;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;

/**
 * Immutable integer recipe, including exact allocation of overlapping ingredients.
 */
public final class CloudworksRuntimeRecipe {
    int operationsPerCycle = 1;
    net.minecraft.util.ResourceLocation requiredEnvironmentAxis;
    String selectionGroup = "";
    int selectionPriority;
    double energyPerInput;
    long unitsPerOutput, prepaidFuelUnits;
    int creditResource = -1;
    boolean secondaryGasInputs;
    boolean[] secondaryInputs;
    int nativeSecondaryMode;
    CloudworksRecipeSpec definition;
    final long[] demands;
    final int[][] matches;
    final int[] outputIds, chanceIds;
    final long[] outputAmounts, chanceAmounts;
    final double[] chances;
    final int duration;
    final double fuelEnergy;
    final int[] choiceIds;
    final long choiceAmount;
    final boolean sunlightRequired;
    final int[][] configuration;
    final long decayAmount;
    final double extraEnergyPerTick;
    final boolean[] gasInputs;
    final boolean[] scalableOutputs;
    final boolean scalableSecondary;
    final long outputSeed;
    final String recipeId;
    private final int[] resources;
    private final boolean independentIngredients;
    private final boolean uniqueMatches;

    public CloudworksRuntimeRecipe(long[] demands, int[][] matches, int[] outputIds, long[] outputAmounts,
                                   int[] chanceIds, long[] chanceAmounts, double[] chances,
                                   int duration, double fuelEnergy) {
        this(demands, matches, outputIds, outputAmounts, chanceIds, chanceAmounts, chances,
                duration, fuelEnergy, new int[0], 0, false);
    }

    public CloudworksRuntimeRecipe(long[] demands, int[][] matches, int[] outputIds, long[] outputAmounts,
                                   int[] chanceIds, long[] chanceAmounts, double[] chances,
                                   int duration, double fuelEnergy, int[] choiceIds, long choiceAmount, boolean sunlightRequired) {
        this(demands, matches, outputIds, outputAmounts, chanceIds, chanceAmounts, chances,
                duration, fuelEnergy, choiceIds, choiceAmount, sunlightRequired, new int[0][]);
    }

    public CloudworksRuntimeRecipe(long[] demands, int[][] matches, int[] outputIds, long[] outputAmounts,
                                   int[] chanceIds, long[] chanceAmounts, double[] chances,
                                   int duration, double fuelEnergy, int[] choiceIds, long choiceAmount, boolean sunlightRequired,
                                   int[][] configuration) {
        this(demands, matches, outputIds, outputAmounts, chanceIds, chanceAmounts, chances,
                duration, fuelEnergy, choiceIds, choiceAmount, sunlightRequired, configuration, -1);
    }

    public CloudworksRuntimeRecipe(long[] demands, int[][] matches, int[] outputIds, long[] outputAmounts,
                                   int[] chanceIds, long[] chanceAmounts, double[] chances,
                                   int duration, double fuelEnergy, int[] choiceIds, long choiceAmount, boolean sunlightRequired,
                                   int[][] configuration, long decayAmount) {
        this(demands, matches, outputIds, outputAmounts, chanceIds, chanceAmounts, chances,
                duration, fuelEnergy, choiceIds, choiceAmount, sunlightRequired,
                configuration, decayAmount, 0D);
    }

    public CloudworksRuntimeRecipe(long[] demands, int[][] matches, int[] outputIds, long[] outputAmounts,
                                   int[] chanceIds, long[] chanceAmounts, double[] chances,
                                   int duration, double fuelEnergy, int[] choiceIds, long choiceAmount, boolean sunlightRequired,
                                   int[][] configuration, long decayAmount, double extraEnergyPerTick) {
        this(demands, matches, outputIds, outputAmounts, chanceIds, chanceAmounts, chances,
                duration, fuelEnergy, choiceIds, choiceAmount, sunlightRequired,
                configuration, decayAmount, extraEnergyPerTick, new boolean[demands.length]);
    }

    public CloudworksRuntimeRecipe(long[] demands, int[][] matches, int[] outputIds, long[] outputAmounts,
                                   int[] chanceIds, long[] chanceAmounts, double[] chances,
                                   int duration, double fuelEnergy, int[] choiceIds, long choiceAmount, boolean sunlightRequired,
                                   int[][] configuration, long decayAmount, double extraEnergyPerTick, boolean[] gasInputs) {
        this(demands, matches, outputIds, outputAmounts, chanceIds, chanceAmounts, chances,
                duration, fuelEnergy, choiceIds, choiceAmount, sunlightRequired,
                configuration, decayAmount, extraEnergyPerTick, gasInputs, allScalable(outputIds.length), true, 0);
    }

    public CloudworksRuntimeRecipe(long[] demands, int[][] matches, int[] outputIds, long[] outputAmounts,
                                   int[] chanceIds, long[] chanceAmounts, double[] chances,
                                   int duration, double fuelEnergy, int[] choiceIds, long choiceAmount, boolean sunlightRequired,
                                   int[][] configuration, long decayAmount, double extraEnergyPerTick, boolean[] gasInputs,
                                   boolean[] scalableOutputs, boolean scalableSecondary, long outputSeed) {
        this(demands, matches, outputIds, outputAmounts, chanceIds, chanceAmounts, chances,
                duration, fuelEnergy, choiceIds, choiceAmount, sunlightRequired,
                configuration, decayAmount, extraEnergyPerTick, gasInputs, scalableOutputs,
                scalableSecondary, outputSeed, "");
    }

    public CloudworksRuntimeRecipe(long[] demands, int[][] matches, int[] outputIds, long[] outputAmounts,
                                   int[] chanceIds, long[] chanceAmounts, double[] chances,
                                   int duration, double fuelEnergy, int[] choiceIds, long choiceAmount, boolean sunlightRequired,
                                   int[][] configuration, long decayAmount, double extraEnergyPerTick, boolean[] gasInputs,
                                   boolean[] scalableOutputs, boolean scalableSecondary, long outputSeed, String recipeId) {
        this.demands = demands.clone();
        this.matches = new int[matches.length][];
        IntArrayList unique = new IntArrayList();
        if (demands.length != matches.length || outputIds.length != outputAmounts.length
                || chanceIds.length != chanceAmounts.length || chanceIds.length != chances.length)
            throw new IllegalArgumentException("Malformed runtime recipe");
        for (int ingredient = 0; ingredient < matches.length; ingredient++) {
            if (demands[ingredient] <= 0) throw new IllegalArgumentException("Invalid runtime ingredient");
            this.matches[ingredient] = matches[ingredient].clone();
            for (int resource : matches[ingredient]) {
                if (resource < 0) throw new IllegalArgumentException("Invalid runtime ingredient id");
                if (!unique.contains(resource)) unique.add(resource);
            }
        }
        resources = unique.toIntArray();
        Arrays.sort(resources);
        boolean independent = true;
        for (int first = 0; first < matches.length; first++)
            for (int second = first + 1; second < matches.length; second++)
                for (int resource : matches[first])
                    for (int other : matches[second])
                        if (resource == other) independent = false;
        independentIngredients = independent;
        boolean uniqueMatches = true;
        for (int[] ingredient : this.matches)
            for (int first = 0; first < ingredient.length; first++)
                for (int second = first + 1; second < ingredient.length; second++)
                    if (ingredient[first] == ingredient[second]) uniqueMatches = false;
        this.uniqueMatches = uniqueMatches;
        this.outputIds = outputIds.clone();
        this.outputAmounts = outputAmounts.clone();
        this.chanceIds = chanceIds.clone();
        this.chanceAmounts = chanceAmounts.clone();
        this.chances = chances.clone();
        for (long amount : this.outputAmounts)
            if (amount <= 0) throw new IllegalArgumentException("Invalid runtime output");
        for (int index = 0; index < this.chances.length; index++)
            if (this.chanceAmounts[index] <= 0 || !Double.isFinite(this.chances[index])
                    || this.chances[index] < 0 || this.chances[index] > 1)
                throw new IllegalArgumentException("Invalid runtime chance output");
        if (!Double.isFinite(fuelEnergy) || fuelEnergy < 0)
            throw new IllegalArgumentException("Invalid runtime fuel energy");
        this.duration = duration;
        this.fuelEnergy = fuelEnergy;
        this.choiceIds = choiceIds.clone();
        if (choiceIds.length == 0 ? choiceAmount != 0 : choiceAmount <= 0)
            throw new IllegalArgumentException("Invalid exclusive output quantity");
        for (int resource : choiceIds)
            if (resource < 0) throw new IllegalArgumentException("Invalid exclusive output id");
        this.choiceAmount = choiceAmount;
        this.sunlightRequired = sunlightRequired;
        this.configuration = new int[configuration.length][];
        for (int index = 0; index < configuration.length; index++)
            this.configuration[index] = configuration[index].clone();
        if (decayAmount < -1 || decayAmount >= 0 && (demands.length != 1 || outputIds.length != 0
                || chanceIds.length != 0 || choiceIds.length != 0 || configuration.length != 0 || fuelEnergy != 0
                || extraEnergyPerTick != 0))
            throw new IllegalArgumentException("Invalid decay contract");
        this.decayAmount = decayAmount;
        if (!Double.isFinite(extraEnergyPerTick) || extraEnergyPerTick < 0D)
            throw new IllegalArgumentException("Invalid runtime recipe energy");
        this.extraEnergyPerTick = extraEnergyPerTick;
        if (gasInputs.length != demands.length) throw new IllegalArgumentException("Malformed thermal input codecs");
        this.gasInputs = gasInputs.clone();
        if (scalableOutputs.length != outputIds.length) throw new IllegalArgumentException("Malformed output roles");
        this.scalableOutputs = scalableOutputs.clone();
        this.scalableSecondary = scalableSecondary;
        this.outputSeed = outputSeed;
        this.recipeId = recipeId == null ? "" : recipeId;
    }

    private static boolean[] allScalable(int count) {
        boolean[] result = new boolean[count];
        Arrays.fill(result, true);
        return result;
    }

    boolean configured(CloudworksRuntimeResourceStore stock, int inventory) {
        for (int[] matches : configuration) {
            boolean matched = false;
            if (inventory >= 0) for (int resource : matches)
                if (stock.amount(inventory, resource) > 0) {
                    matched = true;
                    break;
                }
            if (!matched) return false;
        }
        return true;
    }

    boolean affectedByOutputMultiplier() {
        if (scalableSecondary && (chanceIds.length > 0 || choiceIds.length > 0)) return true;
        for (boolean scalable : scalableOutputs) if (scalable) return true;
        return false;
    }

    Allocation allocate(CloudworksRuntimeResourceStore stock, int inventory, int operations) {
        return allocate(stock, inventory, operations, demands);
    }

    boolean simpleBatch() {
        return independentIngredients && uniqueMatches && !secondaryGasInputs && nativeSecondaryMode == 0
                && chanceIds.length == 0 && choiceIds.length == 0;
    }

    /** Allocation-free upper bound for disjoint, fixed-consumption ingredients. */
    int independentInputLimit(CloudworksRuntimeResourceStore stock, int inventory, int maximum) {
        if (!independentIngredients || !uniqueMatches) return -1;
        long totalDemand = 0;
        for (int ingredient = 0; ingredient < demands.length; ingredient++) {
            if (demands[ingredient] > Long.MAX_VALUE - totalDemand) return 0;
            totalDemand += demands[ingredient];
            long available = 0;
            for (int resource : matches[ingredient])
                available = mekanism.cloudworks.common.service.CloudworksQuantities.addLimit(
                        available, stock.available(inventory, resource));
            maximum = (int) Math.min(maximum, available / demands[ingredient]);
        }
        return totalDemand == 0 ? maximum : (int) Math.min(maximum, Long.MAX_VALUE / totalDemand);
    }

    boolean canAllocate(CloudworksRuntimeResourceStore stock, int inventory, int count) {
        if (count <= 0) return false;
        int limit = independentInputLimit(stock, inventory, count);
        return limit >= 0 ? limit >= count : allocate(stock, inventory, count) != null;
    }

    /** Reserve whole primary fuel charges, and only this tick's side stream. */
    Allocation allocatePrepaid(CloudworksRuntimeResourceStore stock, int inventory, int units, double credit) {
        long[] required = new long[demands.length];
        long charges = (long) Math.ceil(Math.max(0D, units - credit) / prepaidFuelUnits);
        required[0] = Math.multiplyExact(charges, demands[0]);
        for (int ingredient = 1; ingredient < demands.length; ingredient++)
            required[ingredient] = Math.multiplyExact(demands[ingredient], units);
        return allocate(stock, inventory, 1, required);
    }
    Allocation allocateSecondary(CloudworksRuntimeResourceStore stock, int inventory, int operations,
            double multiplier, double[] credits) {
        if (!secondaryGasInputs) return allocate(stock, inventory, operations);
        long[] scaled = new long[demands.length];
        for (int ingredient = 0; ingredient < demands.length; ingredient++) {
            double amount = demands[ingredient] * (double) operations;
            if (secondaryInputs[ingredient]) amount = Math.ceil(amount * multiplier - credits[ingredient] - 1E-10D);
            if (!Double.isFinite(amount) || amount >= Long.MAX_VALUE || amount < 0) return null;
            scaled[ingredient] = (long) amount;
        }
        return allocate(stock, inventory, 1, scaled);
    }
    Allocation allocateNativeSecondary(CloudworksRuntimeResourceStore stock, int inventory, int operations, long[] perOperation) {
        for (int ingredient = 0; ingredient < demands.length; ingredient++) if (secondaryInputs[ingredient]) {
            long available = 0;
            for (int resource : matches[ingredient]) available = mekanism.cloudworks.common.service.CloudworksQuantities.addLimit(available, stock.available(inventory, resource));
            if (available < Math.max(1L, demands[ingredient] / Math.max(1, duration))) return null;
        }
        return allocate(stock, inventory, operations, perOperation);
    }
    private Allocation allocate(CloudworksRuntimeResourceStore stock, int inventory, int operations, long[] demands) {
        if (operations <= 0) return null;
        long required = 0;
        for (long demand : demands) {
            if (demand > Long.MAX_VALUE / operations) return null;
            long count = demand * operations;
            if (count > Long.MAX_VALUE - required) return null;
            required += count;
        }
        if (required == 0) return new Allocation(new int[0], new long[0]);
        if (independentIngredients) {
            long[] amounts = new long[resources.length];
            for (int ingredient = 0; ingredient < demands.length; ingredient++) {
                long remaining = demands[ingredient] * operations;
                for (int resource : matches[ingredient]) {
                    long take = Math.min(remaining, stock.available(inventory, resource));
                    amounts[Arrays.binarySearch(resources, resource)] = take;
                    remaining -= take;
                    if (remaining == 0) break;
                }
                if (remaining > 0) return null;
            }
            return compact(resources, amounts);
        }
        // Source -> ingredient -> concrete resource -> sink. Sparse primitive
        // residual edges avoid wildcard/shared-ingredient greedy allocation.
        int sink = 1 + demands.length + resources.length;
        Flow flow = new Flow(sink + 1);
        for (int ingredient = 0; ingredient < demands.length; ingredient++) {
            flow.edge(0, 1 + ingredient, demands[ingredient] * operations);
            for (int resource : matches[ingredient])
                flow.edge(1 + ingredient, 1 + demands.length + Arrays.binarySearch(resources, resource), required);
        }
        int[] drainEdges = new int[resources.length];
        for (int index = 0; index < resources.length; index++)
            drainEdges[index] = flow.edge(1 + demands.length + index, sink,
                    Math.min(required, stock.available(inventory, resources[index])));
        if (flow.maximum(0, sink, required) != required) return null;
        long[] amounts = new long[resources.length];
        for (int index = 0; index < amounts.length; index++)
            amounts[index] = flow.capacity[drainEdges[index] ^ 1];
        return compact(resources, amounts);
    }

    private static Allocation compact(int[] resources, long[] amounts) {
        int size = 0;
        for (long amount : amounts) if (amount > 0) size++;
        int[] ids = new int[size];
        long[] quantities = new long[size];
        int index = 0;
        for (int resource = 0; resource < resources.length; resource++)
            if (amounts[resource] > 0) {
                ids[index] = resources[resource];
                quantities[index++] = amounts[resource];
            }
        return new Allocation(ids, quantities);
    }

    Allocation outputs(int operations, SplittableRandom random) {
        IntArrayList ids = new IntArrayList();
        long[] amounts = new long[outputIds.length + chanceIds.length + choiceIds.length];
        boolean[] chance = new boolean[amounts.length];
        for (int index = 0; index < outputIds.length; index++)
            merge(ids, amounts, chance, outputIds[index], Math.multiplyExact(outputAmounts[index], operations), false);
        for (int index = 0; index < chanceIds.length; index++) {
            if (chances[index] == 0) continue;
            if (chances[index] >= 1) {
                merge(ids, amounts, chance, chanceIds[index],
                        Math.multiplyExact(chanceAmounts[index], operations), true);
                continue;
            }
            long successes = chanceSuccesses(operations, chances[index], random);
            if (successes > 0)
                merge(ids, amounts, chance, chanceIds[index], Math.multiplyExact(chanceAmounts[index], successes), true);
        }
        if (choiceIds.length > 0) {
            int[] counts = choiceCounts(operations, choiceIds.length, random);
            for (int index = 0; index < counts.length; index++)
                if (counts[index] > 0) merge(ids, amounts, chance, choiceIds[index],
                        Math.multiplyExact(choiceAmount, counts[index]), false);
        }
        return new Allocation(ids.toIntArray(), Arrays.copyOf(amounts, ids.size()), Arrays.copyOf(chance, ids.size()));
    }

    /**
     * Each output has its own deterministic random stream, unaffected by other nodes or outputs.
     */
    Allocation outputs(int operations, int units, long nodeSeed) {
        return outputs(operations, units, nodeSeed, 1D);
    }

    Allocation outputs(int operations, int units, long nodeSeed, double chanceMultiplier) {
        CloudworksOutputMultiplier.validate(units);
        if (!Double.isFinite(chanceMultiplier) || chanceMultiplier < 0D)
            throw new IllegalArgumentException("Invalid secondary chance multiplier");
        IntArrayList ids = new IntArrayList();
        long[] amounts = new long[outputIds.length + chanceIds.length + choiceIds.length];
        boolean[] chance = new boolean[amounts.length];
        long seed = CloudworksOutputMultiplier.mix(nodeSeed ^ outputSeed);
        for (int index = 0; index < outputIds.length; index++)
            merge(ids, amounts, chance, outputIds[index], scaledQuantity(outputAmounts[index], operations,
                    scalableOutputs[index] ? units : CloudworksOutputMultiplier.SCALE, seed, 1, index), false);
        for (int index = 0; index < chanceIds.length; index++) {
            double weighted = Math.min(1D, chances[index] * chanceMultiplier)
                    * (scalableSecondary ? units : CloudworksOutputMultiplier.SCALE)
                    / CloudworksOutputMultiplier.SCALE;
            long certain = (long) Math.floor(weighted);
            long events = Math.addExact(Math.multiplyExact(certain, operations),
                    chanceSuccesses(operations, weighted - certain, random(seed, 2, index)));
            if (events > 0) merge(ids, amounts, chance, chanceIds[index],
                    Math.multiplyExact(chanceAmounts[index], events), true);
        }
        if (choiceIds.length > 0) {
            SplittableRandom choice = random(seed, 3, 0);
            int[] counts = choiceCounts(operations, choiceIds.length, choice);
            for (int index = 0; index < counts.length; index++)
                if (counts[index] > 0)
                    merge(ids, amounts, chance, choiceIds[index], scaledQuantity(choiceAmount, counts[index],
                            scalableSecondary ? units : CloudworksOutputMultiplier.SCALE, seed, 4, index), false);
        }
        return new Allocation(ids.toIntArray(), Arrays.copyOf(amounts, ids.size()), Arrays.copyOf(chance, ids.size()));
    }

    private static SplittableRandom random(long seed, int kind, int output) {
        return new SplittableRandom(CloudworksOutputMultiplier.mix(seed ^ ((long) kind << 32) ^ output));
    }

    /**
     * Samples an equal-weight exclusive output pool. Small batches retain the
     * original per-operation stream exactly; large batches use a sequential
     * binomial approximation so million-operation recipes do not spend a full
     * server tick in an operation loop.
     */
    private static int[] choiceCounts(int operations, int choices, SplittableRandom random) {
        if (operations < 0 || choices <= 0) throw new IllegalArgumentException("Invalid choice batch");
        int[] counts = new int[choices];
        if (operations == 0) return counts;
        if (operations <= 4096) {
            for (int operation = 0; operation < operations; operation++)
                counts[random.nextInt(choices)]++;
            return counts;
        }
        long remaining = operations;
        int remainingChoices = choices;
        for (int index = 0; index < choices - 1; index++) {
            long sample = sampleBinomial(remaining, 1D / remainingChoices, random);
            counts[index] = (int) sample;
            remaining -= sample;
            remainingChoices--;
        }
        counts[choices - 1] = (int) remaining;
        return counts;
    }

    private static long sampleBinomial(long trials, double probability, SplittableRandom random) {
        if (trials <= 0 || probability <= 0D) return 0L;
        if (probability >= 1D) return trials;
        double mean = trials * probability;
        double variance = mean * (1D - probability);
        double u1 = Math.max(Double.MIN_VALUE, random.nextDouble());
        double u2 = random.nextDouble();
        double gaussian = Math.sqrt(-2D * Math.log(u1)) * Math.cos(2D * Math.PI * u2);
        long sample = Math.round(mean + Math.sqrt(variance) * gaussian);
        return Math.max(0L, Math.min(trials, sample));
    }

    private static long scaledQuantity(long amount, int operations, int units, long seed, int kind, int output) {
        long certain = Math.multiplyExact(CloudworksOutputMultiplier.whole(amount, units), operations);
        int remainder = CloudworksOutputMultiplier.remainder(amount, units);
        return remainder == 0 ? certain : Math.addExact(certain, chanceSuccesses(operations,
                remainder / (double) CloudworksOutputMultiplier.SCALE, random(seed, kind, output)));
    }

    static int chanceSuccesses(int operations, double chance, SplittableRandom random) {
        if (operations < 0 || !Double.isFinite(chance) || chance < 0 || chance > 1)
            throw new IllegalArgumentException("Invalid chance batch");
        if (chance == 0) return 0;
        if (chance == 1) return operations;
        if (operations <= 64) {
            int successes = 0;
            for (int operation = 0; operation < operations; operation++)
                if (random.nextDouble() < chance) successes++;
            return successes;
        }
        // Each lane compares an independent 53-bit uniform value, just as nextDouble does.
        // Bit planes resolve 64 comparisons together; decided lanes need no more bits.
        long threshold = (long) Math.ceil(chance * 0x1.0p53);
        if (threshold == (1L << 53)) return operations;
        int successes = 0;
        while (operations > 0) {
            int lanes = Math.min(64, operations);
            long undecided = lanes == 64 ? -1L : (1L << lanes) - 1;
            long accepted = 0;
            for (int bit = 52; bit >= 0 && undecided != 0; bit--) {
                long plane = random.nextLong();
                if ((threshold & (1L << bit)) != 0) {
                    accepted |= undecided & ~plane;
                    undecided &= plane;
                } else undecided &= ~plane;
                if ((threshold & ((1L << bit) - 1)) == 0) break;
            }
            successes += Long.bitCount(accepted);
            operations -= lanes;
        }
        return successes;
    }

    private static void merge(IntArrayList ids, long[] amounts, boolean[] chance,
                              int resource, long value, boolean chanceOutput) {
        int index = ids.indexOf(resource);
        if (index < 0) {
            index = ids.size();
            ids.add(resource);
        }
        amounts[index] = Math.addExact(amounts[index], value);
        chance[index] |= chanceOutput;
    }

    static final class Allocation {
        final int[] ids;
        final long[] amounts;
        final boolean[] chance;

        Allocation(int[] ids, long[] amounts) {
            this(ids, amounts, new boolean[ids.length]);
        }

        Allocation(int[] ids, long[] amounts, boolean[] chance) {
            if (chance.length != ids.length || amounts.length != ids.length)
                throw new IllegalArgumentException("Malformed output allocation");
            this.ids = ids.clone();
            this.amounts = amounts.clone();
            this.chance = chance.clone();
        }
    }

    private static final class Flow {
        private final int[] heads, parent, queue;
        private int size;
        private int[] next = new int[32], to = new int[32];
        private long[] capacity = new long[32];

        Flow(int vertices) {
            heads = new int[vertices];
            parent = new int[vertices];
            queue = new int[vertices];
            Arrays.fill(heads, -1);
        }

        int edge(int source, int target, long value) {
            int result = size;
            append(source, target, value);
            append(target, source, 0);
            return result;
        }

        private void append(int source, int target, long value) {
            if (size == to.length) {
                to = Arrays.copyOf(to, size * 2);
                next = Arrays.copyOf(next, size * 2);
                capacity = Arrays.copyOf(capacity, size * 2);
            }
            to[size] = target;
            capacity[size] = value;
            next[size] = heads[source];
            heads[source] = size++;
        }

        long maximum(int source, int sink, long required) {
            long result = 0;
            while (result < required) {
                Arrays.fill(parent, -1);
                parent[source] = -2;
                int tail = 1;
                queue[0] = source;
                for (int head = 0; head < tail && parent[sink] < 0; head++)
                    for (int edge = heads[queue[head]]; edge >= 0; edge = next[edge])
                        if (capacity[edge] > 0 && parent[to[edge]] == -1) {
                            parent[to[edge]] = edge;
                            queue[tail++] = to[edge];
                        }
                if (parent[sink] < 0) break;
                long amount = required - result;
                for (int vertex = sink; vertex != source; vertex = to[parent[vertex] ^ 1])
                    amount = Math.min(amount, capacity[parent[vertex]]);
                for (int vertex = sink; vertex != source; vertex = to[parent[vertex] ^ 1]) {
                    capacity[parent[vertex]] -= amount;
                    capacity[parent[vertex] ^ 1] += amount;
                }
                result += amount;
            }
            return result;
        }
    }
}
