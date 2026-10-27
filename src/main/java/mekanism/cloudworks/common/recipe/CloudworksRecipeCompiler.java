package mekanism.cloudworks.common.recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.CloudworksMachineRegistration;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;

/** Converts an addon API declaration into the internal immutable recipe snapshot. */
public final class CloudworksRecipeCompiler {
    private CloudworksRecipeCompiler() {}

    public static Optional<CloudworksFrozenRecipeView> capture(CloudworksMachineAdapter adapter,
            CloudworksMachineRegistration registration) {
        return CloudworksFrozenRecipeView.capture(() -> {
            List<CloudworksRecipeSpec> specs = adapter.freeze(registration);
            if (specs == null) return null;
            List<CloudworksFrozenRecipeView.Entry> entries = new ArrayList<>(specs.size());
            for (CloudworksRecipeSpec spec : specs) {
                if (spec == null) return null;
                CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
                for (CloudworksRecipeSpec.Ingredient input : spec.inputs())
                    batch.matcher(input.matcher(), input.amount(), input.port(), false);
                for (CloudworksRecipeSpec.Ingredient template : spec.configuration())
                    batch.matcher(template.matcher(), template.amount(), template.port(), true);
                for (CloudworksRecipeSpec.Output output : spec.outputs()) {
                    if (output.scalable()) batch.output(output.descriptor(), output.amount(), output.port());
                    else batch.unscaledOutput(output.descriptor(), output.amount(), output.port());
                }
                for (CloudworksRecipeSpec.Output output : spec.chanceOutputs())
                    batch.chanceOutput(output.descriptor(), output.amount(), output.chance(), output.port());
                if (!spec.choiceOutputs().isEmpty())
                    batch.chooseResourceOutput(spec.choiceOutputs(), spec.choiceAmount());
                if (spec.sunlightRequired()) batch.requireSunlight();
                if (spec.decayAmount() >= 0) batch.decay(spec.decayAmount());
                if (!spec.outputMultiplierEnabled()) batch.unscaledOutputs();
                if (spec.extraEnergyPerTick() > 0) batch.extraEnergy(spec.extraEnergyPerTick());
                if (spec.energyPerOperation() > 0) batch.energy(spec.energyPerOperation());
                Optional<CloudworksRecipeBatch.FrozenView> frozen = batch.freeze();
                if (!frozen.isPresent()) return null;
                String identity = spec.id().matches("native_[0-9]+") ? "" : spec.id();
                entries.add(new CloudworksFrozenRecipeView.Entry(frozen.get(), spec.duration(),
                        identity, spec));
            }
            return entries;
        });
    }

}
