package mekanism.cloudworks.common;

import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.registries.CloudworksItems;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

public class CloudworksCommonProxy implements mekanism.common.base.IGuiProvider, IGuiHandler {
    public void preInit() {
    }

    public Object getClientGui(int id, EntityPlayer player, World world, BlockPos pos) {
        return null;
    }

    public Container getServerGui(int id, EntityPlayer player, World world, BlockPos pos) {
        if (id == 0) {
            return new ContainerCloudworks(player.inventory, heldTerminalHost(player));
        }
        if (id == 1) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof mekanism.cloudworks.common.tile.TileEntityCloudInput input) {
                return new mekanism.cloudworks.common.inventory.container.ContainerCloudInputPort(player.inventory, input);
            }
        }
        if (id == 2) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof mekanism.cloudworks.common.tile.TileEntityCloudOutput output) {
                return new mekanism.cloudworks.common.inventory.container.ContainerCloudOutputPort(player.inventory, output);
            }
        }
        if (id == 3) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof mekanism.cloudworks.common.tile.TileEntityCloudDashboard dashboard
                    && CloudworksEntry.canOpenDashboard(player, dashboard)) {
                return new ContainerCloudworks(player.inventory, dashboard);
            }
        }
        return null;
    }

    /**
     * Terminal-slot host bound to the opener's held portable terminal (both
     * sides construct identically so the slots line up for vanilla sync).
     */
    public static mekanism.cloudworks.common.content.CloudworksTerminalHost heldTerminalHost(EntityPlayer player) {
        net.minecraft.item.ItemStack held = player.getHeldItemMainhand();
        if (held.getItem() != CloudworksItems.PortableTerminal) return null;
        mekanism.common.inventory.container.item.ItemStackSlotAccess itemAccess = new mekanism.common.inventory.container.item.ItemStackSlotAccess(player.inventory,
                        net.minecraft.util.EnumHand.MAIN_HAND, player.inventory.currentItem, held);
        return new mekanism.cloudworks.common.content.CloudworksTerminalItemHost(player, itemAccess);
    }

    public boolean isValidServerGui(int id, TileEntity tile) {
        return id == 0 || id == 1 && tile instanceof mekanism.cloudworks.common.tile.TileEntityCloudInput
                || id == 2 && tile instanceof mekanism.cloudworks.common.tile.TileEntityCloudOutput
                || id == 3 && tile instanceof mekanism.cloudworks.common.tile.TileEntityCloudDashboard;
    }

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return getServerGui(id, player, world, new BlockPos(x, y, z));
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return getClientGui(id, player, world, new BlockPos(x, y, z));
    }
}
