package mekanism.cloudworks.common.recipe;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksResourceStack;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;
import mekanism.cloudworks.common.service.CloudworksOutputMultiplier;
import mekanism.common.recipe.RecipeHandler;
import net.minecraft.util.ResourceLocation;

/**
 * Detached snapshot for an audited deterministic native adapter.
 */
public final class CloudworksFrozenRecipeView {
    private final int sourceVersion;
    private final List<Entry> entries;

    CloudworksFrozenRecipeView(int sourceVersion, List<Entry> entries) {
        this.sourceVersion = sourceVersion;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * Shared freeze skeleton for audited adapters: guards the global recipe
     * version around the caller's freeze work exactly like the native adapter,
     * so a registry mutation mid-capture refuses instead of freezing a mix.
     */
    static Optional<CloudworksFrozenRecipeView> capture(Supplier<List<Entry>> freezer) {
        int version = RecipeHandler.getGlobalRecipeVersion();
        List<Entry> captured;
        try {
            captured = freezer.get();
        } catch (RuntimeException | LinkageError unsafe) {
            return Optional.empty();
        }
        if (captured == null
                || version != RecipeHandler.getGlobalRecipeVersion())
            return Optional.empty();
        return Optional.of(new CloudworksFrozenRecipeView(version, captured));
    }

    /**
     * Deterministic ordering key: map iteration order must not decide which frozen recipe wins.
     */
    static String signature(Entry entry) {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < entry.outputTemplate().size(); i++) {
            key.append(entry.outputTemplate().get(i).write());
            if (!entry.outputPort(i).isEmpty()) key.append('@').append(entry.outputPort(i));
        }
        for (CloudworksRecipeBatch.ChanceOutput chance : entry.chanceOutputTemplate()) {
            key.append(chance.descriptor()).append(':').append(chance.amount())
                    .append(':').append(chance.chance());
            if (!chance.port().isEmpty()) key.append('@').append(chance.port());
            key.append(',');
        }
        for (CloudworksResourceStack choice : entry.choiceOutputTemplate()) key.append("choice:").append(choice.write());
        if (entry.energyPerOperation() != 0D)
            key.append("energy:").append(entry.energyPerOperation()).append(',');
        if (entry.extraEnergyPerTick() != 0D)
            key.append("usage:").append(entry.extraEnergyPerTick()).append(',');
        return key.toString();
    }

    public int sourceVersion() {
        return sourceVersion;
    }

    public int size() {
        return entries.size();
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry select(Map<QIOResourceDescriptor, Long> stock, int operations) {
        for (Entry entry : entries) if (entry.allocate(stock, operations) != null) return entry;
        return null;
    }

    public static final class Entry {
        private final CloudworksRecipeBatch.FrozenView batch;
        private final int fixedDuration;
        private final String recipeIdentity;
        private final CloudworksRecipeSpec definition;

        Entry(CloudworksRecipeBatch.FrozenView batch, int fixedDuration) {
            this(batch, fixedDuration, "");
        }

        Entry(CloudworksRecipeBatch.FrozenView batch, int fixedDuration, String recipeIdentity) {
            this(batch, fixedDuration, recipeIdentity, null);
        }

        Entry(CloudworksRecipeBatch.FrozenView batch, int fixedDuration, String recipeIdentity,
                CloudworksRecipeSpec definition) {
            this.batch = batch;
            this.fixedDuration = fixedDuration;
            this.recipeIdentity = Objects.requireNonNull(recipeIdentity);
            this.definition = definition;
        }

        public CloudworksRecipeSpec definition() { return definition; }

        public Map<QIOResourceDescriptor, Long> allocate(Map<QIOResourceDescriptor, Long> stock, int operations) {
            return batch.allocate(stock, operations);
        }

        public List<CloudworksResourceStack> outputs(int operations) {
            return batch.outputs(operations);
        }

        public int duration(int fallback) {
            return fixedDuration > 0 ? fixedDuration : Math.max(1, fallback);
        }

        // ---- read-only freezing accessors used by the owner-thread catalog ----

        /**
         * Zero when the entry has no fixed duration and falls back to the machine profile.
         */
        public int fixedDuration() {
            return fixedDuration;
        }

        public int inputCount() {
            return batch.inputCount();
        }

        public long inputAmount(int index) {
            return batch.inputAmount(index);
        }

        public Predicate<QIOResourceDescriptor> inputMatcher(int index) {
            return batch.inputMatcher(index);
        }

        public String inputPort(int index) {
            return batch.inputPort(index);
        }

        public ResourceLocation inputCodec(int index) { return batch.inputCodec(index); }
        public ResourceLocation configurationCodec(int index) { return batch.configurationCodec(index); }

        public int configurationCount() { return batch.configurationCount(); }
        public Predicate<QIOResourceDescriptor> configurationMatcher(int index) {
            return batch.configurationMatcher(index);
        }
        public String configurationPort(int index) { return batch.configurationPort(index); }
        public boolean matchesConfiguration(Map<QIOResourceDescriptor, Long> templates) {
            return batch.matchesConfiguration(templates);
        }

        /**
         * Per-operation output template; callers must not mutate the returned stacks.
         */
        public List<CloudworksResourceStack> outputTemplate() {
            return batch.outputTemplate();
        }

        public String outputPort(int index) {
            return batch.outputPort(index);
        }

        public String recipeIdentity() {
            return recipeIdentity;
        }

        public boolean scalableOutput(int index) { return batch.scalableOutput(index); }
        public boolean outputMultiplierEnabled() { return batch.outputMultiplierEnabled(); }

        /** Cold-only stable output contract identity, independent of runtime symbol allocation/order. */
        public long outputSeed() {
            StringBuilder identity = new StringBuilder(signature(this)).append('|').append(fixedDuration)
                    .append('|').append(recipeIdentity);
            for (int index = 0; index < inputCount(); index++)
                identity.append('|').append(inputAmount(index)).append(':').append(inputCodec(index))
                        .append(':').append(inputPort(index));
            for (int index = 0; index < outputTemplate().size(); index++)
                identity.append('|').append(scalableOutput(index));
            return CloudworksOutputMultiplier.fingerprint(identity.toString());
        }

        /**
         * Independently rolled per-operation chance outputs; pure data for the worker.
         */
        public List<CloudworksRecipeBatch.ChanceOutput> chanceOutputTemplate() {
            return batch.chanceOutputTemplate();
        }

        public List<CloudworksResourceStack> choiceOutputTemplate() { return batch.choiceOutputTemplate(); }
        public boolean sunlightRequired() { return batch.sunlightRequired(); }
        public long decayAmount() { return batch.decayAmount(); }
        public double extraEnergyPerTick() { return batch.extraEnergyPerTick(); }

        /**
         * Fuel contracts: cloud energy yielded per operation; 0 for resource recipes.
         */
        public double energyPerOperation() {
            return batch.energyPerOperation();
        }
    }
}
