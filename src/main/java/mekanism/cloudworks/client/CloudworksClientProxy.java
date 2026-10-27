package mekanism.cloudworks.client;

import mekanism.cloudworks.client.gui.GuiCloudworksCloud;
import mekanism.cloudworks.common.CloudworksCommonProxy;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import mekanism.cloudworks.client.gui.GuiCloudInputPort;
import mekanism.cloudworks.client.gui.GuiCloudOutputPort;
import mekanism.cloudworks.client.gui.GuiCloudTeamJoin;
import mekanism.cloudworks.client.gui.GuiCloudworksTeamRecovery;
import mekanism.cloudworks.common.tile.TileEntityCloudDashboard;
import mekanism.cloudworks.common.tile.TileEntityCloudInput;
import mekanism.cloudworks.common.tile.TileEntityCloudOutput;
import mekanism.cloudworks.common.tile.TileEntityCloudworksTeamRecovery;
import net.minecraft.tileentity.TileEntity;

@SideOnly(Side.CLIENT)
public class CloudworksClientProxy extends CloudworksCommonProxy {
    @Override
    public Object getClientGui(int id, EntityPlayer player, World world, BlockPos pos) {
        if (id == 0 || id == 4) return new GuiCloudworksCloud(player.inventory, null);
        if (id == 6) return new GuiCloudTeamJoin(player.inventory);
        if (id == 3) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof TileEntityCloudDashboard dashboard) {
                return new GuiCloudworksCloud(player.inventory, dashboard);
            }
            return null;
        }
        if (id == 1) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof TileEntityCloudInput input) {
                return new GuiCloudInputPort(player, input);
            }
        }
        if (id == 2) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof TileEntityCloudOutput output) {
                return new GuiCloudOutputPort(player, output);
            }
        }
        if (id == 5) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof TileEntityCloudworksTeamRecovery recovery) {
                return new GuiCloudworksTeamRecovery(player, recovery);
            }
        }
        return null;
    }
}
