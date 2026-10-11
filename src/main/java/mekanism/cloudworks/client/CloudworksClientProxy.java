package mekanism.cloudworks.client;

import mekanism.cloudworks.client.gui.GuiCloudworksCloud;
import mekanism.cloudworks.common.CloudworksCommonProxy;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class CloudworksClientProxy extends CloudworksCommonProxy {
    @Override
    public Object getClientGui(int id, EntityPlayer player, World world, BlockPos pos) {
        if (id == 0) return new GuiCloudworksCloud(player.inventory, null);
        if (id == 3) {
            net.minecraft.tileentity.TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof mekanism.cloudworks.common.tile.TileEntityCloudDashboard dashboard) {
                return new GuiCloudworksCloud(player.inventory, dashboard);
            }
            return null;
        }
        if (id == 1) {
            net.minecraft.tileentity.TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof mekanism.cloudworks.common.tile.TileEntityCloudInput input) {
                return new mekanism.cloudworks.client.gui.GuiCloudInputPort(player, input);
            }
        }
        if (id == 2) {
            net.minecraft.tileentity.TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof mekanism.cloudworks.common.tile.TileEntityCloudOutput output) {
                return new mekanism.cloudworks.client.gui.GuiCloudOutputPort(player, output);
            }
        }
        return null;
    }
}

