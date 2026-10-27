package mekanism.cloudworks.common.item;

import mekanism.cloudworks.common.card.CloudworksCardEffect;
import mekanism.cloudworks.common.card.CloudworksCardEffects;
import mekanism.cloudworks.common.item.prefab.ItemCloudworksCard;

public final class ItemWarehouseCapacityCard extends ItemCloudworksCard {
    @Override
    public CloudworksCardEffect cardEffect() {
        return CloudworksCardEffects.WAREHOUSE_CAPACITY;
    }
}
