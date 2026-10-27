package mekanism.cloudworks.common.recipe;

import java.util.*;
import mekanism.api.qio.resource.*;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;
import mekanism.common.MekanismItems;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.*;

/** Physical biofuel prepays fluid units, not an instantaneous 200-tick energy burst. */
public final class CloudworksBioGeneratorAdapter implements CloudworksMachineAdapter {
    private final double generation;
    public CloudworksBioGeneratorAdapter(double generation) { this.generation = generation; }
    @Override public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
        QIOResourceDescriptor ethanol = QIOResourceCodecs.fluid(new FluidStack(
                Objects.requireNonNull(FluidRegistry.getFluid("bioethanol"), "bioethanol"), 1));
        return Arrays.asList(
                CloudworksRecipeSpec.builder("cloudworks:bioethanol").duration(1).input(ethanol, 1, "fluid_input").energyPerOperation(generation).build(),
                CloudworksRecipeSpec.builder("cloudworks:biofuel").duration(1)
                        .input(QIOResourceCodecs.item(new ItemStack(MekanismItems.BioFuel)), 1, "item_input")
                        .energyPerOperation(generation).prepaidFuel(ethanol, 200).build());
    }
}
