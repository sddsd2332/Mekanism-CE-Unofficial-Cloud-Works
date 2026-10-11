package mekanism.cloudworks.common.recipe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Startup-defined contracts use the same frozen planner as native recipes. */
public final class CloudworksContractRecipeAdapter extends CloudworksNativeRecipeAdapter<CloudworksContractRecipeAdapter.Contract> {
    public CloudworksContractRecipeAdapter(List<Contract> contracts) {
        super(index(contracts));
    }

    private static Map<String, Contract> index(List<Contract> contracts) {
        Map<String, Contract> result = new LinkedHashMap<>();
        for (Contract contract : contracts)
            if (result.putIfAbsent(contract.id, contract) != null)
                throw new IllegalArgumentException("Duplicate contract: " + contract.id);
        return result;
    }

    @Override
    protected CloudworksRecipeBatch describe(Contract recipe) { return recipe.batch; }

    @Override
    protected int duration(Contract recipe, int fallback) { return recipe.ticks; }

    public static final class Contract {
        public final String id;
        public final CloudworksRecipeBatch batch;
        public final int ticks;

        public Contract(String id, CloudworksRecipeBatch batch, int ticks) {
            if (id == null || id.trim().isEmpty() || batch == null || ticks < 1 || !batch.freeze().isPresent())
                throw new IllegalArgumentException("Invalid or unfreezable contract");
            this.id = id;
            this.batch = batch;
            this.ticks = ticks;
        }
    }
}
