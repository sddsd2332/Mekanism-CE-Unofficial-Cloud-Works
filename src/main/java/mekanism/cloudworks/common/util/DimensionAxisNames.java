package mekanism.cloudworks.common.util;

import java.util.Locale;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import mekanism.common.util.LangUtils;

public final class DimensionAxisNames {
    private DimensionAxisNames() {}
    public static String word(EnvironmentAxis axis) {
        if (LangUtils.canLocalize(axis.displayKey())) return LangUtils.localize(axis.displayKey());
        return prettify(axis.fallback().dimension().getName());
    }
    public static String prettify(String name) {
        if (name == null || name.isEmpty()) return name;
        String[] words = name.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}
