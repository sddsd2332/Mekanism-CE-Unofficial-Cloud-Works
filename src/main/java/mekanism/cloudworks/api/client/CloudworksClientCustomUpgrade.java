package mekanism.cloudworks.api.client;

import net.minecraft.item.ItemStack;

/**
 * One custom upgrade pool entry as the client sees it. Counts and templates
 * are server-synced snapshots; unknown ids keep their counts with an empty
 * display key and no item.
 */
public interface CloudworksClientCustomUpgrade {
    /** Namespaced type id. */
    String id();

    /** Declaration display key; empty for unknown ids. */
    String displayKey();

    /** Server-synced pool count. */
    long count();

    /** Physical upload template; empty for editor-only or unknown types. */
    ItemStack item();
}
