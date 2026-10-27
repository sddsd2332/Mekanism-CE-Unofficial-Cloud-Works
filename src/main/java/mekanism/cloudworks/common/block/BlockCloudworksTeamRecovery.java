package mekanism.cloudworks.common.block;

import mekanism.api.IMekWrench;
import mekanism.cloudworks.common.Cloudworks;
import mekanism.cloudworks.common.tile.TileEntityCloudworksTeamRecovery;
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
import net.minecraft.util.math.*;
import net.minecraft.world.*;
import javax.annotation.Nonnull;
import mekanism.common.base.ISustainedData;

/** Team recovery machine block, independently based on Mekanism's container block. */
public final class BlockCloudworksTeamRecovery extends BlockMekanismContainer {
    public BlockCloudworksTeamRecovery() {
        super(Material.IRON);
        setHardness(3.5F);
        setResistance(16F);
        setCreativeTab(Cloudworks.tabCloudworks);
        setTranslationKey("team_cloudworks_recovery");
    }

    @Nonnull @Override public BlockStateContainer createBlockState() { return new BlockStateFacing(this); }
    @Override public int getMetaFromState(IBlockState state) { return 0; }
    @Nonnull @Override @Deprecated public IBlockState getStateFromMeta(int meta) { return getDefaultState(); }

    @Nonnull @Override @Deprecated
    public IBlockState getActualState(@Nonnull IBlockState state, IBlockAccess world, BlockPos pos) {
        TileEntity tile = MekanismUtils.getTileEntitySafe(world, pos);
        if (tile instanceof TileEntityBasicBlock basic && basic.facing != null)
            return state.withProperty(BlockStateFacing.facingProperty, basic.facing);
        return state;
    }

    @Override @Deprecated
    public void neighborChanged(IBlockState state, World world, BlockPos pos, Block neighbor, BlockPos neighborPos) {
        if (!world.isRemote && world.getTileEntity(pos) instanceof TileEntityBasicBlock basic)
            basic.onNeighborChange(neighbor);
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack) {
        if (world.getTileEntity(pos) instanceof TileEntityBasicBlock basic) {
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
            basic.setFacing(facing);
            basic.redstone = world.getRedstonePowerFromNeighbors(pos) > 0;
            MekanismPlacementData.apply(world, pos, placer, stack);
        }
    }

    @Override @Deprecated
    public float getPlayerRelativeBlockHardness(IBlockState state, EntityPlayer player, World world, BlockPos pos) {
        return canAccess(player, world.getTileEntity(pos))
                ? super.getPlayerRelativeBlockHardness(state, player, world, pos) : 0;
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
            EnumHand hand, EnumFacing side, float hitX, float hitY, float hitZ) {
        if (world.isRemote) return true;
        if (!(world.getTileEntity(pos) instanceof TileEntityCloudworksTeamRecovery tile)) return false;
        ItemStack held = player.getHeldItem(hand);
        if (!held.isEmpty()) {
            IMekWrench wrench = Wrenches.getHandler(held);
            if (wrench != null) {
                RayTraceResult trace = new RayTraceResult(new Vec3d(hitX, hitY, hitZ), side, pos);
                if (wrench.canUseWrench(player, hand, held, trace)) {
                    if (!tile.canPlayerAccess(player)) { SecurityUtils.displayNoAccess(player); return true; }
                    wrench.wrenchUsed(player, hand, held, trace);
                    if (player.isSneaking()) {
                        prepareRemoval(world, pos);
                        MekanismUtils.dismantleBlock(this, state, world, pos);
                    }
                    else {
                        tile.setFacing(tile.facing.rotateAround(side.getAxis()));
                        world.notifyNeighborsOfStateChange(pos, this, true);
                    }
                    return true;
                }
            }
        }
        if (player.isSneaking()) return false;
        if (!tile.canPlayerAccess(player)) { SecurityUtils.displayNoAccess(player); return true; }
        player.openGui(Cloudworks.instance, 5, world, pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    private boolean canAccess(EntityPlayer player, TileEntity tile) {
        return tile instanceof TileEntityCloudworksTeamRecovery recovery && recovery.canPlayerAccess(player);
    }

    @Override
    public void harvestBlock(World world, EntityPlayer player, BlockPos pos, IBlockState state, TileEntity tile, ItemStack tool) {
        prepareRemoval(world, pos);
        super.harvestBlock(world, player, pos, state, tile, tool);
    }
    @Override
    public boolean removedByPlayer(IBlockState state, World world, BlockPos pos, EntityPlayer player, boolean willHarvest) {
        if (!willHarvest) prepareRemoval(world, pos);
        return super.removedByPlayer(state, world, pos, player, willHarvest);
    }
    @Override public void breakBlock(World world, BlockPos pos, IBlockState state) {
        prepareRemoval(world, pos);
        super.breakBlock(world, pos, state);
    }
    private static void prepareRemoval(World world, BlockPos pos) {
        if (!world.isRemote && world.getTileEntity(pos) instanceof TileEntityCloudworksTeamRecovery tile)
            tile.prepareRemoval();
    }

    @Override @Deprecated public boolean isOpaqueCube(IBlockState state) { return false; }
    @Nonnull @Override @Deprecated public EnumBlockRenderType getRenderType(IBlockState state) { return EnumBlockRenderType.MODEL; }

    @Nonnull @Override
    protected ItemStack getDropItem(@Nonnull IBlockState state, @Nonnull IBlockAccess world, @Nonnull BlockPos pos) {
        ItemStack stack = new ItemStack(this);
        if (world.getTileEntity(pos) instanceof TileEntityBasicBlock basic) {
            if (basic instanceof ISecurityTile security && stack.getItem() instanceof ISecurityItem itemSecurity
                    && itemSecurity.hasSecurity(stack)) {
                itemSecurity.setOwnerUUID(stack, security.getSecurity().getOwnerUUID());
                itemSecurity.setSecurity(stack, security.getSecurity().getMode());
            }
            if (basic instanceof ISustainedData data) data.writeSustainedData(stack);
        }
        return stack;
    }
    @Override public float getExplosionResistance(World world, BlockPos pos, Entity exploder, Explosion explosion) { return -1; }
    @Override public EnumFacing[] getValidRotations(World world, BlockPos pos) {
        EnumFacing[] valid = new EnumFacing[6];
        if (world.getTileEntity(pos) instanceof TileEntityBasicBlock basic)
            for (EnumFacing direction : EnumFacing.VALUES)
                if (basic.canSetFacing(direction)) valid[direction.ordinal()] = direction;
        return valid;
    }
    @Override public boolean rotateBlock(World world, BlockPos pos, EnumFacing axis) {
        if (world.getTileEntity(pos) instanceof TileEntityBasicBlock basic && basic.canSetFacing(axis)) {
            basic.setFacing(axis);
            return true;
        }
        return false;
    }

    @Override public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityCloudworksTeamRecovery();
    }
}
