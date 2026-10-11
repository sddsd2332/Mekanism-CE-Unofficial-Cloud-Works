package mekanism.cloudworks.client.gui.line;

import mekanism.api.qio.client.QIOResourceRenderer;
import mekanism.api.qio.client.QIOResourceRendererRegistry;
import mekanism.client.gui.GuiUtils;
import mekanism.cloudworks.common.recipe.CloudworksInfuseResourceCodec;
import mekanism.common.InfuseStorage;
import net.minecraft.util.ResourceLocation;

/** Client presentation for Cloudworks' own extension codec. */
public final class CloudworksInfuseRenderer implements QIOResourceRenderer<InfuseStorage> {
    public static void register() {
        CloudworksInfuseResourceCodec.register();
        if (QIOResourceRendererRegistry.INSTANCE.get(CloudworksInfuseResourceCodec.ID) == null)
            QIOResourceRendererRegistry.INSTANCE.register(new CloudworksInfuseRenderer());
    }

    @Override
    public ResourceLocation getCodecId() { return CloudworksInfuseResourceCodec.ID; }

    @Override
    public Class<InfuseStorage> getValueClass() { return InfuseStorage.class; }

    @Override
    public void render(InfuseStorage resource, int x, int y) {
        GuiUtils.drawInfuseBarSprite(x - 1, y - 1, 18, 18, 16, resource, true);
    }

    @Override
    public String getDisplayName(InfuseStorage resource) { return resource.getType().getLocalizedName(); }

    @Override
    public Object getIngredient(InfuseStorage resource) { return resource; }
}
