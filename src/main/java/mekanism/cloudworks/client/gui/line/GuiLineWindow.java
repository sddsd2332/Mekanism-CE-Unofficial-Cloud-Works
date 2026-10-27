package mekanism.cloudworks.client.gui.line;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.cloudworks.common.content.CloudworksWindowTypes;
import mekanism.common.util.LangUtils;
import net.minecraft.util.text.TextComponentString;

public abstract class GuiLineWindow extends GuiWindow {
    private final String title;

    protected GuiLineWindow(IGuiWrapper gui, int x, int y, int width, int height, String title,
            SelectedWindowData.WindowType type) {
        super(gui, x, y, width, height, type);
        this.title = title;
        interactionStrategy = InteractionStrategy.ALL;
    }

    protected static SelectedWindowData.WindowType type(String name) {
        return switch (name) {
            case "identity" -> CloudworksWindowTypes.LINE_IDENTITY;
            case "state" -> CloudworksWindowTypes.LINE_STATE;
            case "upgrades" -> CloudworksWindowTypes.LINE_UPGRADES;
            case "energy" -> CloudworksWindowTypes.LINE_ENERGY;
            case "channel" -> CloudworksWindowTypes.LINE_CHANNEL;
            case "machine" -> CloudworksWindowTypes.LINE_MACHINE;
            case "link" -> CloudworksWindowTypes.LINE_LINK;
            case "resources" -> CloudworksWindowTypes.LINE_RESOURCES;
            case "blueprints" -> CloudworksWindowTypes.LINE_BLUEPRINTS;
            case "blueprint_needs" -> CloudworksWindowTypes.LINE_BLUEPRINT_NEEDS;
            default -> throw new IllegalArgumentException("Unknown line window");
        };
    }

    public static String tr(String key) {
        return key.startsWith("cloudworks.") ? LangUtils.localize(key)
                : LangUtils.localize("cloudworks.editor." + key);
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawTitleText(new TextComponentString(tr(title)), 6);
    }
}
