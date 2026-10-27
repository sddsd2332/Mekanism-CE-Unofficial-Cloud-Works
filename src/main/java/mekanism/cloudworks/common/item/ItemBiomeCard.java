package mekanism.cloudworks.common.item;

import java.util.List;
import mekanism.cloudworks.api.environment.BiomeAxes;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import mekanism.cloudworks.common.util.BiomeAxisNames;
import mekanism.cloudworks.common.util.BiomeAxisSampler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

public final class ItemBiomeCard extends ItemEnvironmentCard {
    public ItemBiomeCard() {}
    public ItemBiomeCard(ResourceLocation fixedAxis) { super(fixedAxis); }
    @Override protected List<EnvironmentAxis> axes() { return BiomeAxes.all(); }
    @Override protected String shellKey() { return "cloudworks.biome_card.name"; }
    @Override protected String axisWord(EnvironmentAxis axis) { return BiomeAxisNames.word(axis); }
    @Override protected EnvironmentAxis sampleAxis(World world, EntityPlayer player) {
        return BiomeAxisSampler.find(world, player);
    }
    @Override protected boolean matches(EnvironmentAxis axis, World world, EntityPlayer player) {
        return axis == BiomeAxisSampler.find(world, player);
    }
}
