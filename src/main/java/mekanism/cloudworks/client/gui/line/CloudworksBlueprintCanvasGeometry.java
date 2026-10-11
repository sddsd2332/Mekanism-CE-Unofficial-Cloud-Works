package mekanism.cloudworks.client.gui.line;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Geometry and interaction policy shared by the blueprint ghost and pending group. */
public final class CloudworksBlueprintCanvasGeometry {
    private CloudworksBlueprintCanvasGeometry() {}

    public static Bounds include(Bounds current, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return current;
        return current == null ? new Bounds(x, y, x + width, y + height)
                : new Bounds(Math.min(current.left, x), Math.min(current.top, y),
                        Math.max(current.right, x + width), Math.max(current.bottom, y + height));
    }

    public static boolean interactive(boolean waitingForBlueprint) {
        return !waitingForBlueprint;
    }

    public static Set<UUID> group(UUID group, Map<UUID, UUID> nodeGroups) {
        Set<UUID> selected = new LinkedHashSet<>();
        if (group == null) return selected;
        nodeGroups.forEach((node, owner) -> {
            if (group.equals(owner)) selected.add(node);
        });
        return selected;
    }

    public static final class Bounds {
        public final int left, top, right, bottom;
        private Bounds(int left, int top, int right, int bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        public int width() { return right - left; }
        public int height() { return bottom - top; }
    }
}
