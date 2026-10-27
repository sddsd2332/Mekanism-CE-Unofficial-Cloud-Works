package mekanism.cloudworks.api.machine;

import java.util.List;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;

/**
 * Addon adapter contract. It is invoked on the lifecycle thread and must return
 * only immutable recipe data; no adapter callback runs on a CloudWorks worker.
 */
@FunctionalInterface
public interface CloudworksMachineAdapter {
    List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration);
    /** Optional immutable thermal contract for an explicitly selected recipe family. */
    default CloudworksThermalPlan thermalPlan() { return null; }
    /** Physical execution requires coverage by an enabled environment source node. */
    default boolean requiresEnvironmentNode() { return false; }
}
