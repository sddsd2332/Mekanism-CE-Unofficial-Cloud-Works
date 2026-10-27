package mekanism.cloudworks.common.block;

import mekanism.cloudworks.common.team.CloudworksTeamContext;
import mekanism.cloudworks.common.tile.TileEntityCloudOutput;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import java.util.UUID;
import net.minecraft.block.state.IBlockState;

/** Team-owned output port using the normal Cloudworks output tile and journal. */
public final class BlockCloudTeamOutput extends BlockCloudOutput {
    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
            EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        if (!world.isRemote && placer instanceof EntityPlayer player
                && world.getTileEntity(pos) instanceof TileEntityCloudOutput tile) {
            UUID owner = CloudworksTeamContext.owner(player);
            if (owner != null && !(stack.hasTagCompound() && stack.getTagCompound().hasKey("cwPortId", 8)))
                tile.getSecurity().setOwnerUUID(owner);
        }
    }
}
