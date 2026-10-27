package mekanism.cloudworks.client.gui.element;

import java.nio.IntBuffer;
import mekanism.client.gui.IGuiWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Window-local clipping, including nested palettes, without changing Mekanism. */
final class CloudworksGuiScissor {
    private static final ThreadLocal<IntBuffer> QUERY =
            ThreadLocal.withInitial(CloudworksGuiScissor::queryBuffer);

    private CloudworksGuiScissor() {}

    static IntBuffer queryBuffer() {
        // LWJGL 2 requires 16 remaining ints, including for the four-value SCISSOR_BOX.
        return BufferUtils.createIntBuffer(16);
    }

    static void clipped(IGuiWrapper gui, float minX, float minY, float maxX, float maxY, Runnable draw) {
        Minecraft minecraft = Minecraft.getMinecraft();
        ScaledResolution resolution = new ScaledResolution(minecraft);
        double scaleX = minecraft.displayWidth / (double) resolution.getScaledWidth();
        double scaleY = minecraft.displayHeight / (double) resolution.getScaledHeight();
        int left = (int) Math.floor((gui.getLeft() + minX) * scaleX);
        int right = (int) Math.ceil((gui.getLeft() + maxX) * scaleX);
        int bottom = (int) Math.floor(minecraft.displayHeight - (gui.getTop() + maxY) * scaleY);
        int top = (int) Math.ceil(minecraft.displayHeight - (gui.getTop() + minY) * scaleY);
        clipped(left, bottom, right - left, top - bottom, draw);
    }

    /** Pixel-space entrance also exercised by the offscreen OpenGL probe. */
    static void clipped(int x, int y, int width, int height, Runnable draw) {
        if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
            IntBuffer previous = QUERY.get();
            previous.clear();
            GL11.glGetInteger(GL11.GL_SCISSOR_BOX, previous);
            int right = Math.min(x + width, previous.get(0) + previous.get(2));
            int top = Math.min(y + height, previous.get(1) + previous.get(3));
            x = Math.max(x, previous.get(0));
            y = Math.max(y, previous.get(1));
            width = Math.max(0, right - x);
            height = Math.max(0, top - y);
        }
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        try {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(x, y, Math.max(0, width), Math.max(0, height));
            draw.run();
        } finally {
            GL11.glPopAttrib();
        }
    }
}
