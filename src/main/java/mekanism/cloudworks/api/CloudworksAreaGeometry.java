package mekanism.cloudworks.api;

import java.util.*;

/** Shared canvas geometry. Coordinates are independent of the viewport scale. */
public final class CloudworksAreaGeometry {
    public static final int NODE_WIDTH = 132, GRID = 24;
    public static final int NODE_BASE_HEIGHT = 57, PORT_ROW_HEIGHT = 12;
    private CloudworksAreaGeometry() {}

    /** Includes the reserved empty-port row, shared by layout, targeting and rendering. */
    public static int nodeHeight(int portCount) {
        if (portCount < 0) throw new IllegalArgumentException("Negative port count");
        return Math.addExact(NODE_BASE_HEIGHT, Math.multiplyExact(Math.max(1, portCount), PORT_ROW_HEIGHT));
    }

    public static final class Rect {
        public final double left, top, right, bottom;
        public Rect(double left, double top, double right, double bottom) {
            if (!Double.isFinite(left + top + right + bottom) || right < left || bottom < top)
                throw new IllegalArgumentException("Invalid area rectangle");
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        public double area() { return (right - left) * (bottom - top); }
    }

    public static Rect square(double centerX, double centerY, double size) {
        if (!Double.isFinite(size) || size < 0) throw new IllegalArgumentException("Invalid area size");
        return new Rect(centerX - size / 2D, centerY - size / 2D, centerX + size / 2D, centerY + size / 2D);
    }

    public static double intersectionRatio(Rect source, Rect machine) {
        double overlap = Math.max(0, Math.min(source.right, machine.right) - Math.max(source.left, machine.left))
                * Math.max(0, Math.min(source.bottom, machine.bottom) - Math.max(source.top, machine.top));
        return machine.area() == 0 ? 0 : overlap / machine.area();
    }
    public static boolean exceeds(Rect source, Rect machine, double threshold) {
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1)
            throw new IllegalArgumentException("Invalid coverage threshold");
        return machine.area() > 0 && intersectionRatio(source, machine) > threshold;
    }
    public static boolean majority(Rect source, Rect machine) { return exceeds(source, machine, .5); }

    /** One source must independently cover a majority; unrelated partial sources never combine. */
    public static boolean covered(Collection<Rect> sources, Rect machine) {
        for (Rect source : sources) if (majority(source, machine)) return true;
        return false;
    }

    /** Immutable candidate supplied by the caller's frozen capability/eligibility policy. */
    public static final class Target {
        public final UUID id;
        public final Rect bounds;
        public final boolean eligible;
        public Target(UUID id, Rect bounds, boolean eligible) {
            this.id = Objects.requireNonNull(id); this.bounds = Objects.requireNonNull(bounds); this.eligible = eligible;
        }
    }

    /** Selects targets independently against each source; partial coverages are never summed. */
    public static Set<UUID> targets(Collection<Rect> sources, Collection<Target> candidates, double threshold) {
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1)
            throw new IllegalArgumentException("Invalid coverage threshold");
        Set<UUID> result = new LinkedHashSet<>();
        for (Target candidate : candidates) {
            if (!candidate.eligible) continue;
            for (Rect source : sources) if (exceeds(source, candidate.bounds, threshold)) {
                result.add(candidate.id); break;
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public static final class Union {
        public final List<Rect> fill;
        /** Degenerate rectangles represent horizontal or vertical boundary segments. */
        public final List<Rect> edges;
        private Union(List<Rect> fill, List<Rect> edges) {
            this.fill = Collections.unmodifiableList(fill); this.edges = Collections.unmodifiableList(edges);
        }
    }

    /** Sweep slabs have disjoint interiors: alpha never accumulates in overlapping sources. */
    public static Union union(Collection<Rect> sources) {
        List<Rect> rectangles = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        SortedSet<Double> xs = new TreeSet<>();
        for (Rect rectangle : sources) {
            if (rectangle.area() == 0 || !unique.add(rectangle.left + ":" + rectangle.top + ":"
                    + rectangle.right + ":" + rectangle.bottom)) continue;
            rectangles.add(rectangle); xs.add(rectangle.left); xs.add(rectangle.right);
        }
        List<Rect> fill = new ArrayList<>(), edges = new ArrayList<>();
        Map<Double, List<double[]>> horizontal = new TreeMap<>(), vertical = new TreeMap<>();
        Double previous = null;
        for (double x : xs) {
            if (previous != null) {
                List<double[]> spans = new ArrayList<>();
                for (Rect rectangle : rectangles)
                    if (rectangle.left < x && rectangle.right > previous)
                        spans.add(new double[]{rectangle.top, rectangle.bottom});
                for (double[] span : merge(spans)) fill.add(new Rect(previous, span[0], x, span[1]));
            }
            previous = x;
        }
        for (Rect rectangle : rectangles) {
            boundary(rectangles, rectangle, true, true, vertical);
            boundary(rectangles, rectangle, true, false, vertical);
            boundary(rectangles, rectangle, false, true, horizontal);
            boundary(rectangles, rectangle, false, false, horizontal);
        }
        horizontal.forEach((y, spans) -> merge(spans).forEach(span -> edges.add(new Rect(span[0], y, span[1], y))));
        vertical.forEach((x, spans) -> merge(spans).forEach(span -> edges.add(new Rect(x, span[0], x, span[1]))));
        return new Union(fill, edges);
    }

    private static void boundary(List<Rect> rectangles, Rect own, boolean vertical, boolean low,
            Map<Double, List<double[]>> result) {
        double coordinate = vertical ? low ? own.left : own.right : low ? own.top : own.bottom;
        double from = vertical ? own.top : own.left, to = vertical ? own.bottom : own.right;
        List<double[]> occluded = new ArrayList<>();
        for (Rect other : rectangles) {
            if (other == own) continue;
            double a = vertical ? other.left : other.top, b = vertical ? other.right : other.bottom;
            if (low ? a < coordinate && b >= coordinate : a <= coordinate && b > coordinate)
                occluded.add(new double[]{Math.max(from, vertical ? other.top : other.left),
                        Math.min(to, vertical ? other.bottom : other.right)});
        }
        double cursor = from;
        for (double[] span : merge(occluded)) {
            if (span[0] > cursor) result.computeIfAbsent(coordinate, ignored -> new ArrayList<>()).add(new double[]{cursor, span[0]});
            cursor = Math.max(cursor, span[1]);
        }
        if (cursor < to) result.computeIfAbsent(coordinate, ignored -> new ArrayList<>()).add(new double[]{cursor, to});
    }

    private static List<double[]> merge(List<double[]> spans) {
        spans.sort(Comparator.comparingDouble(span -> span[0]));
        List<double[]> result = new ArrayList<>();
        for (double[] span : spans) {
            if (span[1] <= span[0]) continue;
            if (result.isEmpty() || result.get(result.size() - 1)[1] < span[0]) result.add(span.clone());
            else result.get(result.size() - 1)[1] = Math.max(result.get(result.size() - 1)[1], span[1]);
        }
        return result;
    }
}
