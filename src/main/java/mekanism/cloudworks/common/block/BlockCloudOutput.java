package mekanism.cloudworks.common.block;

import mekanism.cloudworks.common.tile.TileEntityCloudOutput;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import javax.annotation.Nonnull;

public class BlockCloudOutput extends BlockCloudworksPort {
    @Override
    protected int getGuiID() {
        return 2;
    }

    @Override
    public TileEntity createNewTileEntity(@Nonnull World world, int meta) {
        return new TileEntityCloudOutput();
    }
}
