package mekanism.cloudworks.api.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.util.ResourceLocation;

/**
 * Client-side registry of panel providers. Deterministic by contract: the
 * iteration order is ascending priority with lexicographic id tie-breaking,
 * independent of registration order, and duplicate ids are rejected at
 * registration time so providers can never overwrite each other.
 *
 * <p>This class is client-only: it is loaded from mod client setup and never
 * referenced by common or dedicated-server code.</p>
 */
public final class CloudworksClientPanels {
    private static final Map<ResourceLocation, CloudworksClientPanelProvider> PROVIDERS = new TreeMap<>(
            Comparator.comparing(ResourceLocation::toString));
    private static final Map<ResourceLocation, CloudworksClientLinkPanelProvider> LINK_PROVIDERS = new TreeMap<>(
            Comparator.comparing(ResourceLocation::toString));

    private CloudworksClientPanels() {
    }

    /** Registers one provider; duplicate ids and reserved namespaces throw. */
    public static synchronized void register(CloudworksClientPanelProvider provider) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(provider.id(), "provider id");
        String namespace = provider.id().getNamespace();
        if ("cloudworks".equals(namespace) || "mekanism".equals(namespace))
            throw new IllegalArgumentException("Reserved panel namespace: " + namespace);
        if (PROVIDERS.containsKey(provider.id()))
            throw new IllegalArgumentException("Duplicate client panel: " + provider.id());
        PROVIDERS.put(provider.id(), provider);
    }

    /** Snapshot in deterministic consultation order; never exposes the internal map. */
    public static synchronized List<CloudworksClientPanelProvider> providers() {
        List<CloudworksClientPanelProvider> ordered = new ArrayList<>(PROVIDERS.values());
        ordered.sort(Comparator.comparingInt(CloudworksClientPanelProvider::priority)
                .thenComparing(provider -> provider.id().toString()));
        return ordered;
    }

    public static synchronized void registerLink(CloudworksClientLinkPanelProvider provider) {
        Objects.requireNonNull(provider, "provider");
        ResourceLocation id = Objects.requireNonNull(provider.id(), "provider id");
        String namespace = id.getNamespace();
        if ("cloudworks".equals(namespace) || "mekanism".equals(namespace))
            throw new IllegalArgumentException("Reserved panel namespace: " + namespace);
        if (LINK_PROVIDERS.containsKey(id))
            throw new IllegalArgumentException("Duplicate client link panel: " + id);
        LINK_PROVIDERS.put(id, provider);
    }

    public static synchronized List<CloudworksClientLinkPanelProvider> linkProviders() {
        List<CloudworksClientLinkPanelProvider> ordered = new ArrayList<>(LINK_PROVIDERS.values());
        ordered.sort(Comparator.comparingInt(CloudworksClientLinkPanelProvider::priority)
                .thenComparing(provider -> provider.id().toString()));
        return ordered;
    }

    /** Test-only: restores the registration window between isolated runs. */
    public static synchronized void resetForTests() {
        PROVIDERS.clear();
        LINK_PROVIDERS.clear();
    }
}
