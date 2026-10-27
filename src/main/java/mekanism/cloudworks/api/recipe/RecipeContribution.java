package mekanism.cloudworks.api.recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import mekanism.cloudworks.api.machine.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.api.environment.CloudworksGenerationPlan;
import mekanism.cloudworks.api.module.CloudworksMachineContext;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.cloudworks.api.module.ICustomMachineModule;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineSlotLayout;

/** Immutable machine-contract contribution produced by {@link ICustomMachineModule#onRecipes}. */
public final class RecipeContribution {
    public enum RecipeMode { NONE, REPLACE, APPEND, PARALLEL }
    public enum Installation { PER_NODE, PER_MACHINE }
    public static final int MAX_PORTS = 64;

    private final Installation installation;
    private final int required;
    private final RecipeMode mode;
    private final List<CloudworksRecipeSpec> recipes;
    private final MachineSlotLayout ports;
    private final String exclusiveGroup;
    private final boolean generatorOverride, thermalOverride;
    private final CloudworksEnergyGenerator generator;
    private final CloudworksThermalPlan thermal;
    private final MachineExecutionProfile profile;
    private final GenerationTransformer generationTransformer;

    @FunctionalInterface
    public interface GenerationTransformer {
        CloudworksGenerationPlan transform(CloudworksGenerationPlan original,
                CloudworksMachineContext machine, int operations, CloudworksGenerationContext environment);
    }

    private RecipeContribution(Installation installation, int required, RecipeMode mode,
            List<CloudworksRecipeSpec> recipes, MachineSlotLayout ports, String exclusiveGroup,
            boolean generatorOverride, CloudworksEnergyGenerator generator,
            boolean thermalOverride, CloudworksThermalPlan thermal, MachineExecutionProfile profile,
            GenerationTransformer generationTransformer) {
        this.installation = Objects.requireNonNull(installation);
        if (required < 1) throw new IllegalArgumentException("Module activation count must be positive");
        this.required = required;
        this.mode = Objects.requireNonNull(mode);
        List<CloudworksRecipeSpec> copy = new ArrayList<>(recipes);
        if (copy.contains(null)) throw new IllegalArgumentException("Null recipe contribution");
        this.recipes = Collections.unmodifiableList(copy);
        this.ports = Objects.requireNonNull(ports).frozenCopy();
        if (this.ports.slots().size() > MAX_PORTS) throw new IllegalArgumentException("Too many module ports");
        for (MachineSlotLayout.Slot port : this.ports.slots()) {
            if (port.id().length() > 128 || port.codecId().toString().length() > 128)
                throw new IllegalArgumentException("Module port identifier is too long");
        }
        if (exclusiveGroup != null && !exclusiveGroup.isEmpty()
                && (exclusiveGroup.length() > 128 || !exclusiveGroup.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")))
            throw new IllegalArgumentException("Invalid module exclusive group");
        this.exclusiveGroup = exclusiveGroup == null ? "" : exclusiveGroup;
        this.generatorOverride = generatorOverride;
        this.generator = generator;
        this.thermalOverride = thermalOverride;
        this.thermal = thermal;
        this.profile = profile;
        this.generationTransformer = generationTransformer;
    }

    public static RecipeContribution none() {
        return new RecipeContribution(Installation.PER_NODE, 1, RecipeMode.NONE,
                Collections.emptyList(), new MachineSlotLayout(), "", false, null, false, null, null, null);
    }

    public static RecipeContribution of(Installation installation, int required, RecipeMode mode,
            List<CloudworksRecipeSpec> recipes, MachineSlotLayout ports) {
        String group = mode == RecipeMode.REPLACE ? "cloudworks:primary" : "";
        return new RecipeContribution(installation, required, mode, recipes, ports, group,
                false, null, false, null, null, null);
    }

    public static RecipeContribution perNode(int required, RecipeMode mode,
            List<CloudworksRecipeSpec> recipes, MachineSlotLayout ports) {
        return of(Installation.PER_NODE, required, mode, recipes, ports);
    }

    public static RecipeContribution perMachine(int required, RecipeMode mode,
            List<CloudworksRecipeSpec> recipes, MachineSlotLayout ports) {
        return of(Installation.PER_MACHINE, required, mode, recipes, ports);
    }

    private RecipeContribution copy(String group, boolean generatorOverride,
            CloudworksEnergyGenerator generator, boolean thermalOverride, CloudworksThermalPlan thermal,
            MachineExecutionProfile profile, GenerationTransformer generationTransformer) {
        return new RecipeContribution(installation, required, mode, recipes, ports, group,
                generatorOverride, generator, thermalOverride, thermal, profile, generationTransformer);
    }

    public Installation installation() { return installation; }
    public int required() { return required; }
    public RecipeMode mode() { return mode; }
    public List<CloudworksRecipeSpec> recipes() { return recipes; }
    public MachineSlotLayout ports() { return ports; }
    public String exclusiveGroup() { return exclusiveGroup; }
    public boolean overridesGenerator() { return generatorOverride; }
    public CloudworksEnergyGenerator generator() { return generator; }
    public boolean overridesThermal() { return thermalOverride; }
    public CloudworksThermalPlan thermal() { return thermal; }
    public MachineExecutionProfile profile() { return profile; }
    public GenerationTransformer generationTransformer() { return generationTransformer; }

    public RecipeContribution withExclusiveGroup(String group) {
        return copy(group, generatorOverride, generator, thermalOverride, thermal, profile, generationTransformer);
    }

    public RecipeContribution withGenerator(CloudworksEnergyGenerator generator) {
        return copy(exclusiveGroup, true, Objects.requireNonNull(generator), thermalOverride, thermal, profile, generationTransformer);
    }

    public RecipeContribution withoutGenerator() {
        return copy(exclusiveGroup, true, null, thermalOverride, thermal, profile, generationTransformer);
    }

    public RecipeContribution withThermal(CloudworksThermalPlan thermal) {
        return copy(exclusiveGroup, generatorOverride, generator, true, Objects.requireNonNull(thermal), profile, generationTransformer);
    }

    public RecipeContribution withoutThermal() {
        return copy(exclusiveGroup, generatorOverride, generator, true, null, profile, generationTransformer);
    }

    public RecipeContribution withProfile(MachineExecutionProfile profile) {
        return copy(exclusiveGroup, generatorOverride, generator, thermalOverride, thermal,
                Objects.requireNonNull(profile), generationTransformer);
    }

    public RecipeContribution withGenerationTransformer(GenerationTransformer transformer) {
        return copy(exclusiveGroup, generatorOverride, generator, thermalOverride, thermal,
                profile, Objects.requireNonNull(transformer));
    }

    public boolean active(long installed, long machines) {
        return active(installed, machines, required, installation == Installation.PER_MACHINE);
    }

    public static boolean active(long installed, long machines, int required, boolean perMachine) {
        if (required < 1) throw new IllegalArgumentException("Module activation count must be positive");
        if (machines <= 0) return false;
        if (!perMachine) return installed >= required;
        return machines <= Long.MAX_VALUE / required && installed >= machines * required;
    }
}
