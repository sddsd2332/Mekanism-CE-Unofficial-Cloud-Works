package mekanism.cloudworks.api.machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.minecraft.item.ItemStack;
import java.util.HashSet;
import java.util.Set;
import mekanism.cloudworks.api.recipe.CloudworksRecipeMode;

/**
 * Full declarative addon machine registration. Recipe modes are required unless
 * the explicit script-only deferred factory is used; an
 * environment {@link CloudworksEnergyGenerator} and a {@link CloudworksThermalPlan}
 * are optional and are frozen by the same lifecycle as the modes.
 */
public final class CloudworksMachineDefinition {
    private final ItemStack machine;
    private final MachineExecutionProfile profile;
    private final MachineSlotLayout slots;
    private final MachineIdentityPolicy identity;
    private final List<CloudworksRecipeMode> modes;
    private final CloudworksEnergyGenerator generator;
    private final CloudworksThermalPlan thermal;
    private final CloudworksMachineCategory category;
    private final boolean deferred;

    public CloudworksMachineDefinition(ItemStack machine, MachineExecutionProfile profile,
            MachineSlotLayout slots, MachineIdentityPolicy identity, List<CloudworksRecipeMode> modes) {
        this(machine, profile, slots, identity, modes, null, null, false, null);
    }

    public CloudworksMachineDefinition(ItemStack machine, MachineExecutionProfile profile,
            MachineSlotLayout slots, MachineIdentityPolicy identity, List<CloudworksRecipeMode> modes,
            CloudworksEnergyGenerator generator, CloudworksThermalPlan thermal) {
        this(machine, profile, slots, identity, modes, generator, thermal, false, null);
    }

    public CloudworksMachineDefinition(ItemStack machine, MachineExecutionProfile profile,
            MachineSlotLayout slots, MachineIdentityPolicy identity, List<CloudworksRecipeMode> modes,
            CloudworksEnergyGenerator generator, CloudworksThermalPlan thermal,
            CloudworksMachineCategory category) {
        this(machine, profile, slots, identity, modes, generator, thermal, false, category);
    }

    /** Script-only placeholder; load-complete must attach an execution contract before freeze. */
    public static CloudworksMachineDefinition deferred(ItemStack machine, MachineExecutionProfile profile,
            MachineSlotLayout slots, MachineIdentityPolicy identity) {
        return new CloudworksMachineDefinition(machine, profile, slots, identity,
                Collections.emptyList(), null, null, true, null);
    }

    private CloudworksMachineDefinition(ItemStack machine, MachineExecutionProfile profile,
            MachineSlotLayout slots, MachineIdentityPolicy identity, List<CloudworksRecipeMode> modes,
            CloudworksEnergyGenerator generator, CloudworksThermalPlan thermal, boolean deferred,
            CloudworksMachineCategory category) {
        if (machine == null || machine.isEmpty()) throw new IllegalArgumentException("Empty machine definition");
        this.machine = machine.copy();
        this.machine.setCount(1);
        this.profile = Objects.requireNonNull(profile);
        this.slots = Objects.requireNonNull(slots).frozenCopy();
        this.identity = Objects.requireNonNull(identity);
        List<CloudworksRecipeMode> copy = new ArrayList<>(Objects.requireNonNull(modes));
        Set<String> ids = new HashSet<>();
        for (CloudworksRecipeMode mode : copy)
            if (mode == null || !ids.add(mode.id())) throw new IllegalArgumentException("Duplicate recipe mode");
        this.modes = Collections.unmodifiableList(copy);
        this.generator = generator;
        this.thermal = thermal;
        this.category = category;
        this.deferred = deferred;
        if (copy.isEmpty() && generator == null && thermal == null && !deferred)
            throw new IllegalArgumentException("Machine definition has no modes, generator or thermal contract");
    }

    /** Returns a copy in an addon-owned machine palette page. */
    public CloudworksMachineDefinition category(CloudworksMachineCategory category) {
        return new CloudworksMachineDefinition(machine, profile, slots, identity, modes, generator, thermal,
                deferred, Objects.requireNonNull(category, "category"));
    }

    public CloudworksMachineDefinition category(String id, String translationKey) {
        return category(CloudworksMachineCategory.custom(id, translationKey));
    }

    public ItemStack machine() { return machine.copy(); }
    public MachineExecutionProfile profile() { return profile; }
    public MachineSlotLayout slots() { return slots; }
    public MachineIdentityPolicy identity() { return identity; }
    public List<CloudworksRecipeMode> modes() { return modes; }

    /** Environment generator declaration; null when the machine only runs recipes. */
    public CloudworksEnergyGenerator generator() { return generator; }

    /** Thermal contract declaration; null when the machine is not heat-driven. */
    public CloudworksThermalPlan thermal() { return thermal; }

    /** Optional addon-owned palette page; null selects the inferred core category. */
    public CloudworksMachineCategory category() { return category; }

    /** Effective category for declarations inspected before registration freeze. */
    public CloudworksMachineCategory resolvedCategory() {
        if (category != null) return category;
        if (generator != null) return CloudworksMachineCategory.GENERATION;
        return thermal != null ? CloudworksMachineCategory.OTHER : CloudworksMachineCategory.PROCESSING;
    }
}
