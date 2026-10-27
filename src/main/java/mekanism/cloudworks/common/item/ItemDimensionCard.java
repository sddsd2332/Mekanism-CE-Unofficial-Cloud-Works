package mekanism.cloudworks.common.item;

import java.util.List;
import mekanism.cloudworks.api.environment.DimensionAxes;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import mekanism.cloudworks.api.environment.EnvironmentFallback;
import mekanism.cloudworks.common.util.DimensionAxisNames;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

public final class ItemDimensionCard extends ItemEnvironmentCard {
    public ItemDimensionCard() {}
    public ItemDimensionCard(ResourceLocation fixedAxis) { super(fixedAxis); }
    @Override protected List<EnvironmentAxis> axes() { return DimensionAxes.all(); }
    @Override protected String shellKey() { return "cloudworks.dimension_card.name"; }
    @Override protected String axisWord(EnvironmentAxis axis) { return DimensionAxisNames.word(axis); }
    @Override protected boolean matches(EnvironmentAxis axis, World world, EntityPlayer player) {
        EnvironmentFallback fallback = axis.fallback();
        return world.provider != null && fallback != null
                && fallback.dimension() == world.provider.getDimensionType();
    }
}
