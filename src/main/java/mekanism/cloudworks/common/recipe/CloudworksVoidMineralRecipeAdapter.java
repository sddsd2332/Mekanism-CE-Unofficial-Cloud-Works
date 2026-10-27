package mekanism.cloudworks.common.recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksOperation;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.service.CloudworksResourceCapacity;
import net.minecraft.item.ItemStack;

/** One uniformly chosen native ore template per physical machine cycle, with tier-sized output. */
public final class CloudworksVoidMineralRecipeAdapter extends CloudworksNativeRecipeAdapter<List<ItemStack>> {
    private final int amount;

    public CloudworksVoidMineralRecipeAdapter(List<ItemStack> candidates, int amount) {
        super(recipes(candidates));
        if (amount <= 0) throw new IllegalArgumentException("Invalid mineral output quantity");
        this.amount = amount;
    }

    private static Map<Integer, List<ItemStack>> recipes(List<ItemStack> candidates) {
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) continue;
            ItemStack template = candidate.copy();
            template.setCount(1);
            copy.add(template);
        }
        return copy.isEmpty() ? Collections.emptyMap()
                : Collections.singletonMap(0, Collections.unmodifiableList(copy));
    }

    @Override
    protected CloudworksRecipeBatch describe(List<ItemStack> candidates) {
        return new CloudworksRecipeBatch().chooseItemOutput(candidates, amount);
    }

}
