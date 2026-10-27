package mekanism.cloudworks.common.runtime;

import java.util.SplittableRandom;

/** Native StatUtils/PoissonSampler numerical distribution with an operation-local RNG. */
final class CloudworksNativeSecondaryBudget {
    private CloudworksNativeSecondaryBudget() {}
    static long sample(double mean, int ticks, long seed) {
        if (!Double.isFinite(mean) || mean < 0) throw new IllegalArgumentException("Invalid secondary usage mean");
        SplittableRandom random = new SplittableRandom(seed);
        double exp = Math.exp(mean), coefficient = 1D / Math.sqrt(2D * Math.PI);
        long total = 0;
        for (int tick = 0; tick < ticks; tick++) {
            double target = random.nextDouble() * exp, cumulative = 1;
            int sample = 0;
            while (cumulative < target && sample < 3D * Math.ceil(mean)) {
                sample++;
                cumulative += coefficient / Math.sqrt(sample) * Math.pow(mean * Math.E / sample, sample);
            }
            total = Math.addExact(total, sample);
        }
        return total;
    }
}
