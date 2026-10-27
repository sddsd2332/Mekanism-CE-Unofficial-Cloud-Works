package mekanism.cloudworks.common.block;

import mekanism.api.IMekWrench;
import mekanism.cloudworks.common.Cloudworks;
import mekanism.cloudworks.common.content.CloudworksOwnerPort;
import mekanism.cloudworks.common.item.itemBlock.ItemBlockCloudworksPort;
import mekanism.cloudworks.common.tile.TileEntityCloudInput;
import mekanism.common.base.*;
import mekanism.common.block.BlockMekanismContainer;
import mekanism.common.block.states.BlockStateFacing;
import mekanism.common.integration.wrenches.Wrenches;
import mekanism.common.security.ISecurityItem;
import mekanism.common.security.ISecurityTile;
import mekanism.common.tile.prefab.TileEntityBasicBlock;
import mekanism.common.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Explosion;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import mekanism.cloudworks.common.tile.prefab.TileEntityCloudworksPort;
import mekanism.cloudworks.common.tile.TileEntityCloudworksTeamRecovery;

/**
 * Mekanism-style shared base for Cloudworks input/output blocks.
 */
public abstract class BlockCloudworksPort extends BlockMekanismContainer {

    protected BlockCloudworksPort() {
        super(Material.IRON);
        setHardness(3.5F);
        setResistance(16F);
        setCreativeTab(Cloudworks.tabCloudworks);
    }

    @Nonnull
    @Override
    public BlockStateContainer createBlockState() {
        return new BlockStateFacing(this);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return 0;
    }

    @Nonnull
    @Override
    @Deprecated
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState();
    }

    @Nonnull
    @Override
    @Deprecated
    public IBlockState getActualState(@Nonnull IBlockState state, IBlockAccess world, BlockPos pos) {
        TileEntity tile = MekanismUtils.getTileEntitySafe(world, pos);
        if (tile instanceof TileEntityBasicBlock block && block.facing != null)
            state = state.withProperty(BlockStateFacing.facingProperty, block.facing);
        return state;
    }

    @Override
    @Deprecated
    public void neighborChanged(IBlockState state, World world, BlockPos pos,
                                Block neighborBlock, BlockPos neighborPos) {
        if (!world.isRemote && world.getTileEntity(pos) instanceof TileEntityBasicBlock block)
            block.onNeighborChange(neighborBlock);
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, ItemStack stack) {
        if (world.getTileEntity(pos) instanceof TileEntityBasicBlock tile) {
            EnumFacing facing = EnumFacing.SOUTH;
            if (placer != null) {
                int side = MathHelper.floor(placer.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
                facing = switch (side) {
                    case 0 -> EnumFacing.NORTH;
                    case 1 -> EnumFacing.EAST;
                    case 2 -> EnumFacing.SOUTH;
                    case 3 -> EnumFacing.WEST;
                    default -> facing;
                };
            }
            tile.setFacing(facing);
            tile.redstone = world.getRedstonePowerFromNeighbors(pos) > 0;
            MekanismPlacementData.apply(world, pos, placer, stack);
        }
    }

    @Override
    @Deprecated
    public float getPlayerRelativeBlockHardness(IBlockState state, @Nonnull EntityPlayer player,
                                                @Nonnull World world, @Nonnull BlockPos pos) {
        return canAccess(player, world.getTileEntity(pos))
                ? super.getPlayerRelativeBlockHardness(state, player, world, pos) : 0.0F;
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state,
                                    EntityPlayer player, EnumHand hand, EnumFacing side,
                                    float hitX, float hitY, float hitZ) {
        if (world.isRemote) return true;
        if (!(world.getTileEntity(pos) instanceof TileEntityBasicBlock tile)) return false;
        ItemStack held = player.getHeldItem(hand);
        if (!held.isEmpty()) {
            IMekWrench wrench = Wrenches.getHandler(held);
            if (wrench != null) {
                RayTraceResult trace = new RayTraceResult(new Vec3d(hitX, hitY, hitZ), side, pos);
                if (wrench.canUseWrench(player, hand, held, trace)) {
                    if (!canAccess(player, tile)) {
                        SecurityUtils.displayNoAccess(player);
                        return true;
                    }
                    wrench.wrenchUsed(player, hand, held, trace);
                    if (player.isSneaking()) {
                        prepareRemoval(world, pos);
                        MekanismUtils.dismantleBlock(this, state, world, pos);
                    } else {
                        tile.setFacing(tile.facing.rotateAround(side.getAxis()));
                        world.notifyNeighborsOfStateChange(pos, this, true);
                    }
                    return true;
                }
            }
        }
        if (player.isSneaking()) return false;
        if (!canAccess(player, tile)) {
            SecurityUtils.displayNoAccess(player);
            return true;
        }
        player.openGui(Cloudworks.instance, getGuiID(), world, pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    protected abstract int getGuiID();

    protected boolean canAccess(EntityPlayer player, TileEntity tile) {
        if (tile instanceof TileEntityCloudworksTeamRecovery recovery)
            return recovery.canPlayerAccess(player);
        if (tile instanceof TileEntityCloudworksPort port
                && port.isTeamPort())
            return port.canPlayerAccess(player);
        return SecurityUtils.canAccess(player, tile);
    }

    @Override
    public void harvestBlock(@Nonnull World world, EntityPlayer player, @Nonnull BlockPos pos,
            @Nonnull IBlockState state, TileEntity tile, @Nonnull ItemStack tool) {
        prepareRemoval(world, pos);
        super.harvestBlock(world, player, pos, state, tile, tool);
    }

    @Override
    public boolean removedByPlayer(@Nonnull IBlockState state, World world, @Nonnull BlockPos pos,
            @Nonnull EntityPlayer player, boolean willHarvest) {
        if (!willHarvest) prepareRemoval(world, pos);
        return super.removedByPlayer(state, world, pos, player, willHarvest);
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        prepareRemoval(world, pos);
        super.breakBlock(world, pos, state);
    }

    private static void prepareRemoval(World world, BlockPos pos) {
        if (!world.isRemote && world.getTileEntity(pos) instanceof TileEntityCloudworksPort port)
            port.prepareRemoval();
    }

    @Override
    @Deprecated
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Nonnull
    @Override
    @Deprecated
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.MODEL;
    }

    @Nonnull
    @Override
    protected ItemStack getDropItem(@Nonnull IBlockState state, @Nonnull IBlockAccess world,
                                    @Nonnull BlockPos pos) {
        ItemStack itemStack = new ItemStack(this);
        if (itemStack.getTagCompound() == null) itemStack.setTagCompound(new NBTTagCompound());
        if (world.getTileEntity(pos) instanceof TileEntityBasicBlock tile) {
            if (tile instanceof ISecurityTile security
                    && itemStack.getItem() instanceof ISecurityItem itemSecurity
                    && itemSecurity.hasSecurity(itemStack)) {
                itemSecurity.setOwnerUUID(itemStack, security.getSecurity().getOwnerUUID());
                itemSecurity.setSecurity(itemStack, security.getSecurity().getMode());
            }
            if (tile instanceof ISustainedData data) data.writeSustainedData(itemStack);
        }
        return itemStack;
    }

    @Override
    public float getExplosionResistance(World world, BlockPos pos, Entity exploder, Explosion explosion) {
        return -1;
    }

    @Override
    public EnumFacing[] getValidRotations(World world, @Nonnull BlockPos pos) {
        EnumFacing[] valid = new EnumFacing[6];
        if (world.getTileEntity(pos) instanceof TileEntityBasicBlock tile) {
            for (EnumFacing direction : EnumFacing.VALUES)
                if (tile.canSetFacing(direction)) valid[direction.ordinal()] = direction;
        }
        return valid;
    }

    @Override
    public boolean rotateBlock(World world, @Nonnull BlockPos pos, @Nonnull EnumFacing axis) {
        if (world.getTileEntity(pos) instanceof TileEntityBasicBlock tile && tile.canSetFacing(axis)) {
            tile.setFacing(axis);
            return true;
        }
        return false;
    }
}
