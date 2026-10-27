package mekanism.cloudworks.common.util;

import mekanism.cloudworks.api.environment.EnvironmentAxis;
import mekanism.common.util.LangUtils;

public final class BiomeAxisNames {
    private BiomeAxisNames() {}
    public static String word(EnvironmentAxis axis) {
        if (LangUtils.canLocalize(axis.displayKey())) return LangUtils.localize(axis.displayKey());
        return axis.fallback().biome().getBiomeName();
    }
}
