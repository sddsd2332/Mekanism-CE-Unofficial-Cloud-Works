package mekanism.cloudworks.api.environment;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.util.ResourceLocation;

/** Hand-written weather-axis declarations. */
public final class WeatherAxes {
    public static final EnvironmentAxis THUNDER =
            EnvironmentAxis.Builder.weather("thunder_environment").buildWeather();
    public static final EnvironmentAxis RAIN =
            EnvironmentAxis.Builder.weather("rain_environment").buildWeather();
    public static final List<EnvironmentAxis> ALL =
            Collections.unmodifiableList(Arrays.asList(THUNDER, RAIN));

    private WeatherAxes() {}

    public static List<EnvironmentAxis> all() { return ALL; }

    public static EnvironmentAxis byId(ResourceLocation id) {
        for (EnvironmentAxis axis : ALL) if (axis.id().equals(id)) return axis;
        return null;
    }
}
