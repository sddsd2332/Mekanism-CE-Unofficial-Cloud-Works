package mekanism.cloudworks.common.inventory.container;

import javax.annotation.Nonnull;

import mekanism.cloudworks.common.tile.TileEntityCloudworksTeamRecovery;
import mekanism.cloudworks.common.network.CloudworksNetwork;
import mekanism.cloudworks.common.network.PacketCloudworksTeamRecovery;
import mekanism.common.inventory.container.MekanismTileContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IContainerListener;

/**
 * Team recovery container: no physical tile slots are exposed — the take-only
 * 8×8 dynamic resource grid is a virtual view synchronized through
 * {@link mekanism.cloudworks.common.network.PacketCloudworksTeamRecovery}.
 */
public class ContainerCloudworksTeamRecovery extends MekanismTileContainer<TileEntityCloudworksTeamRecovery> {
    private int pageOffset;
    private int sentVersion = Integer.MIN_VALUE;

    public ContainerCloudworksTeamRecovery(InventoryPlayer inventory, TileEntityCloudworksTeamRecovery tile) {
        super(tile, inventory);
    }

    @Override
    public boolean canInteractWith(@Nonnull EntityPlayer player) {
        return tile != null && tile.isUsableByPlayer(player) && tile.canPlayerAccess(player);
    }

    /** Keep the virtual grid synchronized like the real slots in this container. */
    @Override public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (tile == null || tile.getWorld() == null || tile.getWorld().isRemote
                || listeners.isEmpty() || sentVersion == tile.recoveryVersion()) return;
        for (IContainerListener listener : listeners)
            if (listener instanceof EntityPlayerMP player && canInteractWith(player))
                sendRecoveryPage(player, pageOffset);
    }

    public void sendRecoveryPage(EntityPlayerMP player, int offset) {
        PacketCloudworksTeamRecovery.State page =
                PacketCloudworksTeamRecovery.page(tile, windowId, Math.max(0, offset));
        pageOffset = page.offset();
        sendRecoveryState(player, page);
        sentVersion = tile.recoveryVersion();
    }

    protected void sendRecoveryState(EntityPlayerMP player, PacketCloudworksTeamRecovery.State state) {
        CloudworksNetwork.CHANNEL.sendTo(state, player);
    }
}
