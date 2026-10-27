package mekanism.cloudworks.api.recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import mekanism.cloudworks.api.machine.CloudworksMachineRegistration;
import mekanism.cloudworks.api.machine.MachineSlotLayout;

/** Immutable machine and recipe input supplied while compiling a recipe contribution. */
public final class RecipeFreezeContext {
    private final CloudworksMachineRegistration machine;
    private final List<CloudworksRecipeSpec> baseRecipes;
    private final MachineSlotLayout basePorts;

    public RecipeFreezeContext(CloudworksMachineRegistration machine, List<CloudworksRecipeSpec> recipes,
            MachineSlotLayout ports) {
        this.machine = Objects.requireNonNull(machine);
        this.baseRecipes = Collections.unmodifiableList(new ArrayList<>(recipes));
        this.basePorts = ports == null ? new MachineSlotLayout().frozenCopy() : ports.frozenCopy();
    }

    public CloudworksMachineRegistration machine() { return machine; }
    public List<CloudworksRecipeSpec> baseRecipes() { return baseRecipes; }
    public MachineSlotLayout basePorts() { return basePorts.frozenCopy(); }
}
