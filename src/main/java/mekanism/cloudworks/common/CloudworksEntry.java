package mekanism.cloudworks.common;

import mekanism.cloudworks.api.CloudworksEnabled;
import mekanism.cloudworks.common.capability.CloudworksCapabilities;
import mekanism.cloudworks.common.tile.TileEntityCloudDashboard;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.common.util.SecurityUtils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentTranslation;

/**
 * Portable terminals open the player's cloud; dashboards use their block owner
 * and Mekanism security policy. Both require the opener's cloudworks capability.
 */
public final class CloudworksEntry {
    private CloudworksEntry() {
    }

    /**
     * Server-thread open of the player's own cloudworks GUI.
     */
    public static boolean open(EntityPlayer player) {
        if (player.world.isRemote) return false;
        if (!(player instanceof EntityPlayerMP serverPlayer)) return false;
        CloudworksEnabled capability = serverPlayer.getCapability(CloudworksCapabilities.ENABLED, null);
        if (capability == null || !capability.isEnabled()) {
            serverPlayer.sendMessage(new TextComponentTranslation("chat.cloudworks.not_enabled"));
            return false;
        }
        serverPlayer.openGui(Cloudworks.instance, 0, serverPlayer.world, 0, 0, 0);
        return true;
    }

    /**
     * Server-thread open of the dashboard tile GUI (same entrance permit, tile host).
     */
    public static boolean openTile(EntityPlayer player, net.minecraft.util.math.BlockPos pos) {
        if (player.world.isRemote) return false;
        if (!(player instanceof EntityPlayerMP serverPlayer)) return false;
        if (!(player.world.getTileEntity(pos) instanceof TileEntityCloudDashboard dashboard)
                || !canOpenDashboard(player, dashboard)) return false;
        serverPlayer.openGui(Cloudworks.instance, 3, serverPlayer.world, pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    public static boolean canOpenDashboard(EntityPlayer player, TileEntityCloudDashboard dashboard) {
        if (player.world.isRemote || !dashboard.available() || player.world != dashboard.getWorld()
                || player.getDistanceSq(dashboard.getPos()) > 64) return false;
        CloudworksEnabled capability = player.getCapability(CloudworksCapabilities.ENABLED, null);
        if (capability == null || !capability.isEnabled()) {
            player.sendMessage(new TextComponentTranslation("chat.cloudworks.not_enabled"));
            return false;
        }
        try {
            CloudworksPlayerDataManager.INSTANCE.claimDashboard(dashboard, player.getUniqueID());
        } catch (RuntimeException failure) {
            player.sendMessage(new TextComponentTranslation("cloudworks.error.storage"));
            return false;
        }
        if (!SecurityUtils.canAccess(player, dashboard)) {
            SecurityUtils.displayNoAccess(player);
            return false;
        }
        return true;
    }
}
