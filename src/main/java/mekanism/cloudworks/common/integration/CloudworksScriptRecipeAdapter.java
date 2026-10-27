package mekanism.cloudworks.common.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.CloudworksMachineRegistration;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;

/** Immutable CraftTweaker contract adapter implemented solely with public recipe specs. */
final class CloudworksScriptRecipeAdapter implements CloudworksMachineAdapter {
    private final List<CloudworksRecipeSpec> recipes;

    CloudworksScriptRecipeAdapter(List<CloudworksRecipeSpec> recipes) {
        this.recipes = Collections.unmodifiableList(new ArrayList<>(recipes));
    }

    @Override
    public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
        return recipes;
    }
}
