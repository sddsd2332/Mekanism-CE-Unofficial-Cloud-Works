package mekanism.cloudworks.common.item;

import mekanism.cloudworks.common.card.CloudworksCardEffect;
import mekanism.cloudworks.common.card.CloudworksCardEffects;
import mekanism.cloudworks.common.item.prefab.ItemCloudworksCard;

/** Raises the usable line ceiling without creating or enabling a production line. */
public class ItemLineExpansionCard extends ItemCloudworksCard {
    @Override
    public CloudworksCardEffect cardEffect() {
        return CloudworksCardEffects.LINE_EXPANSION;
    }
}


