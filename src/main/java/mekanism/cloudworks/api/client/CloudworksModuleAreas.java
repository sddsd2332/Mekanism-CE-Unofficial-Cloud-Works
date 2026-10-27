package mekanism.cloudworks.api.client;

import java.util.*;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import mekanism.cloudworks.api.CloudworksAreaGeometry;
import mekanism.cloudworks.api.module.ModuleArea;

/** Client companions share the existing range compiler and renderer. */
@SideOnly(Side.CLIENT)
public final class CloudworksModuleAreas {
    private static final Map<ResourceLocation, IAreaRenderModule> PROVIDERS = new LinkedHashMap<>();
    private CloudworksModuleAreas() {}
    public static void register(ResourceLocation module, IAreaRenderModule provider) {
        if (PROVIDERS.putIfAbsent(Objects.requireNonNull(module), Objects.requireNonNull(provider)) != null)
            throw new IllegalArgumentException("Duplicate module area renderer " + module);
    }
    public static AreaRenderDescriptor describe(AreaRenderContext context) {
        IAreaRenderModule provider = PROVIDERS.get(context.module().id());
        if (provider != null) return Objects.requireNonNull(provider.getAreaRender(context));
        ModuleArea area = context.module().area();
        if (area == null) throw new IllegalArgumentException("Module has no area");
        return new AreaRenderDescriptor(Collections.singletonList(context.bounds()), area);
    }
    public static Set<UUID> draw(AreaRenderDescriptor descriptor, Collection<CloudworksAreaGeometry.Target> candidates,
            double borderWidth) {
        List<CloudworksAreaRenderer.Overlay> overlays = descriptor.mergeMode() == ModuleArea.MergeMode.UNION
                ? Collections.singletonList(CloudworksAreaRenderer.compile(descriptor.areas(), candidates, descriptor.targetThreshold()))
                : CloudworksAreaRenderer.compileIndependent(descriptor.areas(), candidates, descriptor.targetThreshold());
        Set<UUID> targets = new LinkedHashSet<>();
        for (CloudworksAreaRenderer.Overlay overlay : overlays) {
            CloudworksAreaRenderer.draw(overlay, descriptor.fillArgb(), descriptor.borderArgb(), borderWidth);
            targets.addAll(overlay.targets());
        }
        return targets;
    }
}
