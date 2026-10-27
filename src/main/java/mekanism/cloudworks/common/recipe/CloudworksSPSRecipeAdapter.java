package mekanism.cloudworks.common.recipe;

import java.util.*;
import mekanism.api.gas.GasStack;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;
import mekanism.common.MekanismFluids;
import mekanism.common.content.sps.SynchronizedSPSData;

/** Both builtin and CRT SPS use the native energy/input conversion and prepaid progress. */
public final class CloudworksSPSRecipeAdapter implements CloudworksMachineAdapter {
    public CloudworksSPSRecipeAdapter(int ticks) {
        if (ticks < 1) throw new IllegalArgumentException("Invalid SPS compatibility duration");
    }
    @Override public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
        return Collections.singletonList(CloudworksRecipeSpec.builder("cloudworks:sps").duration(1)
                .input(QIOResourceCodecs.gas(new GasStack(MekanismFluids.Polonium, 1)), 1, "gas_input")
                .unscaledOutput(QIOResourceCodecs.gas(new GasStack(MekanismFluids.Antimatter, 1)), 1, "gas_output")
                .powerDriven(SynchronizedSPSData.ENERGY_PER_INPUT, SynchronizedSPSData.INPUT_PER_ANTIMATTER).build());
    }
}
