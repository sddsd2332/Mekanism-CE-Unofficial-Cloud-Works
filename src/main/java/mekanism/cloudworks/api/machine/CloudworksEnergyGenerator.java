package mekanism.cloudworks.api.machine;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.api.environment.CloudworksGenerationPlan;
import mekanism.cloudworks.api.module.CloudworksMachineContext;

/** Addon generator contract. Snapshotting happens on the lifecycle thread. */
@FunctionalInterface
public interface CloudworksEnergyGenerator {
    CloudworksGenerationPlan freeze(CloudworksMachineContext machine, int operations,
            CloudworksGenerationContext environment);
}
