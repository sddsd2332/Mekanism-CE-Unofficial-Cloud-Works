package mekanism.cloudworks.common.item;

import java.util.List;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import mekanism.cloudworks.api.environment.WeatherAxes;
import mekanism.cloudworks.common.util.WeatherAxisNames;
import mekanism.cloudworks.common.util.WeatherAxisSampler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import mekanism.cloudworks.common.util.BiomeAxisSampler;

public final class ItemWeatherCard extends ItemEnvironmentCard {
    public ItemWeatherCard() {}
    public ItemWeatherCard(ResourceLocation fixedAxis) { super(fixedAxis); }
    @Override protected List<EnvironmentAxis> axes() { return WeatherAxes.ALL; }
    @Override protected String shellKey() { return "cloudworks.environment_card.name"; }
    @Override protected String axisWord(EnvironmentAxis axis) { return WeatherAxisNames.word(axis); }
    @Override protected EnvironmentAxis sampleAxis(World world, EntityPlayer player) {
        return WeatherAxisSampler.find(world, player);
    }
    @Override protected boolean matches(EnvironmentAxis axis, World world, EntityPlayer player) {
        return WeatherAxisSampler.matches(axis, world, player,
                BiomeAxisSampler.find(world, player));
    }
}
