package mekanism.cloudworks.common.runtime;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Arrays;
import java.util.SplittableRandom;

/** Immutable integer recipe, including exact allocation of overlapping ingredients. */
public final class CloudworksRuntimeRecipe {
    final long[] demands;
    final int[][] matches;
    final int[] outputIds, chanceIds;
    final long[] outputAmounts, chanceAmounts;
    final double[] chances;
    final int dimension, duration;
    final boolean dimensionRequired;
    final double fuelEnergy;
    final int[] choiceIds;
    final long choiceAmount;
    final boolean sunlightRequired;
    final int[][] configuration;
    final long decayAmount;
    final double extraEnergyPerTick;
    final boolean[] gasInputs;
    private final int[] resources;
    private final boolean independentIngredients;

    public CloudworksRuntimeRecipe(long[] demands, int[][] matches, int[] outputIds, long[] outputAmounts,
            int[] chanceIds, long[] chanceAmounts, double[] chances, int dimension, int duration, double fuelEnergy) {
        this(demands, matches, outputIds, outputAmounts, chanceIds, chanceAmounts, chances,
                dimension, dimension != -1, duration, fuelEnergy);
    }

    public CloudworksRuntimeRecipe(long[] demands, int[][] matches, int[] outputIds, long[] outputAmounts,
            int[] chanceIds, long[] chanceAmounts, double[] chances, int dimension, boolean dimensionRequired,
            int duration, double fuelEnergy) {
        this(demands, matches, outputIds, outputAmounts, chanceIds, chanceAmounts, chances, dimension,
                dimensionRequired, duration, fuelEnergy, new int[0], 0, false);
    }

    public CloudworksRuntimeRecipe(long[] demands, int[][] matches, int[] outputIds, long[] outputAmounts,
            int[] chanceIds, long[] chanceAmounts, double[] chances, int dimension, boolean dimensionRequired,
            int duration, double fuelEnergy, int[] choiceIds, long choiceAmount, boolean sunlightRequired) {
        this(demands, matches, outputIds, outputAmounts, chanceIds, chanceAmounts, chances, dimension,
                dimensionRequired, duration, fuelEnergy, choiceIds, choiceAmount, sunlightRequired, new int[0][]);
    }

    public CloudworksRuntimeRecipe(long[] demands, int[][] matches, int[] outputIds, long[] outputAmounts,
            int[] chanceIds, long[] chanceAmounts, double[] chances, int dimension, boolean dimensionRequired,
            int duration, double fuelEnergy, int[] choiceIds, long choiceAmount, boolean sunlightRequired,
            int[][] configuration) {
        this(demands, matches, outputIds, outputAmounts, chanceIds, chanceAmounts, chances, dimension,
                dimensionRequired, duration, fuelEnergy, choiceIds, choiceAmount, sunlightRequired, configuration, -1);
    }

    public CloudworksRuntimeRecipe(long[] demands, int[][] matches, int[] outputIds, long[] outputAmounts,
            int[] chanceIds, long[] chanceAmounts, double[] chances, int dimension, boolean dimensionRequired,
            int duration, double fuelEnergy, int[] choiceIds, long choiceAmount, boolean sunlightRequired,
            int[][] configuration, long decayAmount) {
        this(demands, matches, outputIds, outputAmounts, chanceIds, chanceAmounts, chances, dimension,
                dimensionRequired, duration, fuelEnergy, choiceIds, choiceAmount, sunlightRequired,
                configuration, decayAmount, 0D);
    }

    public CloudworksRuntimeRecipe(long[] demands, int[][] matches, int[] outputIds, long[] outputAmounts,
            int[] chanceIds, long[] chanceAmounts, double[] chances, int dimension, boolean dimensionRequired,
            int duration, double fuelEnergy, int[] choiceIds, long choiceAmount, boolean sunlightRequired,
            int[][] configuration, long decayAmount, double extraEnergyPerTick) {
        this(demands, matches, outputIds, outputAmounts, chanceIds, chanceAmounts, chances, dimension,
                dimensionRequired, duration, fuelEnergy, choiceIds, choiceAmount, sunlightRequired,
                configuration, decayAmount, extraEnergyPerTick, new boolean[demands.length]);
    }

    public CloudworksRuntimeRecipe(long[] demands, int[][] matches, int[] outputIds, long[] outputAmounts,
            int[] chanceIds, long[] chanceAmounts, double[] chances, int dimension, boolean dimensionRequired,
            int duration, double fuelEnergy, int[] choiceIds, long choiceAmount, boolean sunlightRequired,
            int[][] configuration, long decayAmount, double extraEnergyPerTick, boolean[] gasInputs) {
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
        this.dimension = dimension;
        this.dimensionRequired = dimensionRequired;
        this.duration = duration;
        this.fuelEnergy = fuelEnergy;
        this.choiceIds = choiceIds.clone();
        if (choiceIds.length == 0 ? choiceAmount != 0 : choiceAmount <= 0)
            throw new IllegalArgumentException("Invalid exclusive output quantity");
        for (int resource : choiceIds) if (resource < 0) throw new IllegalArgumentException("Invalid exclusive output id");
        this.choiceAmount = choiceAmount;
        this.sunlightRequired = sunlightRequired;
        this.configuration = new int[configuration.length][];
        for (int index = 0; index < configuration.length; index++) this.configuration[index] = configuration[index].clone();
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
    }

    boolean configured(CloudworksRuntimeResourceStore stock, int inventory) {
        for (int[] matches : configuration) {
            boolean matched = false;
            if (inventory >= 0) for (int resource : matches)
                if (stock.amount(inventory, resource) > 0) { matched = true; break; }
            if (!matched) return false;
        }
        return true;
    }

    Allocation allocate(CloudworksRuntimeResourceStore stock, int inventory, int operations) {
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
        for (int index = 0; index < outputIds.length; index++)
            merge(ids, amounts, outputIds[index], Math.multiplyExact(outputAmounts[index], operations));
        for (int index = 0; index < chanceIds.length; index++) {
            if (chances[index] == 0) continue;
            if (chances[index] >= 1) {
                merge(ids, amounts, chanceIds[index],
                        Math.multiplyExact(chanceAmounts[index], operations));
                continue;
            }
            long successes = chanceSuccesses(operations, chances[index], random);
            if (successes > 0)
                merge(ids, amounts, chanceIds[index], Math.multiplyExact(chanceAmounts[index], successes));
        }
        if (choiceIds.length > 0) {
            int[] counts = new int[choiceIds.length];
            for (int operation = 0; operation < operations; operation++) counts[random.nextInt(counts.length)]++;
            for (int index = 0; index < counts.length; index++)
                if (counts[index] > 0) merge(ids, amounts, choiceIds[index], Math.multiplyExact(choiceAmount, counts[index]));
        }
        return new Allocation(ids.toIntArray(), Arrays.copyOf(amounts, ids.size()));
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

    private static void merge(IntArrayList ids, long[] amounts, int resource, long value) {
        int index = ids.indexOf(resource);
        if (index < 0) {
            index = ids.size();
            ids.add(resource);
        }
        amounts[index] = Math.addExact(amounts[index], value);
    }

    static final class Allocation {
        final int[] ids;
        final long[] amounts;

        Allocation(int[] ids, long[] amounts) {
            this.ids = ids.clone();
            this.amounts = amounts.clone();
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
