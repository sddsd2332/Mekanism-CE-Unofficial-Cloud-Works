package mekanism.cloudworks.common.runtime;

import java.util.Arrays;

/** Persistent promises, separate from inputs that remain reserved until completion. */
public final class CloudworksRuntimeOperationStore {
    private int size;
    private long version;
    private long[] versions = new long[16];
    private boolean[] started = new boolean[16];
    private int[] operations = new int[16], duration = new int[16];
    private double[] extraEnergyPerTick = new double[16];
    private CloudworksRuntimeRecipe.Allocation[] inputs = new CloudworksRuntimeRecipe.Allocation[16];
    private CloudworksRuntimeRecipe.Allocation[] outputs = new CloudworksRuntimeRecipe.Allocation[16];

    public void resize(int count) {
        if (count < 0) throw new IllegalArgumentException("Invalid operation count");
        int capacity = Math.max(count, inputs.length);
        if (capacity > inputs.length) {
            started = Arrays.copyOf(started, capacity);
            versions = Arrays.copyOf(versions, capacity);
            operations = Arrays.copyOf(operations, capacity);
            duration = Arrays.copyOf(duration, capacity);
            extraEnergyPerTick = Arrays.copyOf(extraEnergyPerTick, capacity);
            inputs = Arrays.copyOf(inputs, capacity);
            outputs = Arrays.copyOf(outputs, capacity);
        }
        for (int index = 0; index < Math.max(size, count); index++) clear(index);
        size = count;
    }

    public boolean present(int node) { return inputs[node] != null; }
    public long version() { return version; }
    public long version(int node) { return versions[node]; }
    public boolean started(int node) { return started[node]; }
    public int operations(int node) { return operations[node]; }
    public int duration(int node) { return duration[node]; }
    public double extraEnergyPerTick(int node) { return extraEnergyPerTick[node]; }
    CloudworksRuntimeRecipe.Allocation inputs(int node) { return inputs[node]; }
    CloudworksRuntimeRecipe.Allocation outputs(int node) { return outputs[node]; }

    void plan(int node, int count, int ticks, CloudworksRuntimeRecipe.Allocation demand,
            CloudworksRuntimeRecipe.Allocation promise) {
        plan(node, count, ticks, demand, promise, 0D);
    }

    void plan(int node, int count, int ticks, CloudworksRuntimeRecipe.Allocation demand,
            CloudworksRuntimeRecipe.Allocation promise, double extraEnergy) {
        if (node < 0 || node >= size || present(node) || count <= 0 || ticks <= 0)
            throw new IllegalArgumentException("Invalid runtime operation");
        if (!Double.isFinite(extraEnergy) || extraEnergy < 0D)
            throw new IllegalArgumentException("Invalid runtime operation energy");
        operations[node] = count;
        duration[node] = ticks;
        inputs[node] = demand;
        outputs[node] = promise;
        extraEnergyPerTick[node] = extraEnergy;
        started[node] = false;
    }

    void restore(int node, int count, int ticks, CloudworksRuntimeRecipe.Allocation demand,
            CloudworksRuntimeRecipe.Allocation promise) {
        restore(node, count, ticks, demand, promise, 0D);
    }

    void restore(int node, int count, int ticks, CloudworksRuntimeRecipe.Allocation demand,
            CloudworksRuntimeRecipe.Allocation promise, double extraEnergy) {
        plan(node, count, ticks, demand, promise, extraEnergy);
        started[node] = true;
        versions[node] = ++version;
    }

    void start(int node, int inventory, CloudworksRuntimeResourceStore stock) {
        CloudworksRuntimeRecipe.Allocation demand = inputs[node];
        for (int index = 0; index < demand.ids.length; index++)
            if (stock.available(inventory, demand.ids[index]) < demand.amounts[index])
                throw new IllegalStateException("Runtime reservation no longer covered");
        for (int index = 0; index < demand.ids.length; index++)
            if (!stock.reserve(inventory, demand.ids[index], demand.amounts[index]))
                throw new IllegalStateException("Runtime reservation failed");
        started[node] = true;
        versions[node] = ++version;
    }

    void complete(int node, int input, int output, CloudworksRuntimeResourceStore stock) {
        if (!started[node]) throw new IllegalStateException("Unstarted runtime operation");
        CloudworksRuntimeRecipe.Allocation demand = inputs[node], promise = outputs[node];
        for (int index = 0; index < demand.ids.length; index++)
            if (stock.reserved(input, demand.ids[index]) < demand.amounts[index])
                throw new IllegalStateException("Runtime completion reservation missing");
        long produced = 0;
        for (int index = 0; index < promise.ids.length; index++) {
            Math.addExact(stock.amount(output, promise.ids[index]), promise.amounts[index]);
            produced = Math.addExact(produced, promise.amounts[index]);
        }
        Math.addExact(stock.total(output), produced);
        for (int index = 0; index < demand.ids.length; index++)
            stock.consumeReserved(input, demand.ids[index], demand.amounts[index]);
        for (int index = 0; index < promise.ids.length; index++)
            stock.add(output, promise.ids[index], promise.amounts[index]);
        clear(node);
    }

    void clear(int node) {
        if (started[node]) versions[node] = ++version;
        inputs[node] = outputs[node] = null;
        operations[node] = duration[node] = 0;
        extraEnergyPerTick[node] = 0D;
        started[node] = false;
    }
}
