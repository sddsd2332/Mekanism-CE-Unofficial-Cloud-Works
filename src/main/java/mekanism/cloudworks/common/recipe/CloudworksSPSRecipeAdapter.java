package mekanism.cloudworks.common.recipe;

import java.util.Collections;
import mekanism.api.gas.GasStack;
import mekanism.common.MekanismFluids;
import mekanism.common.content.sps.SynchronizedSPSData;

public final class CloudworksSPSRecipeAdapter extends CloudworksMultiblockRecipeAdapter {
    public CloudworksSPSRecipeAdapter(int ticks) {
        super(Collections.singletonList(new CloudworksContractRecipeAdapter.Contract("sps",
                new CloudworksRecipeBatch()
                        .gas(new GasStack(MekanismFluids.Polonium, 1), SynchronizedSPSData.INPUT_PER_ANTIMATTER, "gas_input")
                        .output(new GasStack(MekanismFluids.Antimatter, 1), "gas_output")
                        .extraEnergy(SynchronizedSPSData.ENERGY_PER_INPUT * SynchronizedSPSData.INPUT_PER_ANTIMATTER / ticks), ticks)));
    }
}
