package mekanism.cloudworks.common;

import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.team.CloudworksTeamContext;
import mekanism.cloudworks.common.registries.CloudworksItems;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;
import java.util.UUID;
import mekanism.cloudworks.common.content.CloudworksTerminalHost;
import mekanism.cloudworks.common.content.CloudworksTerminalItemHost;
import mekanism.cloudworks.common.inventory.container.ContainerCloudInputPort;
import mekanism.cloudworks.common.inventory.container.ContainerCloudOutputPort;
import mekanism.cloudworks.common.inventory.container.ContainerCloudworksTeamRecovery;
import mekanism.cloudworks.common.inventory.ContainerCloudTeamJoin;
import mekanism.cloudworks.common.tile.TileEntityCloudDashboard;
import mekanism.cloudworks.common.tile.TileEntityCloudInput;
import mekanism.cloudworks.common.tile.TileEntityCloudOutput;
import mekanism.cloudworks.common.tile.TileEntityCloudworksTeamRecovery;
import mekanism.common.base.IGuiProvider;
import mekanism.common.inventory.container.item.ItemStackSlotAccess;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

public class CloudworksCommonProxy implements IGuiProvider, IGuiHandler {
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
            if (tile instanceof TileEntityCloudInput input) {
                return new ContainerCloudInputPort(player.inventory, input);
            }
        }
        if (id == 2) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof TileEntityCloudOutput output) {
                return new ContainerCloudOutputPort(player.inventory, output);
            }
        }
        if (id == 5) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof TileEntityCloudworksTeamRecovery recovery) {
                return new ContainerCloudworksTeamRecovery(player.inventory, recovery);
            }
        }
        if (id == 3) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof TileEntityCloudDashboard dashboard
                    && CloudworksEntry.canOpenDashboard(player, dashboard)) {
                return new ContainerCloudworks(player.inventory, dashboard);
            }
        }
        if (id == 4) {
            UUID teamOwner = CloudworksTeamContext.owner(player);
            if (teamOwner == null || !CloudworksTeamContext.canAccess(player, teamOwner)) return null;
            return new ContainerCloudworks(player.inventory, heldTerminalHost(player), teamOwner, true);
        }
        if (id == 6 && CloudworksTeamContext.owner(player) == null
                && player.getHeldItemMainhand().getItem() == CloudworksItems.TeamTerminal)
            return new ContainerCloudTeamJoin(player.inventory);
        return null;
    }

    /**
     * Terminal-slot host bound to the opener's held portable terminal (both
     * sides construct identically so the slots line up for vanilla sync).
     */
    public static CloudworksTerminalHost heldTerminalHost(EntityPlayer player) {
        ItemStack held = player.getHeldItemMainhand();
        if (held.getItem() != CloudworksItems.PortableTerminal
                && held.getItem() != CloudworksItems.TeamTerminal) return null;
        ItemStackSlotAccess itemAccess = new ItemStackSlotAccess(player.inventory,
                        EnumHand.MAIN_HAND, player.inventory.currentItem, held);
        return new CloudworksTerminalItemHost(player, itemAccess);
    }

    public boolean isValidServerGui(int id, TileEntity tile) {
        return id == 0 || id == 4 || id == 6 || id == 1 && tile instanceof TileEntityCloudInput
                || id == 2 && tile instanceof TileEntityCloudOutput
                || id == 5 && tile instanceof TileEntityCloudworksTeamRecovery
                || id == 3 && tile instanceof TileEntityCloudDashboard;
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
