package mekanism.cloudworks.client.toast;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.toasts.GuiToast;
import net.minecraft.client.gui.toasts.IToast;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;
import mekanism.client.render.IFancyFontRenderer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Cloudworks notice toast, AE2-style: vanilla toast texture, fixed text,
 * five seconds. Fired by the server notice packet (recycle overflow etc.).
 */
@SideOnly(Side.CLIENT)
public class CloudworksNoticeToast implements IToast, IFancyFontRenderer {
    public static final int KIND_RECYCLE_OVERFLOW = 0;
    private static final ResourceLocation TEXTURE_TOASTS = new ResourceLocation("textures/gui/toasts.png");
    private final int kind;
    private long firstDrawTime;
    private boolean newDisplay = true;

    public CloudworksNoticeToast(int kind) {
        this.kind = kind;
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
        String title = "cloudworks.toast.recycle_title";
        String line = kind == KIND_RECYCLE_OVERFLOW
                ? "cloudworks.toast.recycle_overflow" : "cloudworks.toast.generic";
        drawScrollingString(new TextComponentTranslation(title), 30, 7, TextAlignment.LEFT, -11534256, 122, 0, false);
        drawScrollingString(new TextComponentTranslation(line), 30, 18, TextAlignment.LEFT, -16777216, 122, 0, false);
        RenderHelper.enableGUIStandardItemLighting();
        return delta - firstDrawTime < 5000L ? Visibility.SHOW : Visibility.HIDE;
    }

    @Override public int getXSize() { return 160; }
    @Override public FontRenderer getFont() { return Minecraft.getMinecraft().fontRenderer; }
}
