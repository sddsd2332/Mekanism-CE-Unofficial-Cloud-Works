package mekanism.cloudworks.client.gui.line;

import mekanism.cloudworks.common.data.CloudworksChannelNode.Mode;
import mekanism.common.MekanismBlocks;
import mekanism.common.block.states.BlockStateMachine.MachineType;
import net.minecraft.item.ItemStack;

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
}
