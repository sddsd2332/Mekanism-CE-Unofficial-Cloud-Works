package mekanism.cloudworks.common.registries;

import mekanism.cloudworks.common.Cloudworks;
import mekanism.cloudworks.common.block.BlockCloudInput;
import mekanism.cloudworks.common.block.BlockCloudOutput;
import mekanism.cloudworks.common.block.BlockCloudworksDashboard;
import mekanism.cloudworks.common.block.BlockCloudTeamInput;
import mekanism.cloudworks.common.block.BlockCloudTeamOutput;
import mekanism.cloudworks.common.block.BlockCloudworksTeamRecovery;
import mekanism.cloudworks.common.item.itemBlock.ItemBlockCloudInput;
import mekanism.cloudworks.common.item.itemBlock.ItemBlockCloudOutput;
import mekanism.cloudworks.common.item.itemBlock.ItemBlockCloudworksDashboard;
import mekanism.cloudworks.common.item.itemBlock.ItemBlockCloudworksPort;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.GameRegistry.ObjectHolder;
import net.minecraftforge.registries.IForgeRegistry;

@ObjectHolder(Cloudworks.MODID)
public class CloudworksBlocks {
    public static Block Dashboard = new BlockCloudworksDashboard();
    public static Block CloudInput = new BlockCloudInput();
    public static Block CloudOutput = new BlockCloudOutput();
    public static Block TeamCloudInput = new BlockCloudTeamInput();
    public static Block TeamCloudOutput = new BlockCloudTeamOutput();
    public static Block TeamRecovery = new BlockCloudworksTeamRecovery();

    public static void registerBlocks(IForgeRegistry<Block> registry) {
        registry.register(init(Dashboard, "cloudworks_dashboard"));
        registry.register(init(CloudInput, "cloud_input"));
        registry.register(init(CloudOutput, "cloud_output"));
        registry.register(init(TeamCloudInput, "team_cloud_input"));
        registry.register(init(TeamCloudOutput, "team_cloud_output"));
        registry.register(init(TeamRecovery, "team_cloudworks_recovery"));
    }

    public static void registerItemBlocks(IForgeRegistry<Item> registry) {
        registry.register(CloudworksItems.init(new ItemBlockCloudworksDashboard(Dashboard), "cloudworks_dashboard"));
        registry.register(CloudworksItems.init(new ItemBlockCloudInput(CloudInput), "cloud_input"));
        registry.register(CloudworksItems.init(new ItemBlockCloudOutput(CloudOutput), "cloud_output"));
        registry.register(CloudworksItems.init(new ItemBlockCloudInput(TeamCloudInput), "team_cloud_input"));
        registry.register(CloudworksItems.init(new ItemBlockCloudOutput(TeamCloudOutput), "team_cloud_output"));
        registry.register(CloudworksItems.init(new ItemBlockCloudworksPort(TeamRecovery), "team_cloudworks_recovery"));
    }

    public static Block init(Block block, String name) {
        return block.setCreativeTab(Cloudworks.tabCloudworks).setTranslationKey(name)
                .setRegistryName(new ResourceLocation(Cloudworks.MODID, name));
    }
}
