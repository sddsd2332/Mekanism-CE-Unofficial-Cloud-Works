package mekanism.cloudworks.common.util;

import mekanism.cloudworks.api.environment.EnvironmentAxis;
import mekanism.common.util.LangUtils;

public final class WeatherAxisNames {
    private WeatherAxisNames() {}
    public static String word(EnvironmentAxis axis) {
        return LangUtils.canLocalize(axis.displayKey())
                ? LangUtils.localize(axis.displayKey()) : axis.id().getPath();
    }
}
