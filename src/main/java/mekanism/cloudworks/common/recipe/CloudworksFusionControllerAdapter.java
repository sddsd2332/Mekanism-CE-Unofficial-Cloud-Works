package mekanism.cloudworks.common.recipe;

import java.util.*;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;

/** Identifies the builtin node-configurable controller without binding runtime state to item NBT. */
public final class CloudworksFusionControllerAdapter implements CloudworksMachineAdapter {
    private final CloudworksFusionReactorRecipeAdapter defaults;
    public CloudworksFusionControllerAdapter(boolean cooled) { defaults = new CloudworksFusionReactorRecipeAdapter(2, cooled); }
    @Override public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) { return defaults.freeze(registration); }
    @Override public CloudworksThermalPlan thermalPlan() { return defaults.thermalPlan(); }
}
