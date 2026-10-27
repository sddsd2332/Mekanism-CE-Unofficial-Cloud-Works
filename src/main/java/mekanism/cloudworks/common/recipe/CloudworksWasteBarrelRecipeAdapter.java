package mekanism.cloudworks.common.recipe;

import java.util.LinkedHashMap;
import java.util.Map;
import mekanism.api.gas.Gas;
import mekanism.api.gas.GasRegistry;
import mekanism.api.gas.GasStack;
import mekanism.common.lib.radiation.RadiationUtil;

/** Owner-thread snapshot of native radiation eligibility, blacklist, interval and decay quantity. */
public final class CloudworksWasteBarrelRecipeAdapter extends CloudworksNativeRecipeAdapter<Gas> {
    private final int multiplier;

    public CloudworksWasteBarrelRecipeAdapter(int multiplier) {
        super(CloudworksLiveMaps.live(() -> {
                Map<Gas, Gas> entries = new LinkedHashMap<>();
                for (Gas gas : GasRegistry.getRegisteredGasses())
                    if (gas.isRadiation()) entries.put(gas, gas);
                return entries;
            }));
        if (multiplier <= 0) throw new IllegalArgumentException("Invalid decay multiplier");
        this.multiplier = multiplier;
    }

    @Override
    protected CloudworksRecipeBatch describe(Gas gas) {
        GasStack template = new GasStack(gas, 1);
        long amount = RadiationUtil.canDecayInWasteBarrel(template)
                ? Math.min(Integer.MAX_VALUE, (long) RadiationUtil.getWasteBarrelDecayAmount() * multiplier) : 0;
        return new CloudworksRecipeBatch().gas(template, 1, "gas_input").decay(amount);
    }

    @Override
    protected int duration(Gas recipe, int fallback) {
        return RadiationUtil.getWasteBarrelProcessTicks();
    }
}
