package mekanism.cloudworks.client;

import mekanism.api.gas.GasStack;
import mekanism.api.qio.client.QIOResourceRenderer;
import mekanism.api.qio.client.QIOResourceRendererRegistry;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.MekanismKeyHandler;
import mekanism.client.gui.GuiUtils;
import mekanism.client.render.MekanismRenderer;
import mekanism.cloudworks.api.environment.EnvironmentAxisRegistry;
import mekanism.cloudworks.api.environment.DimensionAxes;
import mekanism.cloudworks.api.environment.BiomeAxes;
import mekanism.cloudworks.api.environment.WeatherAxes;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import mekanism.cloudworks.common.Cloudworks;
import mekanism.cloudworks.common.item.ItemEnvironmentCard;
import mekanism.cloudworks.common.item.ItemInfiniteResourceCard;
import mekanism.cloudworks.common.registries.CloudworksBlocks;
import mekanism.cloudworks.common.registries.CloudworksItems;
import mekanism.cloudworks.common.special.CloudworksInfiniteResourceCardData;
import mekanism.common.event.ItemGUIRenderEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ModelBakery;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client model registration for the entry set. All block/item models live in
 * the cloudworks asset domain (copied from the reused QIO dashboard, hybrid
 * storage and dimensional stabilizer sources) — the standalone split keeps a
 * self-contained resource pack.
 */
@Mod.EventBusSubscriber(value = Side.CLIENT, modid = Cloudworks.MODID)
public final class CloudworksClientModels {
    private CloudworksClientModels() {
    }

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        model(CloudworksItems.PortableTerminal, "portable_cloudworks_terminal");
        model(CloudworksItems.TeamTerminal, "team_cloudworks_terminal");
        model(CloudworksItems.ActivationCard, "cloud_activation_card");
        model(CloudworksItems.OfflineProcessingCard, "cloud_offline_processing_card");
        model(CloudworksItems.WorkRateUpgradeCard, "cloud_work_rate_upgrade_card");
        model(CloudworksItems.OutputMultiplierCard, "cloud_output_multiplier_card");
        model(CloudworksItems.LineExpansionCard, "cloud_line_expansion_card");
        model(CloudworksItems.NodeExpansionCard, "cloud_node_expansion_card");
        model(CloudworksItems.HeightSimulationCard, "cloud_height_simulation_card");
        model(CloudworksItems.LavaHeatSimulationCard, "cloud_lava_heat_simulation_card");
        model(CloudworksItems.BiomeCard, "cloud_biome_card");
        model(CloudworksItems.DimensionCard, "cloud_dimension_card");
        model(CloudworksItems.StructureCard, "cloud_structure_card");
        model(CloudworksItems.WeatherCard, "cloud_weather_card");
        model(CloudworksItems.WarehouseCapacityCard, "cloud_warehouse_capacity_card");
        registerAxisVariantMeshes();
        model(CloudworksItems.LineCapacityCard, "cloud_line_capacity_card");
        model(CloudworksItems.InfiniteResourceCard, "cloud_infinite_resource_card");
        model(CloudworksItems.QIODownloadUpgrade, "qio_download_upgrade");
        model(CloudworksItems.QIOUploadUpgrade, "qio_upload_upgrade");
        model(CloudworksItems.SpeedModule, "speed_module");
        model(CloudworksItems.EnergyModule, "energy_module");
        model(Item.getItemFromBlock(CloudworksBlocks.Dashboard), "cloudworks_dashboard");
        model(Item.getItemFromBlock(CloudworksBlocks.CloudInput), "cloud_input");
        model(Item.getItemFromBlock(CloudworksBlocks.CloudOutput), "cloud_output");
        model(Item.getItemFromBlock(CloudworksBlocks.TeamCloudInput), "team_cloud_input");
        model(Item.getItemFromBlock(CloudworksBlocks.TeamCloudOutput), "team_cloud_output");
        model(Item.getItemFromBlock(CloudworksBlocks.TeamRecovery), "team_cloudworks_recovery");
    }

    @SubscribeEvent
    public static void renderInfiniteResourceCard(ItemGUIRenderEvent.Pre event) {
        ItemStack itemStack = event.getStack();
        if (itemStack.isEmpty() || !(itemStack.getItem() instanceof ItemInfiniteResourceCard) || !MekanismKeyHandler.getIsKeyPressed(MekanismKeyHandler.sneakKey)) {
            return;
        }
        CloudworksInfiniteResourceCardData card;
        try {
            card = CloudworksInfiniteResourceCardData.parse(itemStack);
        } catch (IllegalArgumentException invalidCard) {
            return;
        }
        QIOResourceDescriptor descriptor = card.descriptor;
        //其他资源优先解析
        if (renderRegisteredResource(descriptor, event.getxPosition(), event.getyPosition())) {
            event.setCanceled(true);
            return;
        }
        //解析物品
        if (QIOResourceCodecs.ITEM_STACK_ID.equals(descriptor.getCodecId())) {
            ItemStack item = descriptor.resolve(QIOResourceCodecs.ITEM_STACK);
            if (item != null && ItemInfiniteResourceCard.isAllowedResource(item)) {
                MekanismRenderer.resetGuiRenderState();
                Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(Minecraft.getMinecraft().player, item, event.getxPosition(), event.getyPosition());
                MekanismRenderer.resetGuiRenderState();
                event.setCanceled(true);
            }
            return;
        }

        //解析流体
        if (QIOResourceCodecs.FLUID_STACK_ID.equals(descriptor.getCodecId())) {
            FluidStack fluid = descriptor.resolve(QIOResourceCodecs.FLUID_STACK);
            if (fluid != null && fluid.getFluid() != null) {
                MekanismRenderer.resetGuiRenderState();
                GuiUtils.drawFluidBarSprite(event.getxPosition() - 1, event.getyPosition() - 1, 18, 18, 16, fluid, true);
                MekanismRenderer.resetGuiRenderState();
                event.setCanceled(true);
            }
            return;
        }

        //解析气体
        if (QIOResourceCodecs.GAS_STACK_ID.equals(descriptor.getCodecId())) {
            GasStack gas = descriptor.resolve(QIOResourceCodecs.GAS_STACK);
            if (gas != null && gas.getGas() != null) {
                MekanismRenderer.resetGuiRenderState();
                GuiUtils.drawGasBarSprite(event.getxPosition() - 1, event.getyPosition() - 1, 18, 18, 16, gas, true);
                MekanismRenderer.resetGuiRenderState();
                event.setCanceled(true);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean renderRegisteredResource(QIOResourceDescriptor descriptor, int x, int y) {
        if (descriptor == null || !descriptor.isResolved()) {
            return false;
        }
        QIOResourceRenderer<?> renderer = QIOResourceRendererRegistry.INSTANCE.get(descriptor.getCodecId());
        if (renderer == null) {
            return false;
        }
        Object resource = descriptor.resolve();

        if (resource == null || !renderer.getValueClass().isInstance(resource)) {
            return false;
        }

        GlStateManager.pushMatrix();
        try {
            ((QIOResourceRenderer<Object>) renderer).render(resource, x, y);
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        } finally {
            GlStateManager.popMatrix();
            MekanismRenderer.resetColor();
        }
    }

    private static void model(Item item, String name) {
        ModelLoader.setCustomModelResourceLocation(item, 0,
                new ModelResourceLocation(Cloudworks.MODID + ":" + name, "inventory"));
    }

    /**
     * NBT-variant mesh for the four sampling cards (§7 tier 1): each axis whose
     * variant model exists overrides the base card texture; axes without a
     * shipped model keep the default card look. Existence is checked through
     * the ResourceManager so resource packs can add or replace variants.
     */
    private static void registerAxisVariantMeshes() {
        Map<Item, String> bases = new LinkedHashMap<>();
        bases.put(CloudworksItems.BiomeCard, "cloud_biome_card");
        bases.put(CloudworksItems.DimensionCard, "cloud_dimension_card");
        bases.put(CloudworksItems.StructureCard, "cloud_structure_card");
        bases.put(CloudworksItems.WeatherCard, "cloud_weather_card");
        Map<Item, Map<String, ModelResourceLocation>> variants = new LinkedHashMap<>();
        for (Item card : bases.keySet()) variants.put(card, new HashMap<>());
        for (EnvironmentAxis axis : EnvironmentAxisRegistry.all()) {
            String variant = "cloud_" + axis.id().getPath() + "_card";
            try {
                Minecraft.getMinecraft().getResourceManager().getResource(
                        new ResourceLocation(Cloudworks.MODID, "models/item/" + variant + ".json"));
            } catch (IOException missing) {
                continue; // opt-in override: no model, no variant — base texture stays
            }
            Item card = ItemEnvironmentCard.cardFor(axis);
            variants.get(card).put(axis.id().toString(),
                    new ModelResourceLocation(Cloudworks.MODID + ":" + variant, "inventory"));
        }
        for (Map.Entry<Item, String> base : bases.entrySet()) {
            Item card = base.getKey();
            ModelResourceLocation fallback =
                    new ModelResourceLocation(Cloudworks.MODID + ":" + base.getValue(), "inventory");
            Map<String, ModelResourceLocation> mesh = variants.get(card);
            if (!mesh.isEmpty())
                ModelBakery.registerItemVariants(card, mesh.values().toArray(new ModelResourceLocation[0]));
            ModelLoader.setCustomMeshDefinition(card, stack -> {
                ResourceLocation axis = ItemEnvironmentCard.axisIdOf(stack);
                ModelResourceLocation variant = axis == null ? null : mesh.get(axis.toString());
                return variant != null ? variant : fallback;
            });
        }
    }
}
