package mekanism.cloudworks.client.gui.team;

import java.util.Locale;
import java.util.UUID;
import mekanism.common.util.LangUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;

/** Shared presentation helpers for team entry, management and member windows. */
public final class CloudworksTeamGui {
    private CloudworksTeamGui() { }

    public static TextComponentString text(String key) {
        return new TextComponentString(LangUtils.localize("cloudworks.team." + key));
    }

    public static TextComponentString mode(String mode) {
        return text("mode_" + mode.toLowerCase(Locale.ROOT));
    }

    public static boolean validName(String name) {
        return name != null && !name.trim().isEmpty() && name.length() <= 64
                && name.chars().noneMatch(c -> c < 32 || c == 127 || c == 167);
    }

    /** Coordinates are relative to the host GUI, just like GuiElement foregrounds. */
    public static void avatar(UUID player, int x, int y, int size) {
        if (player == null) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        NetworkPlayerInfo info = minecraft.getConnection() == null ? null
                : minecraft.getConnection().getPlayerInfo(player);
        ResourceLocation skin = info == null ? DefaultPlayerSkin.getDefaultSkin(player) : info.getLocationSkin();
        minecraft.getTextureManager().bindTexture(skin);
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.enableBlend();
        Gui.drawScaledCustomSizeModalRect(x, y, 8, 8, 8, 8, size, size, 64, 64);
        Gui.drawScaledCustomSizeModalRect(x, y, 40, 8, 8, 8, size, size, 64, 64);
        GlStateManager.disableBlend();
        GlStateManager.color(1, 1, 1, 1);
    }
}
