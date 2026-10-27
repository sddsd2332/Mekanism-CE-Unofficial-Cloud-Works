package mekanism.cloudworks.client.gui.line;

import mekanism.cloudworks.common.data.CloudworksChannelNode.Mode;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.environment.EnvironmentAxisRegistry;
import mekanism.cloudworks.api.environment.DimensionAxes;
import mekanism.cloudworks.api.environment.BiomeAxes;
import mekanism.cloudworks.api.environment.WeatherAxes;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import mekanism.cloudworks.common.item.ItemEnvironmentCard;
import mekanism.common.MekanismBlocks;
import mekanism.common.block.states.BlockStateMachine.MachineType;
import net.minecraft.item.ItemStack;
import mekanism.cloudworks.common.registries.CloudworksItems;

/** The palette and canvas use the same Mekanism block icons for virtual boundaries. */
public final class CloudworksChannelIcons {
    private CloudworksChannelIcons() {
    }

    public static ItemStack stack(Mode mode) {
        return switch (mode) {
            case RECEIVE_INPUT -> new ItemStack(MekanismBlocks.QIO_IMPORTER);
            case TRANSFER_INPUT -> MachineType.PERSONAL_CHEST.getStack();
            case TRANSFER_OUTPUT -> MachineType.HYBRID_STORAGE.getStack();
            case SEND_OUTPUT -> new ItemStack(MekanismBlocks.QIO_EXPORTER);
        };
    }

    public static ItemStack stack(String archetype, Mode mode) {
        if (archetype == null || archetype.isEmpty()) return stack(mode);
        if (archetype.equals("cloudworks:infinite_resources"))
            return new ItemStack(CloudworksItems.InfiniteResourceCard);
        EnvironmentAxis axis = EnvironmentAxisRegistry.byIdString(archetype);
        if (axis != null)
            return ItemEnvironmentCard.bound(ItemEnvironmentCard.cardFor(axis), axis.id());
        return stack(mode);
    }

    public static ItemStack stack(String archetype, Mode mode, QIOResourceDescriptor resource) {
        if ("cloudworks:infinite_resources".equals(archetype) && resource != null) {
            ItemStack item = resource.resolve(QIOResourceCodecs.ITEM_STACK);
            if (item != null && !item.isEmpty()) return item;
        }
        return stack(archetype, mode);
    }
}
