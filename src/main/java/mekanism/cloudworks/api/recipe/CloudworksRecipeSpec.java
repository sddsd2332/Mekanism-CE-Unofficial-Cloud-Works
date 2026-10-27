package mekanism.cloudworks.api.recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import mekanism.api.qio.resource.QIOResourceDescriptor;

/** Immutable, callback-free recipe contract safe to hand to CloudWorks Runtime. */
public final class CloudworksRecipeSpec {
    private final String id;
    private final int duration;
    private final int operationsPerCycle;
    private final double energyPerInput;
    private final long unitsPerOutput, prepaidFuelUnits;
    private final QIOResourceDescriptor creditResource;
    private final boolean secondaryGasInputs;
    private final boolean secondaryFluidInputs;
    private final int nativeSecondaryMode;
    private final double extraEnergyPerTick, energyPerOperation;
    private final List<Ingredient> inputs, configuration;
    private final List<Output> outputs, chanceOutputs;
    private final List<QIOResourceDescriptor> choiceOutputs;
    private final long choiceAmount;
    private final boolean sunlightRequired, outputMultiplierEnabled;
    private final long decayAmount;
    private final net.minecraft.util.ResourceLocation requiredEnvironmentAxis;
    private final String selectionGroup;
    private final int selectionPriority;

    private CloudworksRecipeSpec(Builder builder) {
        id = builder.id;
        duration = builder.duration;
        operationsPerCycle = builder.operationsPerCycle;
        energyPerInput = builder.energyPerInput; unitsPerOutput = builder.unitsPerOutput;
        prepaidFuelUnits = builder.prepaidFuelUnits; creditResource = builder.creditResource;
        secondaryGasInputs = builder.secondaryGasInputs;
        secondaryFluidInputs = builder.secondaryFluidInputs;
        nativeSecondaryMode = builder.nativeSecondaryMode;
        extraEnergyPerTick = builder.extraEnergyPerTick;
        energyPerOperation = builder.energyPerOperation;
        inputs = Collections.unmodifiableList(new ArrayList<>(builder.inputs));
        configuration = Collections.unmodifiableList(new ArrayList<>(builder.configuration));
        outputs = Collections.unmodifiableList(new ArrayList<>(builder.outputs));
        chanceOutputs = Collections.unmodifiableList(new ArrayList<>(builder.chanceOutputs));
        choiceOutputs = Collections.unmodifiableList(new ArrayList<>(builder.choiceOutputs));
        choiceAmount = builder.choiceAmount;
        sunlightRequired = builder.sunlightRequired;
        outputMultiplierEnabled = builder.outputMultiplierEnabled;
        decayAmount = builder.decayAmount;
        requiredEnvironmentAxis = builder.requiredEnvironmentAxis; selectionGroup = builder.selectionGroup; selectionPriority = builder.selectionPriority;
        if (duration < 0 || !Double.isFinite(extraEnergyPerTick) || extraEnergyPerTick < 0
                || !Double.isFinite(energyPerOperation) || energyPerOperation < 0)
            throw new IllegalArgumentException("Invalid frozen recipe contract");
        if (energyPerInput > 0 || prepaidFuelUnits > 0) {
            if (inputs.isEmpty() || !chanceOutputs.isEmpty()
                    || decayAmount >= 0 || secondaryGasInputs || secondaryFluidInputs)
                throw new IllegalArgumentException("Continuous contracts require an exact primary input and no auxiliary work contract");
            if (energyPerInput > 0 && (inputs.size() != 1 || !configuration.isEmpty() || inputs.get(0).matcher().alternatives().size() != 1
                    || prepaidFuelUnits > 0 || energyPerOperation != 0 || outputs.size() != 1 || !choiceOutputs.isEmpty()))
                throw new IllegalArgumentException("Invalid power-driven conversion output");
            if (prepaidFuelUnits > 0 && (extraEnergyPerTick != 0
                    || energyPerOperation > 0 && (!outputs.isEmpty() || !choiceOutputs.isEmpty())
                    || energyPerOperation == 0 && outputs.isEmpty() && choiceOutputs.isEmpty()))
                throw new IllegalArgumentException("Prepaid fuel must produce either energy or resources");
        }
    }

    public static Builder builder(String id) { return new Builder(id); }
    public String id() { return id; }
    public int duration() { return duration; }
    public int operationsPerCycle() { return operationsPerCycle; }
    public double energyPerInput() { return energyPerInput; }
    public long unitsPerOutput() { return unitsPerOutput; }
    public long prepaidFuelUnits() { return prepaidFuelUnits; }
    public QIOResourceDescriptor creditResource() { return creditResource; }
    public boolean secondaryGasInputs() { return secondaryGasInputs; }
    public boolean secondaryFluidInputs() { return secondaryFluidInputs; }
    public int nativeSecondaryMode() { return nativeSecondaryMode; }
    public double extraEnergyPerTick() { return extraEnergyPerTick; }
    public double energyPerOperation() { return energyPerOperation; }
    public List<Ingredient> inputs() { return inputs; }
    public List<Ingredient> configuration() { return configuration; }
    public List<Output> outputs() { return outputs; }
    public List<Output> chanceOutputs() { return chanceOutputs; }
    public List<QIOResourceDescriptor> choiceOutputs() { return choiceOutputs; }
    public long choiceAmount() { return choiceAmount; }
    public boolean sunlightRequired() { return sunlightRequired; }
    public boolean outputMultiplierEnabled() { return outputMultiplierEnabled; }
    public long decayAmount() { return decayAmount; }
    public net.minecraft.util.ResourceLocation requiredEnvironmentAxis() { return requiredEnvironmentAxis; }
    public String selectionGroup() { return selectionGroup; }
    public int selectionPriority() { return selectionPriority; }

    /**
     * Creates a lifecycle-only editor that can remove or rewrite existing
     * inputs and outputs without accidentally duplicating the source contract.
     */
    public CloudworksRecipeEditor editor(String derivedId) {
        return CloudworksRecipeEditor.from(this, derivedId);
    }

    /** Copies the complete immutable contract before adding a module's ingredients or outputs. */
    public Builder toBuilder(String derivedId) {
        Builder builder = new Builder(derivedId);
        builder.duration = duration;
        builder.operationsPerCycle = operationsPerCycle;
        builder.energyPerInput = energyPerInput; builder.unitsPerOutput = unitsPerOutput;
        builder.prepaidFuelUnits = prepaidFuelUnits; builder.creditResource = creditResource;
        builder.secondaryGasInputs = secondaryGasInputs;
        builder.secondaryFluidInputs = secondaryFluidInputs;
        builder.nativeSecondaryMode = nativeSecondaryMode;
        builder.extraEnergyPerTick = extraEnergyPerTick;
        builder.energyPerOperation = energyPerOperation;
        builder.inputs.addAll(inputs);
        builder.configuration.addAll(configuration);
        builder.outputs.addAll(outputs);
        builder.chanceOutputs.addAll(chanceOutputs);
        builder.choiceOutputs.addAll(choiceOutputs);
        builder.choiceAmount = choiceAmount;
        builder.sunlightRequired = sunlightRequired;
        builder.outputMultiplierEnabled = outputMultiplierEnabled;
        builder.decayAmount = decayAmount;
        builder.requiredEnvironmentAxis = requiredEnvironmentAxis; builder.selectionGroup = selectionGroup; builder.selectionPriority = selectionPriority;
        return builder;
    }

    public static final class Ingredient {
        private final CloudworksResourceMatcher matcher;
        private final long amount;
        private final String port;

        private Ingredient(CloudworksResourceMatcher matcher, long amount, String port) {
            if (amount <= 0) throw new IllegalArgumentException("Invalid recipe ingredient");
            this.matcher = Objects.requireNonNull(matcher);
            this.amount = amount;
            this.port = port;
        }
        public CloudworksResourceMatcher matcher() { return matcher; }
        public long amount() { return amount; }
        public String port() { return port; }
    }

    public static final class Output {
        private final QIOResourceDescriptor descriptor;
        private final long amount;
        private final String port;
        private final double chance;
        private final boolean scalable;

        private Output(QIOResourceDescriptor descriptor, long amount, String port, double chance, boolean scalable) {
            if (descriptor == null || !descriptor.isResolved() || amount <= 0
                    || !Double.isFinite(chance) || chance < 0 || chance > 1)
                throw new IllegalArgumentException("Invalid recipe output");
            this.descriptor = descriptor;
            this.amount = amount;
            this.port = port;
            this.chance = chance;
            this.scalable = scalable;
        }
        public QIOResourceDescriptor descriptor() { return descriptor; }
        public long amount() { return amount; }
        public String port() { return port; }
        public double chance() { return chance; }
        public boolean scalable() { return scalable; }
    }

    public static final class Builder {
        private net.minecraft.util.ResourceLocation requiredEnvironmentAxis;
        private String selectionGroup = "";
        private int selectionPriority;
        public Builder environmentAxis(net.minecraft.util.ResourceLocation axis) {
            requiredEnvironmentAxis = Objects.requireNonNull(axis);
            if (axis.toString().length() > 128) throw new IllegalArgumentException("Environment axis id is too long");
            return this;
        }
        /** One winning matching candidate per named group, before resource/space admission. */
        public Builder exclusiveSelection(String group, int priority) {
            if (group == null || group.isEmpty() || group.length() > 128) throw new IllegalArgumentException("Invalid selection group");
            selectionGroup = group; selectionPriority = priority; return this;
        }
        private final String id;
        private int duration;
        private int operationsPerCycle = 1;
        private double energyPerInput;
        private long unitsPerOutput, prepaidFuelUnits;
        private QIOResourceDescriptor creditResource;
        private boolean secondaryGasInputs;
        private boolean secondaryFluidInputs;
        private int nativeSecondaryMode;
        public Builder nativeSecondaryFuel(boolean randomized) { nativeSecondaryMode = randomized ? 2 : 1; return this; }
        public Builder secondaryGasInputs() { secondaryGasInputs = true; return this; }
        public Builder secondaryFluidInputs() { secondaryFluidInputs = true; return this; }
        private double extraEnergyPerTick, energyPerOperation;
        private final List<Ingredient> inputs = new ArrayList<>(), configuration = new ArrayList<>();
        private final List<Output> outputs = new ArrayList<>(), chanceOutputs = new ArrayList<>();
        private final List<QIOResourceDescriptor> choiceOutputs = new ArrayList<>();
        private long choiceAmount = 1, decayAmount = -1;
        private boolean sunlightRequired, outputMultiplierEnabled = true;

        private Builder(String id) {
            if (id == null || id.isEmpty() || id.length() > 256)
                throw new IllegalArgumentException("Invalid recipe id");
            this.id = id;
        }

        public Builder duration(int ticks) {
            if (ticks < 0) throw new IllegalArgumentException("Negative recipe duration");
            duration = ticks;
            return this;
        }
        /** Multiple native operations share one powered execution unit/cycle. */
        public Builder operationsPerCycle(int count) {
            if (count < 1) throw new IllegalArgumentException("Invalid operations per cycle");
            operationsPerCycle = count; return this;
        }
        /** Continuous processing preserves whole ingredient operations and fractional paid progress.
         * The sole input/output may contain multiple units; extraEnergyPerTick, when positive,
         * caps the power draw of one execution unit instead of adding another energy charge. */
        public Builder powerDriven(double joulesPerInput, long inputsPerOutput) {
            if (!Double.isFinite(joulesPerInput) || joulesPerInput <= 0 || inputsPerOutput < 1)
                throw new IllegalArgumentException("Invalid power-driven conversion");
            energyPerInput = joulesPerInput; unitsPerOutput = inputsPerOutput;
            outputMultiplierEnabled = false; return this;
        }
        /** One physical fuel ingredient prepays this many units of the declared fuel resource. */
        public Builder prepaidFuel(QIOResourceDescriptor resource, long units) {
            if (resource == null || !resource.isResolved() || units < 1) throw new IllegalArgumentException("Invalid prepaid fuel");
            creditResource = resource; prepaidFuelUnits = units; outputMultiplierEnabled = false; return this;
        }

        public Builder input(QIOResourceDescriptor descriptor, long amount, String port) {
            return ingredient(inputs, CloudworksResourceMatcher.exact(descriptor), amount, port);
        }

        public Builder alternatives(List<QIOResourceDescriptor> descriptors, long amount, String port) {
            return ingredient(inputs, CloudworksResourceMatcher.alternatives(descriptors), amount, port);
        }

        public Builder matcher(CloudworksResourceMatcher matcher, long amount, String port) {
            return ingredient(inputs, matcher, amount, port);
        }

        public Builder configuration(QIOResourceDescriptor descriptor, String port) {
            return ingredient(configuration, CloudworksResourceMatcher.exact(descriptor), 1, port);
        }

        public Builder configurationMatcher(CloudworksResourceMatcher matcher, String port) {
            return ingredient(configuration, matcher, 1, port);
        }

        private Builder ingredient(List<Ingredient> target, CloudworksResourceMatcher matcher,
                long amount, String port) {
            target.add(new Ingredient(matcher, amount, port == null ? "" : port));
            return this;
        }

        public Builder output(QIOResourceDescriptor descriptor, long amount, String port) {
            outputs.add(new Output(descriptor, amount, port == null ? "" : port, 0, true));
            return this;
        }
        public Builder unscaledOutput(QIOResourceDescriptor descriptor, long amount, String port) {
            outputs.add(new Output(descriptor, amount, port == null ? "" : port, 0, false));
            return this;
        }
        public Builder chanceOutput(QIOResourceDescriptor descriptor, long amount, double chance, String port) {
            chanceOutputs.add(new Output(descriptor, amount, port == null ? "" : port, chance, false));
            return this;
        }
        public Builder choiceOutput(QIOResourceDescriptor descriptor) {
            return choiceOutput(Collections.singletonList(descriptor), 1);
        }
        public Builder choiceOutput(List<QIOResourceDescriptor> descriptors, long amount) {
            if (choiceAmount != 1 || !choiceOutputs.isEmpty() || descriptors == null || descriptors.isEmpty()
                    || amount <= 0)
                throw new IllegalArgumentException("Invalid exclusive output pool");
            for (QIOResourceDescriptor descriptor : descriptors) {
                if (descriptor == null || !descriptor.isResolved())
                    throw new IllegalArgumentException("Invalid choice output");
                choiceOutputs.add(descriptor);
            }
            choiceAmount = amount;
            return this;
        }
        private Builder addChoiceOutput(QIOResourceDescriptor descriptor) {
            if (descriptor == null || !descriptor.isResolved()) throw new IllegalArgumentException("Invalid choice output");
            choiceOutputs.add(descriptor);
            return this;
        }
        public Builder requireSunlight() { sunlightRequired = true; return this; }
        public Builder decay(long amount) {
            if (amount < 0) throw new IllegalArgumentException("Negative decay quantity");
            decayAmount = amount;
            outputMultiplierEnabled = false;
            return this;
        }
        public Builder unscaledOutputs() { outputMultiplierEnabled = false; return this; }
        public Builder extraEnergyPerTick(double amount) {
            extraEnergyPerTick = amount;
            return this;
        }
        public Builder energyPerOperation(double amount) {
            energyPerOperation = amount;
            return this;
        }
        public CloudworksRecipeSpec build() { return new CloudworksRecipeSpec(this); }
    }
}
