package mekanism.cloudworks.common.runtime;

import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.cloudworks.common.data.CloudworksThermalState;

/** Primitive arrays owned by one Runtime, including prepaid fuel and fusion plasma. */
public final class CloudworksRuntimeThermalStore {
    private double[] heat = new double[0], capacity = new double[0], plasma = new double[0],
            fuel = new double[0], credit = new double[0], produced = new double[0], consumed = new double[0], loss = new double[0];
    private long[] versions = new long[0];
    private boolean[] burning = new boolean[0];
    private long version;

    void resize(int size, boolean present) {
        if (!present) size = 0;
        heat = new double[size]; capacity = new double[size]; plasma = new double[size];
        fuel = new double[size]; credit = new double[size]; produced = new double[size];
        consumed = new double[size]; loss = new double[size]; burning = new boolean[size]; versions = new long[size]; version = 0;
    }
    void restore(int node, CloudworksThermalPlan plan, long count, CloudworksThermalState state) {
        if (plan == null) return;
        capacity[node] = finite(plan.capacity * count);
        if (state == null) {
            heat[node] = finite(300 * capacity[node]);
            plasma[node] = finite(300 * plan.plasmaCapacity * count);
        } else {
            heat[node] = Math.max(0, finite(state.heat + 300 * Math.max(0, capacity[node] - state.capacity))
                    - 300 * Math.max(0, state.capacity - capacity[node]));
            plasma[node] = state.plasma; fuel[node] = state.fuelTicks;
            credit[node] = state.credit; burning[node] = state.burning;
        }
        touch(node);
    }
    public long version() { return version; }
    public long version(int node) { return versions.length == 0 ? 0 : versions[node]; }
    boolean present() { return capacity.length > 0; }
    public double heat(int node) { return heat[node]; }
    public double capacity(int node) { return capacity[node]; }
    public double temperature(int node) { return capacity[node] == 0 ? 300 : heat[node] / capacity[node]; }
    public double plasma(int node) { return plasma[node]; }
    public double fuel(int node) { return fuel[node]; }
    public double credit(int node) { return credit[node]; }
    public boolean burning(int node) { return burning[node]; }
    public void setPlasma(int node, double value) { finite(value); if (plasma[node] != value) { plasma[node] = value; touch(node); } }
    void setFuel(int node, double value) { finite(value); if (fuel[node] != value) { fuel[node] = value; touch(node); } }
    void setCredit(int node, double value) { finite(value); if (credit[node] != value) { credit[node] = value; touch(node); } }
    void setBurning(int node, boolean value) { if (burning[node] != value) { burning[node] = value; touch(node); } }
    void metrics(int node, double production, double consumption, double environmentLoss) {
        if (produced[node] != production || consumed[node] != consumption || loss[node] != environmentLoss) touch(node);
        produced[node] = finite(production); consumed[node] = finite(consumption); loss[node] = finite(environmentLoss);
    }
    public void add(int node, double amount) { if (amount > 0) { heat[node] = finite(heat[node] + amount); touch(node); } }
    public double take(int node, double amount, double minimumTemperature) {
        double available = Math.max(0, heat[node] - minimumTemperature * capacity[node]);
        double taken = Math.min(finite(amount), available);
        if (taken > 0) { heat[node] -= taken; touch(node); }
        return taken;
    }
    CloudworksThermalState snapshot(int node) {
        if (capacity.length == 0 || capacity[node] == 0) return null;
        return new CloudworksThermalState(heat[node], capacity[node], plasma[node], fuel[node], credit[node],
                produced[node], consumed[node], loss[node], burning[node]);
    }
    private void touch(int node) { versions[node] = ++version; }
    static double finite(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalStateException("Thermal arithmetic overflow");
        return value;
    }
}
