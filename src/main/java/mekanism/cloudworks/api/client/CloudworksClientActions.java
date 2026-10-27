package mekanism.cloudworks.api.client;

import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;

/**
 * Controlled edit actions available to client panel providers. Draft changes
 * are committed through the editor's normal validated submission; operations
 * that affect live state use the existing server-revalidated network path.
 * Observer-mode providers receive an action object whose draft mutations
 * refuse edits.
 */
public interface CloudworksClientActions {
    /** Assigns one cloud-owned custom upgrade to this panel's machine in the active draft. */
    void installCustomUpgrade(String typeId);

    /** Unassigns one custom upgrade from this panel's machine for refund at draft commit. */
    void removeCustomUpgrade(String typeId);

    /** Changes a machine's enabled state in the active edit draft. */
    default boolean setMachineActive(UUID node, boolean active) { return false; }

    /** Changes a machine's selected recipe mode in the active edit draft. */
    default boolean setMachineRecipeType(UUID node, String recipeType) { return false; }

    /** Changes a channel's enabled state in the active edit draft. */
    default boolean setChannelActive(UUID node, boolean active) { return false; }

    /** Adds a resource marker to a channel in the active edit draft. */
    default boolean addChannelResource(UUID node, QIOResourceDescriptor resource) { return false; }

    /** Removes a resource marker from a channel in the active edit draft. */
    default boolean removeChannelResource(UUID node, QIOResourceDescriptor resource) { return false; }
}
