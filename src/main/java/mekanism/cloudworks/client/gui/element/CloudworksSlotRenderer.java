package mekanism.cloudworks.client.gui.element;

import java.awt.Color;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.slot.GuiSlot;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/** Draws slot selection and disabled overlays above their item icons. */
final class CloudworksSlotRenderer {

    private static final int SLOT_SIZE = 18;
    private static final long CYCLE_MILLIS = 2_400L;
    private static final int BORDER_ALPHA = 220;
    private static final int DISABLED_COLOR = 0xCC333333;

    private CloudworksSlotRenderer() {
    }

    static void draw(int x, int y, boolean selected, boolean enabled, boolean hovered) {
        if (!selected && enabled && !hovered) {
            return;
        }

        RenderState previous = RenderState.capture();
        GlStateManager.pushMatrix();
        try {
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA,
                    GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            if (hovered) {
                quad(buffer, x + 1, y + 1, SLOT_SIZE - 2, SLOT_SIZE - 2,
                        GuiSlot.DEFAULT_HOVER_COLOR);
            }
            if (!enabled) {
                quad(buffer, x + 1, y + 1, SLOT_SIZE - 2, SLOT_SIZE - 2, DISABLED_COLOR);
            }
            if (selected) {
                drawSelectionFrame(buffer, x, y);
            }
            tessellator.draw();
        } finally {
            GlStateManager.popMatrix();
            previous.restore();
        }
    }

    private static void drawSelectionFrame(BufferBuilder buffer, int x, int y) {
        int pixels = 4 * SLOT_SIZE;
        float phase = Math.floorMod(GuiElement.getMillis(), CYCLE_MILLIS)
                / (float) CYCLE_MILLIS;
        for (int index = 0; index < pixels; index++) {
            float hue = index / (float) pixels + phase;
            hue -= (float) Math.floor(hue);
            int color = (BORDER_ALPHA << 24) | (Color.HSBtoRGB(hue, 0.95F, 1F) & 0xFFFFFF);
            int edge = index / SLOT_SIZE;
            int offset = index % SLOT_SIZE;
            int pixelX = edge == 0 ? offset : edge == 1 ? SLOT_SIZE - 1
                    : edge == 2 ? SLOT_SIZE - 1 - offset : 0;
            int pixelY = edge == 0 ? 0 : edge == 1 ? offset
                    : edge == 2 ? SLOT_SIZE - 1 : SLOT_SIZE - 1 - offset;
            quad(buffer, x + pixelX, y + pixelY, 1, 1, color);
        }
    }

    private static void quad(BufferBuilder buffer, int x, int y, int width, int height, int color) {
        int red = color >> 16 & 0xFF;
        int green = color >> 8 & 0xFF;
        int blue = color & 0xFF;
        int alpha = color >>> 24;
        buffer.pos(x, y + height, 0).color(red, green, blue, alpha).endVertex();
        buffer.pos(x + width, y + height, 0).color(red, green, blue, alpha).endVertex();
        buffer.pos(x + width, y, 0).color(red, green, blue, alpha).endVertex();
        buffer.pos(x, y, 0).color(red, green, blue, alpha).endVertex();
    }

    private static final class RenderState {

        private final boolean texture;
        private final boolean lighting;
        private final boolean depth;
        private final boolean blend;
        private final int blendSourceRgb;
        private final int blendDestinationRgb;
        private final int blendSourceAlpha;
        private final int blendDestinationAlpha;

        private RenderState(boolean texture, boolean lighting, boolean depth, boolean blend,
                int blendSourceRgb, int blendDestinationRgb, int blendSourceAlpha,
                int blendDestinationAlpha) {
            this.texture = texture;
            this.lighting = lighting;
            this.depth = depth;
            this.blend = blend;
            this.blendSourceRgb = blendSourceRgb;
            this.blendDestinationRgb = blendDestinationRgb;
            this.blendSourceAlpha = blendSourceAlpha;
            this.blendDestinationAlpha = blendDestinationAlpha;
        }

        private static RenderState capture() {
            return new RenderState(GL11.glIsEnabled(GL11.GL_TEXTURE_2D),
                    GL11.glIsEnabled(GL11.GL_LIGHTING), GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                    GL11.glIsEnabled(GL11.GL_BLEND), GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                    GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                    GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
                    GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA));
        }

        private void restore() {
            GlStateManager.tryBlendFuncSeparate(blendSourceRgb, blendDestinationRgb,
                    blendSourceAlpha, blendDestinationAlpha);
            setTexture(texture);
            setLighting(lighting);
            setDepth(depth);
            setBlend(blend);
        }

        private static void setTexture(boolean enabled) {
            if (enabled) GlStateManager.enableTexture2D();
            else GlStateManager.disableTexture2D();
        }

        private static void setLighting(boolean enabled) {
            if (enabled) GlStateManager.enableLighting();
            else GlStateManager.disableLighting();
        }

        private static void setDepth(boolean enabled) {
            if (enabled) GlStateManager.enableDepth();
            else GlStateManager.disableDepth();
        }

        private static void setBlend(boolean enabled) {
            if (enabled) GlStateManager.enableBlend();
            else GlStateManager.disableBlend();
        }
    }
}
