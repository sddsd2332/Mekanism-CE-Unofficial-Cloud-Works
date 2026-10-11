package mekanism.cloudworks.common.recipe;

import mekanism.api.gas.GasStack;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.common.InfuseStorage;
import mekanism.common.OreDictCache;
import mekanism.common.recipe.inputs.MachineInput;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Per-query native recipe requirements. This is not a second recipe registry.
 */
public final class CloudworksRecipeBatch {
    private final List<Input> inputs = new ArrayList<>();
    private final List<Input> configuration = new ArrayList<>();
    private final List<CloudworksResourceStack> outputs = new ArrayList<>();
    private final List<String> outputPorts = new ArrayList<>();
    private final List<ChanceOutput> chanceOutputs = new ArrayList<>();
    private final List<CloudworksResourceStack> choiceOutputs = new ArrayList<>();
    private boolean sunlightRequired;
    private long decayAmount = -1;
    private double extraEnergyPerTick;
    /**
     * Fuel contracts only: energy produced per operation; 0 for resource recipes.
     */
    private double energyPerOperation;

    private static Predicate<QIOResourceDescriptor> frozenItemMatcher(ItemStack wanted) {
        if (wanted.isEmpty() || MachineInput.hasCustomItemMatcher(wanted)
                || OreDictCache.getOreDictName(wanted).contains("treeSapling")
                || OreDictCache.getOreDictName(new ItemStack(wanted.getItem(), 1,
                OreDictionary.WILDCARD_VALUE)).contains("treeSapling")) return null;
        NBTTagCompound payload = QIOResourceCodecs.item(wanted).getPayload();
        String id = payload.getString("id");
        int damage = payload.getShort("Damage");
        boolean hasTag = payload.hasKey("tag", 10);
        NBTTagCompound tag = hasTag ? payload.getCompoundTag("tag").copy() : null;
        return candidate -> {
            if (!QIOResourceCodecs.ITEM_STACK_ID.equals(candidate.getCodecId())) return false;
            NBTTagCompound actual = candidate.getPayload();
            return id.equals(actual.getString("id"))
                    && (damage == OreDictionary.WILDCARD_VALUE || damage == actual.getShort("Damage")
                    || actual.getShort("Damage") == OreDictionary.WILDCARD_VALUE)
                    && hasTag == actual.hasKey("tag", 10)
                    && (!hasTag || tag.equals(actual.getCompoundTag("tag")));
        };
    }

    private static Predicate<QIOResourceDescriptor> exactMatcher(QIOResourceDescriptor captured) {
        net.minecraft.util.ResourceLocation codec = captured.getCodecId();
        String family = captured.getFamily();
        int version = captured.getCodecVersion();
        NBTTagCompound payload = captured.getPayload();
        return candidate -> codec.equals(candidate.getCodecId()) && family.equals(candidate.getFamily())
                && version == candidate.getCodecVersion() && payload.equals(candidate.getPayload());
    }

    private static Map<QIOResourceDescriptor, Long> allocateFor(List<Input> inputs,
                                                                Map<QIOResourceDescriptor, Long> stock, int operations) {
        if (operations < 1) throw new IllegalArgumentException("Nonpositive batch");
        List<QIOResourceDescriptor> types = new ArrayList<>(stock.keySet());
        types.sort(Comparator.naturalOrder());
        int source = 0, sink = 1 + types.size() + inputs.size();
        List<List<Edge>> graph = new ArrayList<>();
        for (int i = 0; i <= sink; i++) graph.add(new ArrayList<>());
        long required = 0;
        for (int i = 0; i < inputs.size(); i++) {
            long amount = Math.multiplyExact(inputs.get(i).amount, operations);
            required = Math.addExact(required, amount);
            edge(graph, 1 + types.size() + i, sink, amount);
        }
        List<Edge> supplies = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            long amount = stock.get(types.get(i));
            if (amount < 0) throw new IllegalArgumentException("Negative stock");
            supplies.add(edge(graph, source, 1 + i, amount));
            for (int j = 0; j < inputs.size(); j++)
                if (inputs.get(j).matches.test(types.get(i)))
                    edge(graph, 1 + i, 1 + types.size() + j, Math.min(amount, required));
        }
        long flow = 0;
        while (flow < required) {
            Edge[] path = new Edge[graph.size()];
            int[] from = new int[graph.size()];
            Arrays.fill(from, -1);
            from[source] = source;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(source);
            while (!queue.isEmpty() && from[sink] < 0) {
                int at = queue.removeFirst();
                for (Edge e : graph.get(at))
                    if (e.remaining > 0 && from[e.to] < 0) {
                        from[e.to] = at;
                        path[e.to] = e;
                        queue.add(e.to);
                    }
            }
            if (from[sink] < 0) return null;
            long take = required - flow;
            for (int at = sink; at != source; at = from[at]) take = Math.min(take, path[at].remaining);
            for (int at = sink; at != source; at = from[at]) {
                Edge e = path[at];
                e.remaining -= take;
                e.reverse.remaining += take;
            }
            flow += take;
        }
        Map<QIOResourceDescriptor, Long> allocation = new TreeMap<>();
        for (int i = 0; i < supplies.size(); i++)
            if (supplies.get(i).reverse.remaining > 0) allocation.put(types.get(i), supplies.get(i).reverse.remaining);
        return allocation;
    }

    private static Edge edge(List<List<Edge>> graph, int from, int to, long amount) {
        Edge forward = new Edge(to, amount), reverse = new Edge(from, 0);
        forward.reverse = reverse;
        reverse.reverse = forward;
        graph.get(from).add(forward);
        graph.get(to).add(reverse);
        return forward;
    }

    /**
     * Registers an independently rolled per-operation item output.
     */
    public CloudworksRecipeBatch chanceOutput(ItemStack item, double chance) {
        if (item != null && !item.isEmpty() && chance > 0D)
            chanceOutputs.add(new ChanceOutput(QIOResourceCodecs.item(item), item.getCount(), chance));
        return this;
    }

    /**
     * Registers an independently rolled per-operation gas output.
     */
    public CloudworksRecipeBatch chanceOutput(GasStack gas, double chance) {
        if (gas != null && gas.amount > 0 && chance > 0D)
            chanceOutputs.add(new ChanceOutput(QIOResourceCodecs.gas(gas), gas.amount, chance));
        return this;
    }

    /** Exactly one candidate is chosen per operation; duplicate candidates retain their native weight. */
    public CloudworksRecipeBatch chooseItemOutput(List<ItemStack> candidates, long amount) {
        if (!choiceOutputs.isEmpty() || candidates.isEmpty() || amount <= 0)
            throw new IllegalArgumentException("Invalid exclusive output pool");
        for (ItemStack candidate : candidates) {
            if (candidate == null || candidate.isEmpty())
                throw new IllegalArgumentException("Empty exclusive output candidate");
            choiceOutputs.add(new CloudworksResourceStack(QIOResourceCodecs.item(candidate), amount));
        }
        return this;
    }

    public CloudworksRecipeBatch requireSunlight() {
        sunlightRequired = true;
        return this;
    }

    public CloudworksRecipeBatch decay(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative decay quantity");
        decayAmount = amount;
        return this;
    }

    public CloudworksRecipeBatch extraEnergy(double perTick) {
        if (!Double.isFinite(perTick) || perTick < 0D)
            throw new IllegalArgumentException("Invalid recipe energy");
        extraEnergyPerTick = perTick;
        return this;
    }

    public double extraEnergyPerTick() { return extraEnergyPerTick; }
    public double energyPerOperation() { return energyPerOperation; }

    /**
     * Declares this batch a fuel contract: one operation burns the registered
     * inputs and yields exactly this much cloud energy (no resource outputs).
     * Non-finite or non-positive energy is a registration bug and refuses.
     */
    public CloudworksRecipeBatch energy(double perOperation) {
        if (!Double.isFinite(perOperation) || perOperation <= 0D)
            throw new IllegalArgumentException("Invalid fuel contract energy");
        this.energyPerOperation = perOperation;
        return this;
    }

    public CloudworksRecipeBatch resource(QIOResourceDescriptor descriptor, long amount, String port) {
        if (descriptor == null || !descriptor.isResolved() || amount <= 0)
            throw new IllegalArgumentException("Invalid exact resource ingredient");
        Predicate<QIOResourceDescriptor> matcher = exactMatcher(descriptor);
        inputs.add(new Input(amount, matcher, () -> matcher, port, descriptor.getCodecId()));
        return this;
    }

    public CloudworksRecipeBatch resources(List<QIOResourceDescriptor> alternatives, long amount, String port) {
        if (alternatives == null || alternatives.isEmpty() || amount <= 0)
            throw new IllegalArgumentException("Empty resource alternatives");
        List<Predicate<QIOResourceDescriptor>> matchers = new ArrayList<>();
        net.minecraft.util.ResourceLocation codec = alternatives.get(0).getCodecId();
        for (QIOResourceDescriptor descriptor : alternatives) {
            if (descriptor == null || !descriptor.isResolved()) throw new IllegalArgumentException("Unresolved resource");
            if (!codec.equals(descriptor.getCodecId()))
                throw new IllegalArgumentException("One alternative ingredient cannot span different resource codecs");
            matchers.add(exactMatcher(descriptor));
        }
        Predicate<QIOResourceDescriptor> matcher = candidate -> {
            for (Predicate<QIOResourceDescriptor> alternative : matchers) if (alternative.test(candidate)) return true;
            return false;
        };
        inputs.add(new Input(amount, matcher, () -> matcher, port, codec));
        return this;
    }

    public CloudworksRecipeBatch template(QIOResourceDescriptor descriptor, String port) {
        if (descriptor == null || !descriptor.isResolved())
            throw new IllegalArgumentException("Invalid resource template");
        Predicate<QIOResourceDescriptor> matcher = exactMatcher(descriptor);
        configuration.add(new Input(1, matcher, () -> matcher, port, descriptor.getCodecId()));
        return this;
    }

    public CloudworksRecipeBatch output(QIOResourceDescriptor descriptor, long amount, String port) {
        if (descriptor == null || !descriptor.isResolved() || amount <= 0)
            throw new IllegalArgumentException("Invalid exact resource output");
        addOutput(descriptor, amount, port);
        return this;
    }

    public CloudworksRecipeBatch chanceOutput(QIOResourceDescriptor descriptor, long amount, double chance) {
        if (descriptor == null || !descriptor.isResolved() || amount <= 0
                || !Double.isFinite(chance) || chance < 0 || chance > 1)
            throw new IllegalArgumentException("Invalid resource chance output");
        if (chance > 0) chanceOutputs.add(new ChanceOutput(descriptor, amount, chance));
        return this;
    }

    public CloudworksRecipeBatch item(ItemStack item) {
        return item(item, "");
    }

    public CloudworksRecipeBatch item(ItemStack item, String port) {
        ItemStack wanted = item.copy();
        inputs.add(new Input(wanted.getCount(), descriptor -> {
            ItemStack actual = descriptor.resolveItemStackTemplate();
            return actual != null && !actual.isEmpty() && MachineInput.inputItemMatches(actual, wanted);
                }, () -> frozenItemMatcher(wanted), port, QIOResourceCodecs.ITEM_STACK_ID));
        return this;
    }

    public CloudworksRecipeBatch gas(GasStack gas, long amount) {
        return gas(gas, amount, "");
    }

    public CloudworksRecipeBatch gas(GasStack gas, long amount, String port) {
        GasStack wanted = gas.copy();
        inputs.add(new Input(amount, descriptor -> {
            GasStack actual = descriptor.resolveGasStackTemplate();
            return actual != null && actual.isGasEqual(wanted);
        },
                () -> exactMatcher(QIOResourceCodecs.gas(wanted)), port, QIOResourceCodecs.GAS_STACK_ID));
        return this;
    }

    public CloudworksRecipeBatch template(ItemStack item, String port) {
        ItemStack wanted = item.copy();
        wanted.setCount(1);
        configuration.add(new Input(1, descriptor -> {
            ItemStack actual = descriptor.resolveItemStackTemplate();
            return actual != null && MachineInput.inputItemMatches(actual, wanted);
        }, () -> frozenItemMatcher(wanted), port, QIOResourceCodecs.ITEM_STACK_ID));
        return this;
    }

    public CloudworksRecipeBatch template(GasStack gas, String port) {
        QIOResourceDescriptor wanted = QIOResourceCodecs.gas(gas);
        configuration.add(new Input(1, exactMatcher(wanted), () -> exactMatcher(wanted), port, QIOResourceCodecs.GAS_STACK_ID));
        return this;
    }

    public CloudworksRecipeBatch template(FluidStack fluid, String port) {
        QIOResourceDescriptor wanted = QIOResourceCodecs.fluid(fluid);
        configuration.add(new Input(1, exactMatcher(wanted), () -> exactMatcher(wanted), port, QIOResourceCodecs.FLUID_STACK_ID));
        return this;
    }

    public boolean matchesConfiguration(Map<QIOResourceDescriptor, Long> templates) {
        return matchesConfiguration(configuration, templates);
    }

    private static boolean matchesConfiguration(List<Input> requirements, Map<QIOResourceDescriptor, Long> templates) {
        for (Input requirement : requirements) {
            boolean matched = false;
            for (Map.Entry<QIOResourceDescriptor, Long> template : templates.entrySet())
                if (template.getValue() >= requirement.amount && requirement.matches.test(template.getKey())) {
                    matched = true;
                    break;
                }
            if (!matched) return false;
        }
        return true;
    }

    public CloudworksRecipeBatch fluid(FluidStack fluid) {
        FluidStack wanted = fluid.copy();
        inputs.add(new Input(wanted.amount, descriptor -> {
            FluidStack actual = descriptor.resolveFluidStackTemplate();
            return actual != null && actual.isFluidEqual(wanted);
        },
                () -> exactMatcher(QIOResourceCodecs.fluid(wanted)), "", QIOResourceCodecs.FLUID_STACK_ID));
        return this;
    }

    public CloudworksRecipeBatch infuse(InfuseStorage infuse) {
        InfuseStorage wanted = new InfuseStorage(infuse.getType(), infuse.getAmount());
        inputs.add(new Input(wanted.getAmount(), descriptor -> {
            InfuseStorage actual = descriptor.resolve(CloudworksInfuseResourceCodec.INSTANCE);
            return actual != null && actual.getType() == wanted.getType();
        }, () -> exactMatcher(CloudworksInfuseResourceCodec.descriptor(wanted)), "", CloudworksInfuseResourceCodec.ID));
        return this;
    }

    /**
     * Capture only predicates expressible without item, codec or ore-dictionary callbacks.
     */
    public Optional<FrozenView> freeze() {
        List<Input> frozen = new ArrayList<>(inputs.size());
        for (Input input : inputs) {
            Predicate<QIOResourceDescriptor> matcher = input.frozenMatcher.get();
            if (matcher == null) return Optional.empty();
            frozen.add(new Input(input.amount, matcher, null, input.port, input.codec));
        }
        List<Input> frozenConfiguration = new ArrayList<>(configuration.size());
        for (Input input : configuration) {
            Predicate<QIOResourceDescriptor> matcher = input.frozenMatcher.get();
            if (matcher == null) return Optional.empty();
            frozenConfiguration.add(new Input(input.amount, matcher, null, input.port, input.codec));
        }
        List<CloudworksResourceStack> output = outputs(1);
        return Optional.of(new FrozenView(frozen, output,
                Collections.unmodifiableList(new ArrayList<>(chanceOutputs)), outputPorts, energyPerOperation,
                choiceOutputs, sunlightRequired, frozenConfiguration, decayAmount, extraEnergyPerTick));
    }

    public CloudworksRecipeBatch output(ItemStack item) {
        return output(item, "");
    }

    public CloudworksRecipeBatch output(ItemStack item, String portId) {
        if (!item.isEmpty()) addOutput(QIOResourceCodecs.item(item), item.getCount(), portId);
        return this;
    }

    public CloudworksRecipeBatch output(GasStack gas) {
        return output(gas, "");
    }

    public CloudworksRecipeBatch output(GasStack gas, String portId) {
        if (gas != null && gas.amount > 0)
            addOutput(QIOResourceCodecs.gas(gas), gas.amount, portId);
        return this;
    }

    public CloudworksRecipeBatch output(FluidStack fluid) {
        return output(fluid, "");
    }

    public CloudworksRecipeBatch output(FluidStack fluid, String portId) {
        if (fluid != null && fluid.amount > 0)
            addOutput(QIOResourceCodecs.fluid(fluid), fluid.amount, portId);
        return this;
    }

    private void addOutput(QIOResourceDescriptor descriptor, long amount, String portId) {
        outputs.add(new CloudworksResourceStack(descriptor, amount));
        outputPorts.add(portId == null ? "" : portId);
    }

    public List<CloudworksResourceStack> outputs(int operations) {
        if (operations < 1) throw new IllegalArgumentException("Nonpositive batch");
        List<CloudworksResourceStack> result = new ArrayList<>();
        for (CloudworksResourceStack value : outputs)
            result.add(new CloudworksResourceStack(value.descriptor(), Math.multiplyExact(value.amount(), operations)));
        return result;
    }

    /**
     * Exact bipartite allocation, including overlapping wildcard ingredients and shared descriptors.
     */
    public Map<QIOResourceDescriptor, Long> allocate(Map<QIOResourceDescriptor, Long> stock, int operations) {
        return allocateFor(inputs, stock, operations);
    }

    public static final class Input {
        final long amount;
        final Predicate<QIOResourceDescriptor> matches;
        final Supplier<Predicate<QIOResourceDescriptor>> frozenMatcher;
        final String port;
        final net.minecraft.util.ResourceLocation codec;

        Input(long amount, Predicate<QIOResourceDescriptor> matches,
              Supplier<Predicate<QIOResourceDescriptor>> frozenMatcher) {
            this(amount, matches, frozenMatcher, "");
        }

        Input(long amount, Predicate<QIOResourceDescriptor> matches,
              Supplier<Predicate<QIOResourceDescriptor>> frozenMatcher, String port) {
            this(amount, matches, frozenMatcher, port, null);
        }

        Input(long amount, Predicate<QIOResourceDescriptor> matches,
              Supplier<Predicate<QIOResourceDescriptor>> frozenMatcher, String port,
              net.minecraft.util.ResourceLocation codec) {
            if (amount <= 0) throw new IllegalArgumentException("Nonpositive recipe input");
            this.amount = amount;
            this.matches = matches;
            this.frozenMatcher = frozenMatcher;
            this.port = Objects.requireNonNull(port);
            this.codec = codec;
        }
    }

    /**
     * One independently rolled per-operation output: every operation rolls
     * {@code random.nextDouble() < chance} and, on a hit, produces exactly this
     * amount. Chance entries never consume inputs and are excluded from the
     * deterministic per-operation template.
     */
    public static final class ChanceOutput {
        final QIOResourceDescriptor descriptor;
        final long amount;
        final double chance;

        ChanceOutput(QIOResourceDescriptor descriptor, long amount, double chance) {
            if (amount <= 0) throw new IllegalArgumentException("Nonpositive chance output");
            if (chance <= 0D || chance > 1D || !Double.isFinite(chance))
                throw new IllegalArgumentException("Invalid chance output probability");
            this.descriptor = descriptor;
            this.amount = amount;
            this.chance = chance;
        }

        public QIOResourceDescriptor descriptor() {
            return descriptor;
        }

        public long amount() {
            return amount;
        }

        public double chance() {
            return chance;
        }
    }

    public static final class FrozenView {
        private final List<Input> inputs;
        private final List<Input> configuration;
        private final List<CloudworksResourceStack> outputs;
        private final List<ChanceOutput> chanceOutputs;
        private final List<String> outputPorts;
        private final double energyPerOperation;
        private final List<CloudworksResourceStack> choiceOutputs;
        private final boolean sunlightRequired;
        private final long decayAmount;
        private final double extraEnergyPerTick;

        private FrozenView(List<Input> inputs, List<CloudworksResourceStack> outputs,
                           List<ChanceOutput> chanceOutputs, List<String> outputPorts, double energyPerOperation,
                           List<CloudworksResourceStack> choiceOutputs, boolean sunlightRequired, List<Input> configuration,
                           long decayAmount, double extraEnergyPerTick) {
            this.inputs = Collections.unmodifiableList(inputs);
            this.outputs = Collections.unmodifiableList(outputs);
            this.chanceOutputs = chanceOutputs;
            this.outputPorts = Collections.unmodifiableList(new ArrayList<>(outputPorts));
            this.energyPerOperation = energyPerOperation;
            this.choiceOutputs = Collections.unmodifiableList(new ArrayList<>(choiceOutputs));
            this.sunlightRequired = sunlightRequired;
            this.configuration = Collections.unmodifiableList(configuration);
            this.decayAmount = decayAmount;
            this.extraEnergyPerTick = extraEnergyPerTick;
        }

        public Map<QIOResourceDescriptor, Long> allocate(Map<QIOResourceDescriptor, Long> stock, int operations) {
            return allocateFor(inputs, stock, operations);
        }

        public List<CloudworksResourceStack> outputs(int operations) {
            if (operations < 1) throw new IllegalArgumentException("Nonpositive batch");
            List<CloudworksResourceStack> result = new ArrayList<>(outputs.size());
            for (CloudworksResourceStack stack : outputs)
                result.add(new CloudworksResourceStack(stack.descriptor(), Math.multiplyExact(stack.amount(), operations)));
            return result;
        }

        /**
         * Read-only per-operation input rules so owner threads can freeze them into pure symbols.
         */
        public int inputCount() {
            return inputs.size();
        }

        public long inputAmount(int index) {
            return inputs.get(index).amount;
        }

        public Predicate<QIOResourceDescriptor> inputMatcher(int index) {
            return inputs.get(index).matches;
        }

        public String inputPort(int index) {
            return inputs.get(index).port;
        }

        public net.minecraft.util.ResourceLocation inputCodec(int index) { return inputs.get(index).codec; }
        public net.minecraft.util.ResourceLocation configurationCodec(int index) { return configuration.get(index).codec; }

        public int configurationCount() { return configuration.size(); }
        public Predicate<QIOResourceDescriptor> configurationMatcher(int index) { return configuration.get(index).matches; }
        public String configurationPort(int index) { return configuration.get(index).port; }
        public boolean matchesConfiguration(Map<QIOResourceDescriptor, Long> templates) {
            return CloudworksRecipeBatch.matchesConfiguration(configuration, templates);
        }

        /**
         * Per-operation output template; callers must not mutate the returned stacks.
         */
        public List<CloudworksResourceStack> outputTemplate() {
            return outputs;
        }

        public String outputPort(int index) {
            return outputPorts.get(index);
        }

        /**
         * Independently rolled per-operation outputs; pure data, rolled by the worker.
         */
        public List<ChanceOutput> chanceOutputTemplate() {
            return chanceOutputs;
        }

        public List<CloudworksResourceStack> choiceOutputTemplate() { return choiceOutputs; }
        public boolean sunlightRequired() { return sunlightRequired; }
        public long decayAmount() { return decayAmount; }
        public double extraEnergyPerTick() { return extraEnergyPerTick; }

        /**
         * Fuel contracts: cloud energy per operation; 0 for resource recipes.
         */
        public double energyPerOperation() {
            return energyPerOperation;
        }
    }

    private static final class Edge {
        final int to;
        long remaining;
        Edge reverse;

        Edge(int to, long remaining) {
            this.to = to;
            this.remaining = remaining;
        }
    }
}
