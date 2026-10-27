package mekanism.cloudworks.api.module;
import java.util.Objects;
import mekanism.cloudworks.api.CloudworksAreaGeometry;

/** Shared server/client range geometry and display defaults. No client classes are referenced. */
public final class ModuleArea {
    public enum MergeMode { UNION, INDEPENDENT }
    private final int radius;
    private final double threshold;
    private final MergeMode merge;
    private final int fill, border;
    public ModuleArea(int radius, double threshold, MergeMode merge, int fill, int border) {
        if (radius < 0 || !Double.isFinite(threshold) || threshold < 0 || threshold > 1)
            throw new IllegalArgumentException("Invalid module area");
        this.radius = radius; this.threshold = threshold; this.merge = Objects.requireNonNull(merge);
        this.fill = fill; this.border = border;
    }
    public int radius() { return radius; }
    public double threshold() { return threshold; }
    public MergeMode mergeMode() { return merge; }
    public int fillArgb() { return fill; }
    public int borderArgb() { return border; }
    public CloudworksAreaGeometry.Rect bounds(int x, int y, int height, int radiusOverride) {
        return CloudworksAreaGeometry.square(x + CloudworksAreaGeometry.NODE_WIDTH / 2D, y + height / 2D,
                (radiusOverride > 0 ? radiusOverride : radius) * 2D);
    }
}
