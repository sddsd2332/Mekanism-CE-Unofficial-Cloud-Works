package mekanism.cloudworks.common.recipe;

import java.util.Collections;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.CloudworksMachineRegistration;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;
import mekanism.common.MekanismFluids;
import mekanism.common.config.MekanismConfig;
import net.minecraftforge.fluids.*;

/** A virtual water source; native extraction quantities, power and tank size stay separate. */
public final class CloudworksPumpRecipeAdapter implements CloudworksMachineAdapter {
    private final int processes;
    public CloudworksPumpRecipeAdapter(int processes) { this.processes = processes; }
    @Override public boolean requiresEnvironmentNode() { return true; }
    public int processes() { return processes; }
    @Override public java.util.List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
        return CloudworksPumpRecipes.recipes(processes, false);
    }
}
