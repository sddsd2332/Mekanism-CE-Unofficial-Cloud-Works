package mekanism.cloudworks.api.work;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import mekanism.api.qio.resource.QIOResourceDescriptor;

/** Immutable numerical and cost contribution returned by one module source. */
public final class WorkContribution {
    private static final WorkContribution NEUTRAL = new WorkContribution(1D, 1D, 1D, 1D,
            Collections.emptyList());
    private final double durationMultiplier;
    private final double energyPerTickMultiplier;
    private final double capacityMultiplier;
    private final double capacityAddition;
    private final double secondaryChanceMultiplier;
    private final List<ResourceCost> costs;

    public WorkContribution(double duration, double energy, double capacity, double chance,
            List<ResourceCost> costs) {
        this(duration, energy, capacity, chance, costs, 0D);
    }
    private WorkContribution(double duration, double energy, double capacity, double chance,
            List<ResourceCost> costs, double addition) {
        validate(duration); validate(energy); validate(capacity); validate(chance);
        this.durationMultiplier = duration;
        this.energyPerTickMultiplier = energy;
        this.capacityMultiplier = capacity;
        validate(addition); this.capacityAddition = addition;
        this.secondaryChanceMultiplier = chance;
        this.costs = Collections.unmodifiableList(new ArrayList<>(costs));
    }

    public static WorkContribution neutral() { return NEUTRAL; }
    public static Builder builder() { return new Builder(); }
    public double durationMultiplier() { return durationMultiplier; }
    public double energyPerTickMultiplier() { return energyPerTickMultiplier; }
    public double capacityMultiplier() { return capacityMultiplier; }
    public double capacityAddition() { return capacityAddition; }
    public double secondaryChanceMultiplier() { return secondaryChanceMultiplier; }
    public List<ResourceCost> costs() { return costs; }

    private static void validate(double value) {
        if (!Double.isFinite(value) || value < 0D) throw new IllegalArgumentException("Invalid work multiplier");
    }

    public static final class Builder {
        private double duration = 1D, energy = 1D, capacity = 1D, chance = 1D;
        private double addition;
        private final List<ResourceCost> costs = new ArrayList<>();
        public Builder durationMultiplier(double value) { duration = value; return this; }
        public Builder energyPerTickMultiplier(double value) { energy = value; return this; }
        public Builder capacityMultiplier(double value) { capacity = value; return this; }
        public Builder capacityAddition(double joules) { addition = joules; return this; }
        public Builder secondaryChanceMultiplier(double value) { chance = value; return this; }
        public Builder cost(ResourceCost value) { costs.add(Objects.requireNonNull(value)); return this; }
        public WorkContribution build() { return new WorkContribution(duration, energy, capacity, chance, costs, addition); }
    }

    public static final class ResourceCost {
        public enum Unit { PER_OPERATION, PER_BASE_MACHINE_TICK }
        private final QIOResourceDescriptor resource;
        private final double amount;
        private final Unit unit;

        public ResourceCost(QIOResourceDescriptor resource, double amount, Unit unit) {
            if (resource == null || !resource.isResolved() || !Double.isFinite(amount) || amount < 0D)
                throw new IllegalArgumentException("Invalid module cost");
            this.resource = resource;
            this.amount = amount;
            this.unit = Objects.requireNonNull(unit);
        }
        public QIOResourceDescriptor resource() { return resource; }
        public double amount() { return amount; }
        public Unit unit() { return unit; }
    }
}
