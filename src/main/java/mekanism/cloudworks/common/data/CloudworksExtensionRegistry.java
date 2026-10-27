package mekanism.cloudworks.common.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mekanism.cloudworks.api.CloudworksExtensionType;
import net.minecraft.util.ResourceLocation;
import mekanism.cloudworks.api.CloudworksAPI;

/**
 * Internal consumer for public extension type declarations. It answers
 * id/version queries for {@link CloudworksExtensionSet}; payload decoding is
 * the addon's business and never happens on Runtime workers.
 */
public final class CloudworksExtensionRegistry {
    private static final Map<ResourceLocation, CloudworksExtensionType<?>> TYPES = new LinkedHashMap<>();
    private static boolean frozen;

    private CloudworksExtensionRegistry() {
    }

    /** CloudWorks lifecycle entry; duplicates abort loading with the id named. */
    public static void freezeAndRegister() {
        Map<ResourceLocation, CloudworksExtensionType<?>> checkpoint = new LinkedHashMap<>(TYPES);
        try {
            CloudworksAPI.freezeAndDrainExtensions(type -> {
                if (TYPES.containsKey(type.id()))
                    throw new IllegalArgumentException("Duplicate extension type: " + type.id());
                TYPES.put(type.id(), type);
            });
            TYPES.values().forEach(type -> {
                if (type.version() <= 0) throw new IllegalArgumentException("Invalid extension version");
            });
        } catch (RuntimeException failure) {
            // Transactional drain (audit fix): no partial writes survive a refusal.
            TYPES.clear();
            TYPES.putAll(checkpoint);
            throw failure;
        }
        frozen = true;
    }

    public static boolean isKnown(ResourceLocation id) {
        return TYPES.containsKey(id);
    }

    /** Declared version of one type; -1 when unknown. */
    public static int version(ResourceLocation id) {
        CloudworksExtensionType<?> type = TYPES.get(id);
        return type == null ? -1 : type.version();
    }

    public static boolean isFrozen() {
        return frozen;
    }

    public static Map<ResourceLocation, CloudworksExtensionType<?>> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(TYPES));
    }

    /** Test-only: restores the declaration window between isolated runs. */
    public static void resetForTests() {
        TYPES.clear();
        frozen = false;
    }
}
