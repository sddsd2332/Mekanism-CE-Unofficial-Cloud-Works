package mekanism.cloudworks.common.runtime;

/** Reusable exact-boundary power checks for one line's batch search. */
final class CloudworksRuntimePowerBudget {
    private double[] loads;
    private double[] generation;
    private CloudworksRuntimeEnergyStore energy;
    private int targetLine = -1;
    private int lineCount;
    private double otherDeficit;
    private boolean prepared;

    /** Captures the unchanged plan-time power state, excluding the candidate's line. */
    void prepare(int targetLine, double[] loads, double[] generation, CloudworksRuntimeEnergyStore energy) {
        if (targetLine < 0 || targetLine >= loads.length || generation.length != loads.length
                || energy.lineCount() != loads.length) throw new IllegalArgumentException("Invalid runtime power budget");
        this.loads = loads;
        this.generation = generation;
        this.energy = energy;
        this.targetLine = targetLine;
        lineCount = loads.length;
        otherDeficit = 0D;
        prepared = !energy.unlimited();
        if (!prepared) return;
        for (int line = 0; line < lineCount; line++) {
            if (line == targetLine) continue;
            double lineDeficit = Math.max(0D, loads[line] - energy.lineEnergy(line) - generation[line]);
            otherDeficit += lineDeficit;
        }
    }

    boolean targets(int line) {
        return energy != null && targetLine == line;
    }

    /** Estimates ordinary probes in O(1), then uses the original summation at the boundary. */
    boolean canPay(double additional) {
        if (energy == null) throw new IllegalStateException("Runtime power budget is not prepared");
        if (energy.unlimited()) return true;
        if (!prepared) return canPayExact(additional);
        double candidateLoad = loads[targetLine] + additional;
        double candidateDeficit = Math.max(0D, candidateLoad - energy.lineEnergy(targetLine) - generation[targetLine]);
        double estimate = otherDeficit + candidateDeficit;
        double cloudEnergy = energy.cloudEnergy();
        if (!Double.isFinite(estimate) || nearBoundary(estimate, cloudEnergy)) return canPayExact(additional);
        return estimate <= cloudEnergy;
    }

    /** Same ordered accumulation as Runtime's original admission predicate. */
    boolean canPayExact(double additional) {
        if (energy.unlimited()) return true;
        double deficit = 0D;
        for (int line = 0; line < lineCount; line++) {
            double load = loads[line] + (line == targetLine ? additional : 0D);
            deficit += Math.max(0D, load - energy.lineEnergy(line) - generation[line]);
        }
        return Double.isFinite(deficit) && deficit <= energy.cloudEnergy();
    }

    /**
     * Estimates the largest count allowed by this line's piecewise-linear processing load.
     * The caller must validate the estimate and its next count with the original predicate;
     * unusual floating-point boundaries return -1 so the caller can use its normal search.
     */
    int maximumBatchCandidate(int maximum, int rate, double basePerUnit, double extraPerOperation,
            double modulePower) {
        if (!prepared || maximum < 0 || rate < 1 || !Double.isFinite(basePerUnit) || basePerUnit < 0D
                || !Double.isFinite(extraPerOperation) || extraPerOperation < 0D
                || !Double.isFinite(modulePower) || modulePower < 0D) return -1;
        if (maximum == 0) return 0;
        double remainingCloud = energy.cloudEnergy() - otherDeficit;
        if (!Double.isFinite(remainingCloud)) return -1;
        if (remainingCloud < 0D) return 0;
        double currentLineDeficit = loads[targetLine] - energy.lineEnergy(targetLine) - generation[targetLine];
        double headroom = remainingCloud - currentLineDeficit;
        if (!Double.isFinite(headroom)) return -1;
        if (headroom < 0D) return 0;

        double baseCost = basePerUnit * modulePower;
        double extraCost = extraPerOperation * modulePower;
        if (!Double.isFinite(baseCost) || !Double.isFinite(extraCost)) return -1;
        if (baseCost == 0D && extraCost == 0D) return maximum;

        long maximumUnits = ((long) maximum + rate - 1L) / rate;
        if (extraCost == 0D) {
            double units = Math.floor(headroom / baseCost);
            if (!Double.isFinite(units) || units >= maximumUnits) return maximum;
            long allowedUnits = Math.max(0L, (long) units);
            return (int) Math.min(maximum, allowedUnits * (long) rate);
        }
        if (baseCost == 0D) {
            double count = Math.floor(headroom / extraCost);
            if (!Double.isFinite(count) || count >= maximum) return maximum;
            return Math.max(0, (int) count);
        }

        double denominator = baseCost + extraCost * rate;
        double numerator = headroom + extraCost * (rate - 1D);
        if (!Double.isFinite(denominator) || denominator <= 0D || !Double.isFinite(numerator)) return -1;
        double unitsEstimate = Math.floor(numerator / denominator);
        if (!Double.isFinite(unitsEstimate)) return -1;
        if (unitsEstimate <= 0D) return 0;
        long units = Math.min(maximumUnits, unitsEstimate >= maximumUnits ? maximumUnits : (long) unitsEstimate);
        long minimumCountForUnits = (units - 1L) * rate + 1L;
        long maximumCountForUnits = Math.min((long) maximum, units * (long) rate);
        double remaining = headroom - baseCost * units;
        if (!Double.isFinite(remaining)) return -1;
        if (remaining < 0D) return -1;
        double countEstimate = Math.floor(remaining / extraCost);
        if (!Double.isFinite(countEstimate)) return -1;
        long count = Math.min(maximumCountForUnits,
                countEstimate >= maximumCountForUnits ? maximumCountForUnits : (long) countEstimate);
        if (count < minimumCountForUnits) return -1;
        return (int) count;
    }

    private boolean nearBoundary(double estimate, double cloudEnergy) {
        double scale = Math.max(1D, Math.max(Math.abs(estimate), Math.abs(cloudEnergy)));
        double tolerance = Math.ulp(scale) * (lineCount + 8D) * 8D;
        return !Double.isFinite(tolerance) || Math.abs(estimate - cloudEnergy) <= tolerance;
    }
}
