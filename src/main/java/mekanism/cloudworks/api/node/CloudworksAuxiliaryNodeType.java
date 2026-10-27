package mekanism.cloudworks.api.node;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import java.util.ArrayList;
import mekanism.cloudworks.api.module.MachineModuleSource;
import mekanism.cloudworks.api.module.MachineModuleType;

/**
 * Public declaration for the fourth CloudWorks node family.
 *
 * <p>An auxiliary node does not process recipes or carry ordinary production
 * links. It supplies a frozen effect to selected machine nodes. AREA targeting
 * is geometry based, LINKED is represented by explicit target ids in the
 * line draft, and LINE_WIDE targets every eligible machine on the line.</p>
 */
public final class CloudworksAuxiliaryNodeType {
    public enum Targeting { LINKED, LINE_WIDE, AREA }

    @FunctionalInterface
    public interface TargetFilter extends Predicate<ItemStack> {
        @Override boolean test(ItemStack machine);
    }

    private final ResourceLocation id;
    private final String translationKey;
    private final Targeting targeting;
    private final ItemStack item;
    private final int range;
    private final int maxTargets;
    private final TargetFilter targetFilter;
    private final MachineModuleType<?> module;
    private final List<CloudworksPortDefinition> ports;

    private CloudworksAuxiliaryNodeType(ResourceLocation id, String translationKey,
            Targeting targeting, ItemStack item, int range, int maxTargets,
            TargetFilter targetFilter, MachineModuleType<?> module,
            List<CloudworksPortDefinition> ports) {
        this.id = Objects.requireNonNull(id);
        this.translationKey = Objects.requireNonNull(translationKey);
        this.targeting = Objects.requireNonNull(targeting);
        this.item = item == null ? ItemStack.EMPTY : item.copy();
        this.range = range;
        this.maxTargets = maxTargets;
        this.targetFilter = Objects.requireNonNull(targetFilter);
        this.module = Objects.requireNonNull(module);
        if (!module.acceptsSource(MachineModuleSource.AUXILIARY_NODE))
            throw new IllegalArgumentException("Module does not accept auxiliary sources");
        if (range < 0 || maxTargets < 0) throw new IllegalArgumentException("Invalid auxiliary limits");
        if (ports == null) this.ports = Collections.emptyList();
        else this.ports = Collections.unmodifiableList(new ArrayList<>(ports));
    }

    public static CloudworksAuxiliaryNodeType area(ResourceLocation id, String translationKey,
            ItemStack item, int range, int maxTargets, TargetFilter targetFilter,
            MachineModuleType<?> module, List<CloudworksPortDefinition> ports) {
        return new CloudworksAuxiliaryNodeType(id, translationKey, Targeting.AREA, item,
                range, maxTargets, targetFilter, module, ports);
    }

    public static CloudworksAuxiliaryNodeType area(ResourceLocation id, String translationKey,
            ItemStack item, int range, TargetFilter targetFilter, MachineModuleType<?> module) {
        return area(id, translationKey, item, range, 0, targetFilter, module, Collections.emptyList());
    }

    public static CloudworksAuxiliaryNodeType lineWide(ResourceLocation id, String translationKey,
            ItemStack item, int maxTargets, TargetFilter targetFilter,
            MachineModuleType<?> module, List<CloudworksPortDefinition> ports) {
        return new CloudworksAuxiliaryNodeType(id, translationKey, Targeting.LINE_WIDE, item,
                0, maxTargets, targetFilter, module, ports);
    }

    public static CloudworksAuxiliaryNodeType linked(ResourceLocation id, String translationKey,
            ItemStack item, int maxTargets, TargetFilter targetFilter,
            MachineModuleType<?> module, List<CloudworksPortDefinition> ports) {
        return new CloudworksAuxiliaryNodeType(id, translationKey, Targeting.LINKED, item,
                0, maxTargets, targetFilter, module, ports);
    }

    public CloudworksAuxiliaryNodeType withItem(ItemStack stack) {
        return new CloudworksAuxiliaryNodeType(id, translationKey, targeting, stack, range,
                maxTargets, targetFilter, module, ports);
    }

    public ResourceLocation id() { return id; }
    public String translationKey() { return translationKey; }
    public Targeting targeting() { return targeting; }
    public ItemStack item() { return item.copy(); }
    public int range() { return range; }
    public int maxTargets() { return maxTargets; }
    public TargetFilter targetFilter() { return targetFilter; }
    public MachineModuleType<?> module() { return module; }
    public List<CloudworksPortDefinition> ports() { return ports; }

    public boolean accepts(ItemStack machine) {
        return targetFilter.test(machine);
    }

}
