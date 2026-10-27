package mekanism.cloudworks.api.client;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Optional client companion. Register it only from the physical client lifecycle. */
@SideOnly(Side.CLIENT)
@FunctionalInterface
public interface IAreaRenderModule {
    AreaRenderDescriptor getAreaRender(AreaRenderContext context);
}
