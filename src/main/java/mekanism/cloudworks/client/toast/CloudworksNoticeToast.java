package mekanism.cloudworks.client.toast;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.toasts.GuiToast;
import net.minecraft.client.gui.toasts.IToast;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;
import java.util.UUID;
import mekanism.client.render.IFancyFontRenderer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import mekanism.cloudworks.common.network.CloudworksNetwork;

/**
 * Cloudworks notice toast, AE2-style: vanilla toast texture, fixed text,
 * five seconds. Fired by the server notice packet (recycle overflow etc.).
 */
@SideOnly(Side.CLIENT)
public class CloudworksNoticeToast implements IToast, IFancyFontRenderer {
    public static final int KIND_RECYCLE_OVERFLOW = 0;
    private static final ResourceLocation TEXTURE_TOASTS = new ResourceLocation("textures/gui/toasts.png");
    private final int kind;
    private final UUID leader;
    private final String leaderName, teamName;
    private long firstDrawTime;
    private boolean newDisplay = true;

    public CloudworksNoticeToast(int kind) {
        this(kind, null, "", "");
    }

    public CloudworksNoticeToast(int kind, UUID leader, String leaderName, String teamName) {
        this.kind = kind;
        this.leader = leader;
        this.leaderName = leaderName == null ? "" : leaderName;
        this.teamName = teamName == null ? "" : teamName;
    }

    @Override
    public Visibility draw(GuiToast toastGui, long delta) {
        if (newDisplay) {
            firstDrawTime = delta;
            newDisplay = false;
        }
        Minecraft minecraft = toastGui.getMinecraft();
        minecraft.getTextureManager().bindTexture(TEXTURE_TOASTS);
        GlStateManager.color(1.0F, 1.0F, 1.0F);
        toastGui.drawTexturedModalRect(0, 0, 0, 32, 160, 32);
        if (kind == CloudworksNetwork.Notice.TEAM_DISSOLVED && leader != null) {
            NetworkPlayerInfo info = minecraft.getConnection() == null ? null
                    : minecraft.getConnection().getPlayerInfo(leader);
            ResourceLocation skin = info == null ? DefaultPlayerSkin.getDefaultSkin(leader) : info.getLocationSkin();
            minecraft.getTextureManager().bindTexture(skin);
            toastGui.drawTexturedModalRect(8, 8, 8, 8, 8, 8);
            toastGui.drawTexturedModalRect(8, 8, 40, 8, 8, 8);
        }
        String title = kind == CloudworksNetwork.Notice.TEAM_DISSOLVED
                ? "cloudworks.toast.team_title" : "cloudworks.toast.recycle_title";
        String line = kind == KIND_RECYCLE_OVERFLOW
                ? "cloudworks.toast.recycle_overflow"
                : kind == CloudworksNetwork.Notice.TEAM_DISSOLVED
                ? "cloudworks.toast.team_dissolved" : "cloudworks.toast.generic";
        drawScrollingString(new TextComponentTranslation(title), 30, 7, TextAlignment.LEFT, -11534256, 122, 0, false);
        TextComponentTranslation message = kind == CloudworksNetwork.Notice.TEAM_DISSOLVED
                ? new TextComponentTranslation(line, teamName, leaderName)
                : new TextComponentTranslation(line);
        drawScrollingString(message, 30, 18, TextAlignment.LEFT, -16777216, 122, 0, false);
        RenderHelper.enableGUIStandardItemLighting();
        return delta - firstDrawTime < 5000L ? Visibility.SHOW : Visibility.HIDE;
    }

    @Override public int getXSize() { return 160; }
    @Override public FontRenderer getFont() { return Minecraft.getMinecraft().fontRenderer; }
}
