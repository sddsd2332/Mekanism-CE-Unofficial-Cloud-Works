package mekanism.cloudworks.common.block;

import mekanism.cloudworks.common.CloudworksEntry;
import mekanism.cloudworks.common.item.itemBlock.ItemBlockCloudworksDashboard;
import mekanism.cloudworks.common.tile.TileEntityCloudDashboard;
import mekanism.common.block.BlockQIODashboard;
import mekanism.common.block.states.BlockStateFacing;
import mekanism.common.block.states.BlockStateQIOComponent;
import mekanism.common.util.SecurityUtils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nonnull;

public class BlockCloudworksDashboard extends BlockQIODashboard {
    public BlockCloudworksDashboard() {
        super();
        setDefaultState(getDefaultState().withProperty(BlockStateFacing.facingProperty, EnumFacing.NORTH)
                .withProperty(BlockStateQIOComponent.activeProperty, true));
        disableStats();
    }

    @Override
    public boolean hasTileEntity(@Nonnull IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createNewTileEntity(@Nonnull World world, int meta) {
        return new TileEntityCloudDashboard();
    }

    @Nonnull
    @Override
    @Deprecated
    public IBlockState getActualState(@Nonnull IBlockState state, IBlockAccess world, BlockPos pos) {
        return super.getActualState(state, world, pos)
                .withProperty(BlockStateQIOComponent.activeProperty, true);
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state,
                                    EntityPlayer player, EnumHand hand, EnumFacing side,
                                    float hitX, float hitY, float hitZ) {
        if (!world.isRemote) CloudworksEntry.openTile(player, pos);
        return true;
    }

    @Override
    public boolean removedByPlayer(@Nonnull IBlockState state, World world, @Nonnull BlockPos pos,
                                    @Nonnull EntityPlayer player, boolean willHarvest) {
        return SecurityUtils.canAccess(player, world.getTileEntity(pos))
                && super.removedByPlayer(state, world, pos, player, willHarvest);
    }

    @Nonnull
    @Override
    protected ItemStack getDropItem(@Nonnull IBlockState state, @Nonnull IBlockAccess world,
                                    @Nonnull BlockPos pos) {
        ItemStack stack = new ItemStack(this);
        if (world.getTileEntity(pos) instanceof TileEntityCloudDashboard board
                && stack.getItem() instanceof ItemBlockCloudworksDashboard item) {
            item.setOwnerUUID(stack, board.getSecurity().getOwnerUUID());
            item.setSecurity(stack, board.getSecurity().getMode());
            item.setSustainedInventory(board.getInventory(), stack);
            board.writeSustainedData(stack);
        }
        return stack;
    }
}
