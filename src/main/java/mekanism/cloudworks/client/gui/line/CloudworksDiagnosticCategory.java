package mekanism.cloudworks.client.gui.line;

/** Unknown states deliberately stay visible as OTHER instead of looking healthy. */
public enum CloudworksDiagnosticCategory {
    NORMAL(0xFF76CFA0), INPUT(0xFFE4D268), OUTPUT(0xFFE4A45D), POWER(0xFFE46B6B),
    RECIPE(0xFF999999), DISABLED(0xFF555555), OTHER(0xFFA591C1);
    public final int color;
    CloudworksDiagnosticCategory(int color) { this.color = color; }
    public static CloudworksDiagnosticCategory of(String state) {
        if (state == null) return OTHER;
        return switch (state) {
            case "READY", "RUNNING", "GENERATING", "DECAYING", "BUFFERED", "BUFFER_EMPTY" -> NORMAL;
            case "WAITING_INPUT", "WAITING_FUEL", "INSUFFICIENT_INPUT", "MISSING_INPUT",
                    "NO_MATCHING_FUEL", "INSUFFICIENT_FUEL", "WAITING_TEMPLATE", "WAITING_ENVIRONMENT", "WAITING_SUNLIGHT",
                    "WAITING_WIND", "WAITING_HEAT" -> INPUT;
            case "WAITING_OUTPUT", "DECAY_BLOCKED", "BUFFER_FULL" -> OUTPUT;
            case "WAITING_POWER" -> POWER;
            case "UNSUPPORTED_RECIPE", "NO_RECIPE", "NO_MATCHING_RECIPE" -> RECIPE;
            case "OFF", "LINE_STOPPED" -> DISABLED;
            case "NO_GENERATION", "WAITING_BLUEPRINT" -> OTHER;
            default -> OTHER;
        };
    }
}
