package mekanism.cloudworks.api.client;

import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;
import java.util.*;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL14;
import mekanism.cloudworks.api.CloudworksAreaGeometry;

/**
 * Client-only area overlay renderer. Input coordinates use the caller's current canvas transform.
 * Compile/cache a union when geometry changes; drawing does not reinterpret gameplay targeting.
 */
@SideOnly(Side.CLIENT)
public final class CloudworksAreaRenderer {
    private CloudworksAreaRenderer() {}

    /**
     * Shared frozen client projection for every area effect. Nether sources,
     * auxiliary nodes and future area modules use this same union and target
     * selection path.
     */
    public static Overlay compile(Collection<CloudworksAreaGeometry.Rect> areas,
            Collection<CloudworksAreaGeometry.Target> candidates, double threshold) {
        Objects.requireNonNull(areas, "areas");
        Objects.requireNonNull(candidates, "candidates");
        return new Overlay(CloudworksAreaGeometry.union(areas),
                CloudworksAreaGeometry.targets(areas, candidates, threshold));
    }

    /**
     * Compiles independent area overlays without unioning or merging their
     * rectangles. This is used by effects whose individual source boundaries
     * carry meaning, such as auxiliary heat sinks.
     */
    public static List<Overlay> compileIndependent(Collection<CloudworksAreaGeometry.Rect> areas,
            Collection<CloudworksAreaGeometry.Target> candidates, double threshold) {
        Objects.requireNonNull(areas, "areas");
        Objects.requireNonNull(candidates, "candidates");
        List<Overlay> result = new ArrayList<>();
        for (CloudworksAreaGeometry.Rect area : areas)
            result.add(new Overlay(CloudworksAreaGeometry.union(Collections.singletonList(area)),
                    CloudworksAreaGeometry.targets(Collections.singletonList(area), candidates, threshold)));
        return Collections.unmodifiableList(result);
    }

    public static final class Overlay {
        private final CloudworksAreaGeometry.Union union;
        private final Set<UUID> targets;

        private Overlay(CloudworksAreaGeometry.Union union, Set<UUID> targets) {
            this.union = union;
            this.targets = Collections.unmodifiableSet(new LinkedHashSet<>(targets));
        }

        public CloudworksAreaGeometry.Union union() { return union; }
        public Set<UUID> targets() { return targets; }
    }

    public static void draw(Overlay overlay, int fillArgb, int borderArgb, double borderWidth) {
        Objects.requireNonNull(overlay, "overlay");
        draw(overlay.union(), fillArgb, borderArgb, borderWidth);
    }

    public static void draw(CloudworksAreaGeometry.Union union, int fillArgb, int borderArgb, double borderWidth) {
        if (!Double.isFinite(borderWidth) || borderWidth < 0) throw new IllegalArgumentException("Invalid area border width");
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false); GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            batch(union.fill, fillArgb, 0);
            if (borderWidth > 0) batch(union.edges, borderArgb, borderWidth);
        } finally {
            GL11.glPopAttrib();
        }
    }

    private static void batch(List<CloudworksAreaGeometry.Rect> rectangles, int argb, double edgeWidth) {
        if (rectangles.isEmpty() || (argb >>> 24) == 0) return;
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (CloudworksAreaGeometry.Rect rectangle : rectangles) {
            double left = rectangle.left, right = rectangle.right, top = rectangle.top, bottom = rectangle.bottom;
            if (left == right) { left -= edgeWidth / 2; right += edgeWidth / 2; }
            if (top == bottom) { top -= edgeWidth / 2; bottom += edgeWidth / 2; }
            vertex(buffer, left, bottom, argb); vertex(buffer, right, bottom, argb);
            vertex(buffer, right, top, argb); vertex(buffer, left, top, argb);
        }
        tessellator.draw();
    }
    private static void vertex(BufferBuilder buffer, double x, double y, int argb) {
        buffer.pos(x, y, 0).color(argb >>> 16 & 255, argb >>> 8 & 255, argb & 255, argb >>> 24).endVertex();
    }
}
