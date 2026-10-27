package mekanism.cloudworks.common.recipe;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Small owner-thread live map view used to defer optional-mod enumeration. */
public final class CloudworksLiveMaps {
    private CloudworksLiveMaps() {
    }

    public static <K, V> Map<K, V> live(Supplier<Map<K, V>> source) {
        return new AbstractMap<K, V>() {
            private Map<K, V> snapshot() {
                Map<K, V> value = source.get();
                return value == null ? new LinkedHashMap<>() : value;
            }

            @Override public Set<Entry<K, V>> entrySet() { return snapshot().entrySet(); }
            @Override public Collection<V> values() { return snapshot().values(); }
            @Override public int size() { return snapshot().size(); }
        };
    }
}
