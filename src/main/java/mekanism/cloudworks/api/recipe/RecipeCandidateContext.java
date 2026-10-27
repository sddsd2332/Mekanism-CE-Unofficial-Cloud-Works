package mekanism.cloudworks.api.recipe;

import java.util.Objects;
import java.util.Map;
import java.util.Collections;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import java.util.LinkedHashMap;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;

/** Per-attempt view of one already-frozen recipe candidate. */
public final class RecipeCandidateContext {
    private final CloudworksRecipeSpec recipe;
    private final int originalOrder;
    private final Map<QIOResourceDescriptor, Long> configuration;
    private final CloudworksGenerationContext environment;

    public RecipeCandidateContext(CloudworksRecipeSpec recipe, int originalOrder,
            Map<QIOResourceDescriptor, Long> configuration, CloudworksGenerationContext environment) {
        this.recipe = Objects.requireNonNull(recipe);
        this.originalOrder = originalOrder;
        this.configuration = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(configuration)));
        this.environment = Objects.requireNonNull(environment);
    }

    public CloudworksRecipeSpec recipe() { return recipe; }
    public int originalOrder() { return originalOrder; }
    public Map<QIOResourceDescriptor, Long> configuration() { return configuration; }
    public CloudworksGenerationContext environment() { return environment; }
}
