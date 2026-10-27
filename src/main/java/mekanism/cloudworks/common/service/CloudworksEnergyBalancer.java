package mekanism.cloudworks.common.service;

import mekanism.cloudworks.common.data.CloudworksData;
import mekanism.cloudworks.common.data.ProductionLineData;

import java.util.*;

/**
 * Deterministic, transactional energy settlement for all lines of one cloud.
 */
public final class CloudworksEnergyBalancer {
    private static final double EPSILON = 1E-9D;

    private CloudworksEnergyBalancer() {
    }

    public static Result settle(CloudworksData cloud, List<ProductionLineData> lines,
                                List<Double> loads, List<Double> generation) {
        if (lines.size() != loads.size() || lines.size() != generation.size())
            throw new IllegalArgumentException("energy vectors must have equal lengths");
        int count = lines.size();
        Set<UUID> ids = new HashSet<>();
        double[] demand = new double[count];
        double[] pools = new double[count];
        double[] capacities = new double[count];
        double[] produced = new double[count];
        for (int i = 0; i < count; i++) {
            ProductionLineData line = lines.get(i);
            if (!cloud.owner().equals(line.owner()) || !ids.add(line.lineId()))
                throw new IllegalArgumentException("Invalid energy line");
            demand[i] = finiteNonnegative(loads.get(i));
            pools[i] = finiteNonnegative(line.energy());
            capacities[i] = finiteNonnegative(line.maxEnergy());
            produced[i] = finiteNonnegative(generation.get(i));
        }
        Settlement settlement = calculate(cloud.energy(), cloud.maxEnergy(), cloud.unlimitedEnergy(),
                pools, capacities, demand, produced);
        cloud.setEnergy(settlement.cloudEnergy(), cloud.maxEnergy(), cloud.unlimitedEnergy());
        for (int i = 0; i < count; i++) lines.get(i).setEnergy(settlement.lineEnergy(i), capacities[i]);
        return new Result(settlement.powered(), settlement.load(), settlement.generation());
    }

    /**
     * Pure numerical calculation shared by the server models and detached worker state.
     */
    public static Settlement calculate(double cloudEnergy, double cloudCapacity, boolean unlimited,
                                       double[] lineEnergy, double[] lineCapacity, double[] loads, double[] generation) {
        if (lineEnergy == null || lineCapacity == null || loads == null || generation == null
                || lineEnergy.length != lineCapacity.length || lineEnergy.length != loads.length
                || lineEnergy.length != generation.length)
            throw new IllegalArgumentException("energy vectors must have equal lengths");
        if (Double.isNaN(cloudCapacity) || cloudCapacity < 0D
                || (!unlimited && !Double.isFinite(cloudCapacity))
                || (!unlimited && (!Double.isFinite(cloudEnergy) || cloudEnergy < 0D || cloudEnergy > cloudCapacity)))
            throw new IllegalArgumentException("Invalid cloud energy");
        int count = loads.length;
        double[] demand = new double[count], pools = new double[count], capacities = new double[count];
        double generated = 0D;
        for (int i = 0; i < count; i++) {
            demand[i] = finiteNonnegative(loads[i]);
            pools[i] = finiteNonnegative(lineEnergy[i]);
            capacities[i] = finiteNonnegative(lineCapacity[i]);
            if (pools[i] > capacities[i]) throw new IllegalArgumentException("Line energy exceeds capacity");
            generated = addFinite(generated, finiteNonnegative(generation[i]));
        }
        double availableCloud = unlimited ? Double.POSITIVE_INFINITY : cloudEnergy;
        double[] generationAllocation = fairAllocation(demand, generated);
        double[] afterGeneration = remaining(demand, generationAllocation);
        double[] cloudAllocation = unlimited ? afterGeneration.clone() : fairAllocation(afterGeneration, availableCloud);
        double[] localNeed = remaining(afterGeneration, cloudAllocation);
        boolean[] powered = new boolean[count];
        double generatedUsed = 0D, cloudUsed = 0D;
        for (int i = 0; i < count; i++) {
            powered[i] = localNeed[i] <= pools[i] + EPSILON;
            if (powered[i]) {
                generatedUsed = addFinite(generatedUsed, generationAllocation[i]);
                if (!unlimited) cloudUsed = addFinite(cloudUsed, cloudAllocation[i]);
                pools[i] = Math.max(0D, pools[i] - localNeed[i]);
            }
        }

        // Energy assigned to a line that cannot pay its local part is not consumed.
        double surplus = Math.max(0D, generated - generatedUsed);
        double[] refill = new double[count];
        for (int i = 0; i < count; i++)
            if (demand[i] > EPSILON || generation[i] > EPSILON) {
                double room = Math.max(0D, capacities[i] - pools[i]);
                refill[i] = room;
            }
        double[] accepted = fairAllocation(refill, surplus);
        double acceptedTotal = 0D;
        for (int i = 0; i < count; i++) {
            pools[i] = Math.min(capacities[i], pools[i] + accepted[i]);
            acceptedTotal += accepted[i];
        }
        surplus = Math.max(0D, surplus - acceptedTotal);
        double nextCloud = unlimited ? 0D : Math.max(0D, availableCloud - cloudUsed);
        if (!unlimited) nextCloud = Math.min(cloudCapacity, nextCloud + surplus);

        double totalLoad = 0D;
        for (double value : demand) totalLoad += value;
        return new Settlement(powered, pools, nextCloud, totalLoad, generated);
    }

    /**
     * Worker entry point; no live cloud, machine or codec object is passed.
     */
    public static Settlement calculate(CloudworksDetachedState.View view,
                                       double[] loads, double[] generation) {
        double[] pools = new double[view.lines.size()], capacities = new double[view.lines.size()];
        for (int index = 0; index < pools.length; index++) {
            pools[index] = view.lines.get(index).energy;
            capacities[index] = view.lines.get(index).maxEnergy;
        }
        return calculate(view.energy, view.maxEnergy, view.unlimitedEnergy,
                pools, capacities, loads, generation);
    }

    private static double[] remaining(double[] total, double[] used) {
        double[] result = new double[total.length];
        for (int i = 0; i < total.length; i++) result[i] = Math.max(0D, total[i] - used[i]);
        return result;
    }

    /**
     * Equal-share allocator capped by each demand; returns one allocation per entry.
     */
    private static double[] fairAllocation(double[] demands, double available) {
        double[] remaining = demands.clone();
        double[] allocation = new double[demands.length];
        while (available > EPSILON) {
            int active = 0;
            for (double value : remaining) if (value > EPSILON) active++;
            if (active == 0) break;
            double share = available / active;
            double used = 0D;
            for (int i = 0; i < remaining.length; i++) {
                if (remaining[i] <= EPSILON) continue;
                double take = Math.min(remaining[i], share);
                allocation[i] += take;
                remaining[i] -= take;
                used += take;
            }
            if (used <= EPSILON) break;
            available -= used;
        }
        return allocation;
    }

    private static double finiteNonnegative(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0D)
            throw new IllegalArgumentException("Invalid energy value");
        return value;
    }

    private static double addFinite(double first, double second) {
        double value = first + second;
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Energy overflow");
        return value;
    }

    public static final class Result {
        private final List<Boolean> powered;
        private final double load;
        private final double generation;

        private Result(List<Boolean> powered, double load, double generation) {
            this.powered = Collections.unmodifiableList(new ArrayList<>(powered));
            this.load = load;
            this.generation = generation;
        }

        public List<Boolean> powered() {
            return powered;
        }

        public double load() {
            return load;
        }

        public double generation() {
            return generation;
        }
    }

    public static final class Settlement {
        private final boolean[] powered;
        private final double[] lineEnergy;
        private final double cloudEnergy, load, generation;

        private Settlement(boolean[] powered, double[] lineEnergy, double cloudEnergy,
                           double load, double generation) {
            this.powered = powered.clone();
            this.lineEnergy = lineEnergy.clone();
            this.cloudEnergy = cloudEnergy;
            this.load = load;
            this.generation = generation;
        }

        public List<Boolean> powered() {
            return Collections.unmodifiableList(new AbstractList<Boolean>() {
                @Override public Boolean get(int index) { return powered[index]; }
                @Override public int size() { return powered.length; }
            });
        }

        public boolean powered(int index) { return powered[index]; }

        public double lineEnergy(int index) {
            return lineEnergy[index];
        }

        public double cloudEnergy() {
            return cloudEnergy;
        }

        public double load() {
            return load;
        }

        public double generation() {
            return generation;
        }
    }
}
