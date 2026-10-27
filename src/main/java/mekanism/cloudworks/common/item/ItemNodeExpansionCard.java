package mekanism.cloudworks.common.item;

import mekanism.cloudworks.common.card.CloudworksCardEffect;
import mekanism.cloudworks.common.card.CloudworksCardEffects;
import mekanism.cloudworks.common.item.prefab.ItemCloudworksCard;

/** Existing subclasses may keep overriding the fixed per-card increment. */
public class ItemNodeExpansionCard extends ItemCloudworksCard {
    @Override
    public CloudworksCardEffect cardEffect() {
        int step = getNodeStep();
        return step == 1 ? CloudworksCardEffects.NODE_EXPANSION : CloudworksCardEffects.NODE_EXPANSION.withAmount(step);
    }

    public int getNodeStep() {
        return 1;
    }
}


