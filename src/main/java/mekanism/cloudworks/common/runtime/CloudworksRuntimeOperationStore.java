package mekanism.cloudworks.common.runtime;

import java.util.ArrayList;
import java.util.List;

/** Sparse independent promises; unused processing capacity allocates no lanes. */
public final class CloudworksRuntimeOperationStore {
    private long version;
    private long[] versions = new long[0];
    private long[] promiseVersions = new long[0];
    private int[] physicalRoots = new int[0];
    private List<CloudworksRuntimeBatch>[] batches = new List[0];
    private CloudworksRuntimeBatch[] selected = new CloudworksRuntimeBatch[0];
    private boolean[] selecting = new boolean[0];
    private boolean[] dirty = new boolean[0];
    public void resize(int count) {
        if (count < 0) throw new IllegalArgumentException("Invalid operation count");
        versions = new long[count]; batches = new List[count];
        promiseVersions = new long[count]; physicalRoots = new int[count];
        selected = new CloudworksRuntimeBatch[count]; selecting = new boolean[count];
        dirty = new boolean[count];
        for (int node = 0; node < count; node++) {
            batches[node] = new ArrayList<>();
            physicalRoots[node] = node;
        }
    }
    /** Bound before restoring any operations for this execution row. */
    void bindPhysicalRoot(int node, int root) {
        if (root < 0 || root > node || !batches[node].isEmpty())
            throw new IllegalArgumentException("Invalid promise output group");
        physicalRoots[node] = root;
    }
    long promiseVersion(int root) { return promiseVersions[root]; }
    private void promisesChanged(int node) { promiseVersions[physicalRoots[node]]++; }
    List<CloudworksRuntimeBatch> batches(int node) { return batches[node]; }
    CloudworksRuntimeBatch current(int node) {
        return selecting[node] ? selected[node] : batches[node].isEmpty() ? null : batches[node].get(0);
    }
    void select(int node, CloudworksRuntimeBatch batch) { selecting[node] = true; selected[node] = batch; }
    void resetSelection(int node) {
        if (dirty[node]) { batches[node].removeIf(batch -> batch.inputs == null); dirty[node] = false; }
        selecting[node] = false; selected[node] = null;
    }
    void changed(int node) { versions[node] = ++version; }
    int occupied(int node) {
        long units = 0;
        for (CloudworksRuntimeBatch batch : batches[node]) units += batch.parallelUnits;
        return (int) Math.min(Integer.MAX_VALUE, units);
    }
    long nextSequence(int node, long next) {
        for (CloudworksRuntimeBatch batch : batches[node])
            if (batch.sequence >= next) next = Math.addExact(batch.sequence, 1);
        return next;
    }
    public int batchCount(int node) { return batches[node].size(); }
    public boolean present(int node) { return current(node) != null && current(node).inputs != null; }
    public long version() { return version; }
    public long version(int node) { return versions[node]; }
    public boolean started(int node) { return present(node) && current(node).started; }
    public boolean completionPrepared(int node) { return present(node) && current(node).completionPrepared; }
    public int operations(int node) { return present(node) ? current(node).operations : 0; }
    public int duration(int node) { return present(node) ? current(node).duration : 0; }
    public String recipeId(int node) { return present(node) ? current(node).recipeId : ""; }
    public double extraEnergyPerTick(int node) { return present(node) ? current(node).extraEnergyPerTick : 0D; }
    public double modulePower(int node) { return present(node) ? current(node).modulePower : 1D; }
    public long lifecycleId(int node) { return present(node) ? current(node).lifecycleId : 0; }
    void setLifecycleId(int node, long value) { current(node).lifecycleId = value; }
    void setModulePower(int node, double value) {
        if (!Double.isFinite(value) || value < 0D) throw new IllegalArgumentException("Invalid module power");
        current(node).modulePower = value; changed(node);
    }
    void setParallelism(int node, int units, long sequence) {
        if (units < 1 || units > operations(node) || sequence < 0) throw new IllegalArgumentException("Invalid parallel batch");
        current(node).parallelUnits = units; current(node).sequence = sequence; changed(node);
    }
    CloudworksRuntimeRecipe.Allocation inputs(int node) { return present(node) ? current(node).inputs : null; }
    CloudworksRuntimeRecipe.Allocation outputs(int node) { return present(node) ? current(node).outputs : null; }
    CloudworksRuntimeRecipe.Allocation chanceOutputs(int node) { return present(node) ? current(node).chanceOutputs : null; }
    void plan(int node, int count, int ticks, CloudworksRuntimeRecipe.Allocation demand,
            CloudworksRuntimeRecipe.Allocation promise) { plan(node, count, ticks, demand, promise, 0D); }
    void plan(int node, int count, int ticks, CloudworksRuntimeRecipe.Allocation demand,
            CloudworksRuntimeRecipe.Allocation promise, double extraEnergy) { plan(node, count, ticks, demand, promise, extraEnergy, ""); }
    void plan(int node, int count, int ticks, CloudworksRuntimeRecipe.Allocation demand,
            CloudworksRuntimeRecipe.Allocation promise, double extraEnergy, String recipeId) {
        if (node < 0 || node >= batches.length || present(node) || count <= 0 || ticks <= 0
                || !Double.isFinite(extraEnergy) || extraEnergy < 0D)
            throw new IllegalArgumentException("Invalid runtime operation");
        CloudworksRuntimeBatch batch = new CloudworksRuntimeBatch();
        batch.operations = batch.parallelUnits = count; batch.duration = ticks;
        batch.inputs = demand; batch.outputs = promise; batch.chanceOutputs = chanceAllocation(promise);
        batch.extraEnergyPerTick = extraEnergy; batch.recipeId = recipeId == null ? "" : recipeId;
        batches[node].add(batch); select(node, batch); changed(node);
        promisesChanged(node);
    }
    void restore(int node, int count, int ticks, CloudworksRuntimeRecipe.Allocation demand,
            CloudworksRuntimeRecipe.Allocation promise) { restore(node, count, ticks, demand, promise, 0D); }
    void restore(int node, int count, int ticks, CloudworksRuntimeRecipe.Allocation demand,
            CloudworksRuntimeRecipe.Allocation promise, double extraEnergy) { restore(node, count, ticks, demand, promise, extraEnergy, false); }
    void restore(int node, int count, int ticks, CloudworksRuntimeRecipe.Allocation demand,
            CloudworksRuntimeRecipe.Allocation promise, double extraEnergy, boolean prepared) {
        restore(node, count, ticks, demand, promise, extraEnergy, prepared, "");
    }
    void restore(int node, int count, int ticks, CloudworksRuntimeRecipe.Allocation demand,
            CloudworksRuntimeRecipe.Allocation promise, double extraEnergy, boolean prepared, String recipeId) {
        restore(node, count, ticks, demand, promise, chanceAllocation(promise), extraEnergy, prepared, recipeId);
    }
    void restore(int node, int count, int ticks, CloudworksRuntimeRecipe.Allocation demand,
            CloudworksRuntimeRecipe.Allocation promise, CloudworksRuntimeRecipe.Allocation chance,
            double extraEnergy, boolean prepared, String recipeId) {
        plan(node, count, ticks, demand, promise, extraEnergy, recipeId);
        current(node).chanceOutputs = chance; current(node).started = true;
        current(node).completionPrepared = prepared; changed(node);
    }
    void prepareCompletion(int node) {
        if (!started(node) || completionPrepared(node)) return;
        current(node).completionPrepared = true; changed(node);
    }
    void replaceOutputs(int node, CloudworksRuntimeRecipe.Allocation promise) {
        if (!started(node) || promise == null) throw new IllegalStateException("Invalid completion output replacement");
        current(node).outputs = promise; current(node).chanceOutputs = chanceAllocation(promise); changed(node);
        promisesChanged(node);
    }
    void start(int node, int inventory, CloudworksRuntimeResourceStore stock) {
        var demand = inputs(node);
        for (int i = 0; i < demand.ids.length; i++)
            if (stock.available(inventory, demand.ids[i]) < demand.amounts[i]) throw new IllegalStateException("Runtime reservation no longer covered");
        for (int i = 0; i < demand.ids.length; i++)
            if (!stock.reserve(inventory, demand.ids[i], demand.amounts[i])) throw new IllegalStateException("Runtime reservation failed");
        current(node).started = true; changed(node);
    }
    void complete(int node, int input, int output, CloudworksRuntimeResourceStore stock) {
        if (!started(node)) throw new IllegalStateException("Unstarted runtime operation");
        var demand = inputs(node); var promise = outputs(node);
        for (int i = 0; i < demand.ids.length; i++)
            if (stock.reserved(input, demand.ids[i]) < demand.amounts[i]) throw new IllegalStateException("Runtime completion reservation missing");
        long produced = 0;
        for (int i = 0; i < promise.ids.length; i++) {
            Math.addExact(stock.amount(output, promise.ids[i]), promise.amounts[i]);
            produced = Math.addExact(produced, promise.amounts[i]);
        }
        Math.addExact(stock.total(output), produced);
        for (int i = 0; i < demand.ids.length; i++) stock.consumeReserved(input, demand.ids[i], demand.amounts[i]);
        for (int i = 0; i < promise.ids.length; i++) stock.add(output, promise.ids[i], promise.amounts[i]);
        clear(node);
    }
    void clear(int node) {
        var batch = current(node);
        if (batch == null) return;
        promisesChanged(node);
        batch.inputs = batch.outputs = null; dirty[node] = true;
        select(node, null); changed(node);
    }
    void cancel(int node, int inventory, CloudworksRuntimeResourceStore stock) {
        if (started(node)) {
            var demand = inputs(node);
            for (int i = 0; i < demand.ids.length; i++) stock.release(inventory, demand.ids[i], demand.amounts[i]);
        }
        clear(node);
    }
    private static CloudworksRuntimeRecipe.Allocation chanceAllocation(CloudworksRuntimeRecipe.Allocation source) {
        if (source == null) return null;
        int count = 0;
        for (boolean chance : source.chance) if (chance) count++;
        int[] ids = new int[count]; long[] amounts = new long[count]; int i = 0;
        for (int index = 0; index < source.ids.length; index++) if (source.chance[index]) {
            ids[i] = source.ids[index]; amounts[i++] = source.amounts[index];
        }
        return new CloudworksRuntimeRecipe.Allocation(ids, amounts, new boolean[count]);
    }
}
