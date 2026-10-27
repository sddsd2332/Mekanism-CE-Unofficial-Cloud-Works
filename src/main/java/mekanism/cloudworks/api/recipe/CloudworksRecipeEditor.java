package mekanism.cloudworks.api.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import mekanism.api.qio.resource.QIOResourceDescriptor;

/**
 * Lifecycle-only editor for deriving one immutable recipe contract from another.
 *
 * <p>The editor never runs on a Runtime worker. It is intended for mechanism
 * transformers that need to remove, replace, or scale existing recipe entries
 * without accidentally duplicating the original outputs.</p>
 */
public final class CloudworksRecipeEditor {

    private final String derivedId;
    private int duration;
    private int operationsPerCycle;
    private final CloudworksRecipeSpec source;
    private net.minecraft.util.ResourceLocation requiredEnvironmentAxis;
    private double extraEnergyPerTick;
    private double energyPerOperation;
    private final List<InputEdit> inputs = new ArrayList<>();
    private final List<InputEdit> configuration = new ArrayList<>();
    private final List<OutputEdit> outputs = new ArrayList<>();
    private final List<OutputEdit> chanceOutputs = new ArrayList<>();
    private final List<QIOResourceDescriptor> choiceOutputs = new ArrayList<>();
    private long choiceAmount;
    private boolean sunlightRequired;
    private boolean outputMultiplierEnabled;
    private long decayAmount = -1;

    private CloudworksRecipeEditor(CloudworksRecipeSpec source, String derivedId) {
        if (source == null) throw new IllegalArgumentException("Recipe source cannot be null");
        if (derivedId == null || derivedId.isEmpty() || derivedId.length() > 256)
            throw new IllegalArgumentException("Invalid derived recipe id");
        this.derivedId = derivedId;
        this.source = source;
        duration = source.duration();
        operationsPerCycle = source.operationsPerCycle();
        requiredEnvironmentAxis = source.requiredEnvironmentAxis();
        extraEnergyPerTick = source.extraEnergyPerTick();
        energyPerOperation = source.energyPerOperation();
        sunlightRequired = source.sunlightRequired();
        outputMultiplierEnabled = source.outputMultiplierEnabled();
        decayAmount = source.decayAmount();
        inputs.addAll(copyInputs(source.inputs()));
        configuration.addAll(copyInputs(source.configuration()));
        for (CloudworksRecipeSpec.Output output : source.outputs()) {
            outputs.add(OutputEdit.guaranteed(output.descriptor(), output.amount(), output.port())
                    .scaled(output.scalable()));
        }
        for (CloudworksRecipeSpec.Output output : source.chanceOutputs()) {
            chanceOutputs.add(OutputEdit.chance(output.descriptor(), output.amount(),
                    output.chance(), output.port()));
        }
        choiceOutputs.addAll(source.choiceOutputs());
        choiceAmount = source.choiceAmount();
    }

    /**
     * Starts an editor while preserving every field of the source contract.
     */
    public static CloudworksRecipeEditor from(CloudworksRecipeSpec source, String derivedId) {
        return new CloudworksRecipeEditor(source, derivedId);
    }

    public CloudworksRecipeEditor mapOutputs(UnaryOperator<OutputEdit> mapper) {
        mapValues(outputs, mapper);
        return this;
    }

    public CloudworksRecipeEditor mapChanceOutputs(UnaryOperator<OutputEdit> mapper) {
        mapValues(chanceOutputs, mapper);
        return this;
    }

    public CloudworksRecipeEditor removeOutputs(Predicate<OutputEdit> predicate) {
        removeValues(outputs, predicate);
        return this;
    }

    public CloudworksRecipeEditor removeChanceOutputs(Predicate<OutputEdit> predicate) {
        removeValues(chanceOutputs, predicate);
        return this;
    }

    public CloudworksRecipeEditor addOutput(QIOResourceDescriptor descriptor, long amount, String port) {
        outputs.add(OutputEdit.guaranteed(descriptor, amount, port));
        return this;
    }

    public CloudworksRecipeEditor addChanceOutput(QIOResourceDescriptor descriptor, long amount,
                                                   double chance, String port) {
        chanceOutputs.add(OutputEdit.chance(descriptor, amount, chance, port));
        return this;
    }

    public CloudworksRecipeEditor duration(int ticks) {
        if (ticks < 0) throw new IllegalArgumentException("Negative recipe duration");
        duration = ticks;
        return this;
    }

    /** Sets the environment gate; null removes the source recipe's gate. */
    public CloudworksRecipeEditor environmentAxis(net.minecraft.util.ResourceLocation value) {
        requiredEnvironmentAxis = value;
        return this;
    }

    public CloudworksRecipeEditor extraEnergyPerTick(double amount) {
        if (!Double.isFinite(amount) || amount < 0D)
            throw new IllegalArgumentException("Invalid recipe extra energy");
        extraEnergyPerTick = amount;
        return this;
    }

    public CloudworksRecipeEditor energyPerOperation(double amount) {
        if (!Double.isFinite(amount) || amount < 0D)
            throw new IllegalArgumentException("Invalid recipe operation energy");
        energyPerOperation = amount;
        return this;
    }

    public CloudworksRecipeEditor requireSunlight(boolean value) {
        sunlightRequired = value;
        return this;
    }

    public CloudworksRecipeEditor outputMultiplierEnabled(boolean value) {
        outputMultiplierEnabled = value;
        return this;
    }

    public CloudworksRecipeEditor replaceChoiceOutputs(List<QIOResourceDescriptor> descriptors, long amount) {
        if (descriptors == null || descriptors.isEmpty() || amount <= 0)
            throw new IllegalArgumentException("Invalid exclusive output pool");
        choiceOutputs.clear();
        for (QIOResourceDescriptor descriptor : descriptors) {
            if (descriptor == null || !descriptor.isResolved())
                throw new IllegalArgumentException("Invalid exclusive output");
            choiceOutputs.add(descriptor);
        }
        choiceAmount = amount;
        return this;
    }

    public CloudworksRecipeEditor clearChoiceOutputs() {
        choiceOutputs.clear();
        choiceAmount = 0;
        return this;
    }

    public CloudworksRecipeEditor mapInputs(UnaryOperator<InputEdit> mapper) {
        mapValues(inputs, mapper);
        return this;
    }

    public CloudworksRecipeEditor mapConfiguration(UnaryOperator<InputEdit> mapper) {
        mapValues(configuration, mapper);
        return this;
    }

    public CloudworksRecipeEditor removeInputs(Predicate<InputEdit> predicate) {
        removeValues(inputs, predicate);
        return this;
    }

    public CloudworksRecipeEditor removeConfiguration(Predicate<InputEdit> predicate) {
        removeValues(configuration, predicate);
        return this;
    }

    public CloudworksRecipeEditor addInput(CloudworksResourceMatcher matcher, long amount, String port) {
        inputs.add(new InputEdit(matcher, amount, port));
        return this;
    }

    public CloudworksRecipeEditor addConfiguration(CloudworksResourceMatcher matcher, String port) {
        configuration.add(new InputEdit(matcher, 1, port));
        return this;
    }

    public CloudworksRecipeSpec build() {
        CloudworksRecipeSpec.Builder builder = CloudworksRecipeSpec.builder(derivedId)
                .duration(duration)
                .operationsPerCycle(operationsPerCycle)
                .extraEnergyPerTick(extraEnergyPerTick)
                .energyPerOperation(energyPerOperation);
        if (source.energyPerInput() > 0) builder.powerDriven(source.energyPerInput(), source.unitsPerOutput());
        if (source.prepaidFuelUnits() > 0) builder.prepaidFuel(source.creditResource(), source.prepaidFuelUnits());
        if (source.secondaryGasInputs()) builder.secondaryGasInputs();
        if (source.secondaryFluidInputs()) builder.secondaryFluidInputs();
        if (source.nativeSecondaryMode() > 0) builder.nativeSecondaryFuel(source.nativeSecondaryMode() == 2);
        if (requiredEnvironmentAxis != null) builder.environmentAxis(requiredEnvironmentAxis);
        if (!source.selectionGroup().isEmpty()) builder.exclusiveSelection(source.selectionGroup(), source.selectionPriority());
        for (InputEdit input : inputs) builder.matcher(input.matcher, input.amount, input.port);
        for (InputEdit input : configuration) builder.configurationMatcher(input.matcher, input.port);
        for (OutputEdit output : outputs) output.apply(builder);
        for (OutputEdit output : chanceOutputs) output.apply(builder);
        if (!choiceOutputs.isEmpty()) builder.choiceOutput(choiceOutputs, choiceAmount);
        if (sunlightRequired) builder.requireSunlight();
        if (decayAmount >= 0) builder.decay(decayAmount);
        else if (!outputMultiplierEnabled) builder.unscaledOutputs();
        return builder.build();
    }

    private static <T> void mapValues(List<T> values, UnaryOperator<T> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        for (int index = 0; index < values.size(); index++) {
            T next = mapper.apply(values.get(index));
            if (next == null) values.remove(index--);
            else values.set(index, next);
        }
    }

    private static <T> void removeValues(List<T> values, Predicate<T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        values.removeIf(predicate);
    }

    private static List<InputEdit> copyInputs(List<CloudworksRecipeSpec.Ingredient> source) {
        List<InputEdit> result = new ArrayList<>(source.size());
        for (CloudworksRecipeSpec.Ingredient input : source)
            result.add(new InputEdit(input.matcher(), input.amount(), input.port()));
        return result;
    }

    public static final class InputEdit {
        private final CloudworksResourceMatcher matcher;
        private final long amount;
        private final String port;

        private InputEdit(CloudworksResourceMatcher matcher, long amount, String port) {
            this.matcher = Objects.requireNonNull(matcher);
            if (amount <= 0) throw new IllegalArgumentException("Invalid recipe input amount");
            this.amount = amount;
            this.port = port == null ? "" : port;
        }

        public CloudworksResourceMatcher matcher() { return matcher; }
        public long amount() { return amount; }
        public String port() { return port; }

        public InputEdit amount(long value) {
            return new InputEdit(matcher, value, port);
        }

        public InputEdit port(String value) {
            return new InputEdit(matcher, amount, value);
        }

        public InputEdit matcher(CloudworksResourceMatcher value) {
            return new InputEdit(value, amount, port);
        }
    }

    public static final class OutputEdit {
        private final QIOResourceDescriptor descriptor;
        private final long amount;
        private final String port;
        private final double chance;
        private final boolean chanceOutput;
        private final boolean scalable;

        private OutputEdit(QIOResourceDescriptor descriptor, long amount, String port,
                           double chance, boolean chanceOutput, boolean scalable) {
            if (descriptor == null || !descriptor.isResolved() || amount <= 0
                    || !Double.isFinite(chance) || chance < 0D || chance > 1D)
                throw new IllegalArgumentException("Invalid recipe output edit");
            this.descriptor = descriptor;
            this.amount = amount;
            this.port = port == null ? "" : port;
            this.chance = chance;
            this.chanceOutput = chanceOutput;
            this.scalable = scalable;
        }

        public static OutputEdit guaranteed(QIOResourceDescriptor descriptor, long amount, String port) {
            return new OutputEdit(descriptor, amount, port, 0D, false, true);
        }

        public static OutputEdit chance(QIOResourceDescriptor descriptor, long amount,
                                        double chance, String port) {
            return new OutputEdit(descriptor, amount, port, chance, true, false);
        }

        public QIOResourceDescriptor descriptor() { return descriptor; }
        public long amount() { return amount; }
        public String port() { return port; }
        public double chance() { return chance; }
        public boolean chanceOutput() { return chanceOutput; }
        public boolean scalable() { return scalable; }

        public OutputEdit amount(long value) {
            return new OutputEdit(descriptor, value, port, chance, chanceOutput, scalable);
        }

        public OutputEdit descriptor(QIOResourceDescriptor value) {
            return new OutputEdit(value, amount, port, chance, chanceOutput, scalable);
        }

        public OutputEdit port(String value) {
            return new OutputEdit(descriptor, amount, value, chance, chanceOutput, scalable);
        }

        public OutputEdit chance(double value) {
            return new OutputEdit(descriptor, amount, port, value, true, false);
        }

        public OutputEdit guaranteed() {
            return new OutputEdit(descriptor, amount, port, 0D, false, true);
        }

        public OutputEdit asChance() {
            return new OutputEdit(descriptor, amount, port, chance, true, false);
        }

        public OutputEdit scaled(boolean value) {
            return new OutputEdit(descriptor, amount, port, chance, chanceOutput, value);
        }

        private void apply(CloudworksRecipeSpec.Builder builder) {
            if (chanceOutput) builder.chanceOutput(descriptor, amount, chance, port);
            else if (scalable) builder.output(descriptor, amount, port);
            else builder.unscaledOutput(descriptor, amount, port);
        }
    }
}
