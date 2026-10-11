package mekanism.cloudworks.api;

import java.util.Locale;

/** Immutable owner-level environment snapshot used by virtual generators. */
public final class CloudworksGenerationContext {
    private final int simulatedHeight;
    private final int clockTick;
    private final int lavaHeatCards;
    private final boolean netherEnvironment;

    public CloudworksGenerationContext(int simulatedHeight, int clockTick, int lavaHeatCards,
                                       boolean netherEnvironment) {
        this.simulatedHeight = Math.max(0, simulatedHeight);
        this.clockTick = Math.max(1, Math.min(24000, clockTick));
        this.lavaHeatCards = Math.max(0, lavaHeatCards);
        this.netherEnvironment = netherEnvironment;
    }

    public int simulatedHeight() { return simulatedHeight; }
    public int clockTick() { return clockTick; }
    public int lavaHeatCards() { return lavaHeatCards; }
    public boolean netherEnvironment() { return netherEnvironment; }

    public static String timeOfDay(int clockTick) {
        int tick = Math.floorMod(clockTick - 1, 24000) + 1;
        if (tick <= 5000) return "morning";
        if (tick <= 10000) return "noon";
        if (tick <= 13800) return "dusk";
        if (tick <= 18000) return "evening";
        if (tick <= 22200) return "midnight";
        return "dawn";
    }

    public static String clockTime(int clockTick) {
        int minutes = (int) ((Math.floorMod((long) clockTick - 1, 24000L) * 1440 / 24000 + 360) % 1440);
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    public CloudworksGenerationContext nextTick() {
        return new CloudworksGenerationContext(simulatedHeight, clockTick == 24000 ? 1 : clockTick + 1,
                lavaHeatCards, netherEnvironment);
    }
}
