package mekanism.cloudworks.common.item;

import java.util.List;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import mekanism.cloudworks.api.environment.StructureAxes;
import mekanism.cloudworks.common.util.CloudworksStructureSampler;
import mekanism.cloudworks.common.util.StructureAxisNames;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class ItemStructureCard extends ItemEnvironmentCard {
    public ItemStructureCard() {}
    public ItemStructureCard(ResourceLocation fixedAxis) { super(fixedAxis); }
    @Override protected List<EnvironmentAxis> axes() { return StructureAxes.ALL; }
    @Override protected String shellKey() { return "cloudworks.environment_card.name"; }
    @Override protected String axisWord(EnvironmentAxis axis) { return StructureAxisNames.word(axis); }
    @Override protected boolean matches(EnvironmentAxis axis, World world, EntityPlayer player) {
        return CloudworksStructureSampler.inside(world, new BlockPos(player), axis);
    }
}
