package mekanism.cloudworks.api.client;

import javax.annotation.Nullable;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.window.GuiWindow;
import net.minecraft.util.ResourceLocation;

/** Read-only addon link panels, consulted by priority then namespaced provider id. */
public interface CloudworksClientLinkPanelProvider {
    ResourceLocation id();

    default int priority() { return 0; }

    boolean handles(CloudworksClientLinkView link);

    /** Returning null declines the target so the next provider or built-in panel may handle it. */
    @Nullable
    GuiWindow createWindow(IGuiWrapper gui, CloudworksClientLinkView link, CloudworksClientCloudView cloud);
}
