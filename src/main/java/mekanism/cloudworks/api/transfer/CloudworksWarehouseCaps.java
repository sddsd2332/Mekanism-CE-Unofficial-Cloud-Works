package mekanism.cloudworks.api.transfer;

import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceFamily;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-type warehouse capacity registry for the three staging warehouses
 * (receiving / transfer / sending). Every distinct resource type of a family
 * gets the family's cap on its own stored amount — there is no cross-type
 * shared budget. Built-ins: item uses the server's configured per-type cap;
 * fluid and gas use 1000x that value. Addon resource kinds register their own
 * families (for example a mana family with 900,000 per type); the first
 * registration wins, matching the append-only QIO codec registry philosophy.
 *
 * <p>Semantics follow QIO drives: content above a cap (for example after a
 * cap was lowered) stays readable, but nothing more of that type may enter
 * until extraction brings it back within the cap.</p>
 */
public final class CloudworksWarehouseCaps {
    /** Default configured per-type cap for the item family. */
    public static final long DEFAULT_ITEM_TYPE_CAP = 1_000_000L;
    /**
     * Fluid/gas per-type caps are the item cap times this multiplier.
     */
    public static final long FLUID_GAS_MULTIPLIER = 1_000L;

    private static final Set<String> BUILT_IN_FAMILIES = new HashSet<>(Arrays.asList(
            QIOResourceCodecs.ITEM_FAMILY, QIOResourceCodecs.FLUID_FAMILY, QIOResourceCodecs.GAS_FAMILY));
    private static final Map<String, Long> ADDON_FAMILY_TYPE_CAPS = new ConcurrentHashMap<>();
    private static volatile long configuredItemTypeCap = DEFAULT_ITEM_TYPE_CAP;

    private CloudworksWarehouseCaps() {
    }

    /**
     * Registers the per-type cap for one resource family. The first registration
     * wins; later registrations for the same family are ignored (return false).
     */
    public static boolean registerFamilyTypeCap(String family, long cap) {
        QIOResourceFamily.requireValid(Objects.requireNonNull(family, "family"));
        if (cap <= 0) throw new IllegalArgumentException("Per-type cap must be positive: " + family);
        if (BUILT_IN_FAMILIES.contains(family)) return false;
        return ADDON_FAMILY_TYPE_CAPS.putIfAbsent(family, cap) == null;
    }

    /**
     * Whether the family has an explicitly registered per-type cap.
     */
    public static boolean isCapped(String family) {
        return BUILT_IN_FAMILIES.contains(family) || ADDON_FAMILY_TYPE_CAPS.containsKey(family);
    }

    /**
     * Per-type cap of one family; unregistered families are unlimited.
     */
    public static long capOf(String family) {
        return capOf(family, configuredItemTypeCap);
    }

    /** CloudWorks lifecycle hook for the frozen server setting. */
    public static void configureItemTypeCap(long cap) {
        if (cap <= 0) throw new IllegalArgumentException("Per-type cap must be positive");
        configuredItemTypeCap = cap;
    }

    public static long capOf(String family, long itemCap) {
        if (itemCap < 0) throw new IllegalArgumentException("Negative per-type cap");
        if (QIOResourceCodecs.ITEM_FAMILY.equals(family)) return itemCap;
        if (QIOResourceCodecs.FLUID_FAMILY.equals(family) || QIOResourceCodecs.GAS_FAMILY.equals(family))
            return itemCap * FLUID_GAS_MULTIPLIER;
        return ADDON_FAMILY_TYPE_CAPS.getOrDefault(family, Long.MAX_VALUE);
    }
}
