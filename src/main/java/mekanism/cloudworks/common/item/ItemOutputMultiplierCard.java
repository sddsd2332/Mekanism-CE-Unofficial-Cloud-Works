package mekanism.cloudworks.common.item;

import mekanism.cloudworks.common.card.CloudworksCardEffect;
import mekanism.cloudworks.common.card.CloudworksCardEffects;
import mekanism.cloudworks.common.item.prefab.ItemCloudworksCard;

public class ItemOutputMultiplierCard extends ItemCloudworksCard {
    @Override public CloudworksCardEffect cardEffect() { return CloudworksCardEffects.OUTPUT_MULTIPLIER; }
}
