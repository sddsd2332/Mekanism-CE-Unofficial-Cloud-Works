package mekanism.cloudworks.api.client;

import javax.annotation.Nullable;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.window.GuiWindow;
import net.minecraft.util.ResourceLocation;

/**
 * Client-side panel provider for line nodes. Providers are registered by
 * namespaced id during mod client setup and consulted when a node window is
 * requested; selection is deterministic (ascending priority, ties broken by
 * id); matching providers are consulted in order until one returns a window.
 *
 * <p>Providers must reuse Mekanism's window, scroll-list, slot, gauge and
 * text rendering tools and must keep their window read-only when the node
 * view reports a non-editing session.</p>
 */
public interface CloudworksClientPanelProvider {
    /** Namespaced provider id; the {@code cloudworks} and {@code mekanism} namespaces are reserved. */
    ResourceLocation id();

    /** Lower values are consulted first; equal priorities are ordered by id. Default 0. */
    default int priority() {
        return 0;
    }

    /** Whether this provider owns the window for the given node. */
    boolean handles(CloudworksClientNodeView node);

    /**
     * Creates the window for a node this provider handles. The views are
     * immutable snapshots; edit actions update only the edit draft or route
     * through a server-revalidated action. Returning {@code null} declines this
     * provider so the next provider or built-in window can handle the target.
     */
    @Nullable
    GuiWindow createWindow(IGuiWrapper gui, CloudworksClientNodeView node,
            CloudworksClientCloudView cloud, CloudworksClientActions actions);
}
