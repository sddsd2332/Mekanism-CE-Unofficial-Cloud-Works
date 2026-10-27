package mekanism.cloudworks.api.environment;

import java.util.Objects;
import net.minecraft.util.ResourceLocation;

/**
 * Manual probability-item declaration layered on top of the frozen mob and
 * loot pipeline. Pack and addon authors use these to restore or add drops
 * the conservative stage-2/3 rulings refuse (modded conditions and
 * functions, equipment entries, loot added by other mods); the frozen
 * pipeline itself is never modified.
 *
 * <p>Targets:
 * <ul>
 * <li>{@code ENTITY}: rolls once per simulated kill of that entity,
 * regardless of which axis picked it.</li>
 * <li>{@code AXIS}: rolls once per simulated event under the axis — every
 * entity kill rolled for the axis and every chest-table roll of a
 * structure axis. Weather and dimension axes declare no loot events and
 * therefore never fire.</li>
 * </ul>
 *
 * <p>Each injection rolls independently: with probability {@code chance}
 * the item drops {@code [minCount, maxCount]} times, on top of whatever
 * the frozen tables produced for the same event. Injections never fire on
 * their own: an axis with an empty spawn table produces no kill events,
 * and an axis without chest tables produces no chest events.
 */
public final class CloudworksLootInjection {

    /** What one injection attaches to. */
    public enum Target { ENTITY, AXIS }

    private final Target target;
    private final ResourceLocation targetId;
    private final ResourceLocation item;
    private final int meta;
    private final int minCount;
    private final int maxCount;
    private final double chance;

    private CloudworksLootInjection(Target target, ResourceLocation targetId, ResourceLocation item,
                                    int meta, int minCount, int maxCount, double chance) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(item, "item");
        if (Double.isNaN(chance) || chance <= 0 || chance > 1)
            throw new IllegalArgumentException("Loot injection chance must be in (0, 1]: " + chance);
        if (meta < 0)
            throw new IllegalArgumentException("Loot injection metadata must be non-negative");
        if (minCount < 1)
            throw new IllegalArgumentException("Loot injection min count must be at least 1");
        if (maxCount < minCount)
            throw new IllegalArgumentException("Loot injection max count is below its min count");
        this.target = target;
        this.targetId = targetId;
        this.item = item;
        this.meta = meta;
        this.minCount = minCount;
        this.maxCount = maxCount;
        this.chance = chance;
    }

    /** Per-kill injection for one entity registry name ("zombie" defaults to "minecraft:zombie"). */
    public static CloudworksLootInjection entity(String entityName, ResourceLocation item,
                                                 int minCount, int maxCount, double chance) {
        return entity(entityName, item, 0, minCount, maxCount, chance);
    }

    /** Per-kill injection with item metadata. */
    public static CloudworksLootInjection entity(String entityName, ResourceLocation item, int meta,
                                                 int minCount, int maxCount, double chance) {
        Objects.requireNonNull(entityName, "entityName");
        return new CloudworksLootInjection(Target.ENTITY, qualified(entityName),
                Objects.requireNonNull(item, "item"), meta, minCount, maxCount, chance);
    }

    /** Per-event injection for one environment axis. */
    public static CloudworksLootInjection axis(ResourceLocation axisId, ResourceLocation item,
                                               int minCount, int maxCount, double chance) {
        return axis(axisId, item, 0, minCount, maxCount, chance);
    }

    /** Per-event injection for one environment axis with item metadata. */
    public static CloudworksLootInjection axis(ResourceLocation axisId, ResourceLocation item, int meta,
                                               int minCount, int maxCount, double chance) {
        return new CloudworksLootInjection(Target.AXIS, Objects.requireNonNull(axisId, "axisId"),
                Objects.requireNonNull(item, "item"), meta, minCount, maxCount, chance);
    }

    private static ResourceLocation qualified(String name) {
        return new ResourceLocation(name.indexOf(':') >= 0 ? name : "minecraft:" + name);
    }

    public Target target() { return target; }

    /** Entity registry name (ENTITY) or axis id (AXIS). */
    public ResourceLocation targetId() { return targetId; }

    public ResourceLocation item() { return item; }
    public int meta() { return meta; }
    public int minCount() { return minCount; }
    public int maxCount() { return maxCount; }

    /** Drop probability per event; in (0, 1]. */
    public double chance() { return chance; }

    /** Human-readable target for lifecycle error messages. */
    public String describeTarget() {
        return (target == Target.ENTITY ? "entity " : "axis ") + targetId;
    }
}
