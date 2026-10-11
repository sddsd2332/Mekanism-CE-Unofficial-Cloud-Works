package mekanism.cloudworks.common.block;

import mekanism.cloudworks.common.tile.TileEntityCloudInput;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import javax.annotation.Nonnull;

public class BlockCloudInput extends BlockCloudworksPort {
    @Override
    protected int getGuiID() {
        return 1;
    }

    @Override
    public TileEntity createNewTileEntity(@Nonnull World world, int meta) {
        return new TileEntityCloudInput();
    }
}
