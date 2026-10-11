package mekanism.cloudworks.client.gui;

/**
 * Resolution-independent bounds used by the Mekanism-backed Cloudworks screen.
 */
public final class CloudworksGuiLayout {
    public final int xSize, ySize, leftWidth, rightX, rightWidth;
    public final int canvasX, canvasY, canvasWidth, canvasHeight;
    public final int bottomY;
    public final int pickerTop = 83;
    public final int pickerHeight, resourcePageY, actionY, utilityY;
    public final int detailsTop = 105;
    public final int detailsHeight;

    private CloudworksGuiLayout(int xSize, int ySize, int leftWidth, int rightX, int rightWidth,
                                int canvasX, int canvasY, int canvasWidth, int canvasHeight, int bottomY) {
        this.xSize = xSize;
        this.ySize = ySize;
        this.leftWidth = leftWidth;
        this.rightX = rightX;
        this.rightWidth = rightWidth;
        this.canvasX = canvasX;
        this.canvasY = canvasY;
        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
        this.bottomY = bottomY;
        resourcePageY = Math.max(110, ySize - 146);
        pickerHeight = resourcePageY - pickerTop - 4;
        actionY = resourcePageY + 44;
        detailsHeight = actionY - 22 - detailsTop - 4;
        utilityY = Math.max(194, ySize - 36);
    }

    public static CloudworksGuiLayout calculate(int screenWidth, int screenHeight) {
        int width = Math.min(1120, Math.max(1, screenWidth - 12));
        int height = Math.min(610, Math.max(1, screenHeight - 12));
        int left = Math.min(105, Math.max(48, width / 5));
        int right = Math.min(190, Math.max(80, width / 3));
        int canvasX = left + 12;
        int rightX = width - right;
        int canvasWidth = Math.max(1, rightX - canvasX - 8);
        int canvasY = 48;
        int bottom = Math.max(canvasY + 60, height - 42);
        int canvasHeight = Math.max(40, bottom - canvasY - 10);
        return new CloudworksGuiLayout(width, height, left, rightX, width - rightX - 7,
                canvasX, canvasY, canvasWidth, canvasHeight, bottom);
    }

    public boolean containsCanvas(int x, int y) {
        return x >= canvasX && x < canvasX + canvasWidth && y >= canvasY && y < canvasY + canvasHeight;
    }
}
