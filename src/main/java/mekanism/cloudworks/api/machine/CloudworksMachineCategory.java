package mekanism.cloudworks.api.machine;

import java.util.Objects;
import net.minecraft.util.ResourceLocation;

/** Stable palette category declaration for a machine definition. */
public final class CloudworksMachineCategory {

    public static final CloudworksMachineCategory PROCESSING =
            new CloudworksMachineCategory("cloudworks:processing", "cloudworks.editor.palette_processing", true);
    public static final CloudworksMachineCategory GENERATION =
            new CloudworksMachineCategory("cloudworks:generation", "cloudworks.editor.palette_generation", true);
    public static final CloudworksMachineCategory OTHER =
            new CloudworksMachineCategory("cloudworks:other", "cloudworks.editor.palette_other", true);
    public static final CloudworksMachineCategory SUPPORT =
            new CloudworksMachineCategory("cloudworks:support", "cloudworks.editor.palette_support", true);

    private final String id;
    private final String translationKey;

    private CloudworksMachineCategory(String id, String translationKey, boolean builtIn) {
        this.id = validateId(id, builtIn);
        if (translationKey == null || translationKey.trim().isEmpty() || translationKey.length() > 256)
            throw new IllegalArgumentException("Invalid machine category translation key");
        this.translationKey = translationKey;
    }

    /** Declares an addon-owned page. The id must be namespaced by the declaring addon. */
    public static CloudworksMachineCategory custom(String id, String translationKey) {
        return new CloudworksMachineCategory(id, translationKey, false);
    }

    private static String validateId(String value, boolean builtIn) {
        if (value == null || value.length() > 128)
            throw new IllegalArgumentException("Invalid machine category id");
        ResourceLocation parsed;
        try {
            parsed = new ResourceLocation(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid machine category id: " + value, invalid);
        }
        if (!parsed.toString().equals(value))
            throw new IllegalArgumentException("Machine category id must be normalized: " + value);
        if (!builtIn && ("cloudworks".equals(parsed.getNamespace()) || "mekanism".equals(parsed.getNamespace())))
            throw new IllegalArgumentException("Reserved machine category namespace: " + parsed.getNamespace());
        return value;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return translationKey;
    }

    public boolean sameDefinition(CloudworksMachineCategory other) {
        return other != null && id.equals(other.id) && translationKey.equals(other.translationKey);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CloudworksMachineCategory category && sameDefinition(category);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, translationKey);
    }
}
