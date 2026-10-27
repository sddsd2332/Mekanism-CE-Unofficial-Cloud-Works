package mekanism.cloudworks.api.client;

import java.util.List;

/**
 * Read-only view over the cloud data the client currently shows. The view is
 * derived from synced presentation data only; inventories, journals and
 * endpoint credentials never leave the server.
 */
public interface CloudworksClientCloudView {
    /** Custom upgrade pool entries, server order. */
    List<CloudworksClientCustomUpgrade> customUpgrades();

    /** Pool count of one custom type by namespaced id; 0 when absent. */
    long customUpgradeCount(String id);

    /** Native pool count; {@code true} selects speed, {@code false} energy. */
    int upgradePool(boolean speed);

    /** Generic cloud-effect levels, server order; ids stay opaque strings (8.6). */
    List<CloudworksClientEffectLevel> effectLevels();

    /** One generic cloud-effect level. */
    interface CloudworksClientEffectLevel {
        /** Namespaced effect id. */
        String id();

        /** Current level. */
        long level();
    }
}
