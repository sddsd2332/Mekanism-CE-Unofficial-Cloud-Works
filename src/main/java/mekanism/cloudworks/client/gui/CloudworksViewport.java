package mekanism.cloudworks.client.gui;

/**
 * Pure coordinate math shared by rendering and hit testing.
 */
public final class CloudworksViewport {
    private double x, y, scale = 1;

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double scale() {
        return scale;
    }

    public double worldX(double screen) {
        return (screen - x) / scale;
    }

    public double worldY(double screen) {
        return (screen - y) / scale;
    }

    public void pan(double dx, double dy) {
        x += dx;
        y += dy;
    }

    public void center(double worldX, double worldY, double width, double height) {
        x = width / 2D - worldX * scale;
        y = height / 2D - worldY * scale;
    }

    public void zoom(double factor, double ax, double ay) {
        double wx = worldX(ax), wy = worldY(ay);
        scale = Math.max(.25, Math.min(2, scale * factor));
        x = ax - wx * scale;
        y = ay - wy * scale;
    }

    public void fit(double x0, double y0, double x1, double y1, double width, double height) {
        scale = Math.max(.25, Math.min(1, Math.min((width - 24) / Math.max(1, x1 - x0), (height - 24) / Math.max(1, y1 - y0))));
        x = (width - (x1 - x0) * scale) / 2 - x0 * scale;
        y = (height - (y1 - y0) * scale) / 2 - y0 * scale;
    }
}
