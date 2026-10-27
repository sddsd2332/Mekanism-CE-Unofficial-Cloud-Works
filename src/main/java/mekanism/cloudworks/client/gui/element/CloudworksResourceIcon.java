package mekanism.cloudworks.client.gui.element;

import mekanism.api.gas.GasStack;
import mekanism.api.qio.client.QIOResourceRenderer;
import mekanism.api.qio.client.QIOResourceRendererRegistry;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.render.MekanismRenderer;
import mekanism.cloudworks.client.gui.line.CloudworksInfuseRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import mekanism.common.Mekanism;

/** Family-aware icon rendering shared by line-canvas resource badges. */
public final class CloudworksResourceIcon {
    public enum Family {
        ITEM, FLUID, GAS, INFUSE, OTHER, UNKNOWN
    }

    private CloudworksResourceIcon() {
    }

    public static Family family(QIOResourceDescriptor resource) {
        return resource == null || !resource.isResolved() ? Family.UNKNOWN : family(resource.getFamily());
    }

    public static Family family(String family) {
        if (QIOResourceCodecs.ITEM_FAMILY.equals(family)) return Family.ITEM;
        if (QIOResourceCodecs.FLUID_FAMILY.equals(family)) return Family.FLUID;
        if (QIOResourceCodecs.GAS_FAMILY.equals(family)) return Family.GAS;
        if ("infuse".equals(family)) return Family.INFUSE;
        return family == null || family.isEmpty() ? Family.UNKNOWN : Family.OTHER;
    }

    /**
     * Renders a resource using its native family presentation first, then an
     * addon QIO renderer. False means the caller should draw its item fallback.
     */
    public static boolean render(IGuiWrapper gui, QIOResourceDescriptor resource, int x, int y) {
        if (gui == null || resource == null || !resource.isResolved()) return false;
        MekanismRenderer.resetGuiRenderState();
        try {
            switch (family(resource)) {
                case ITEM: {
                    ItemStack item = resource.resolveItemStackTemplate();
                    if (item != null && !item.isEmpty()) {
                        gui.renderItemWithOverlay(item, x, y, 1, "");
                        return true;
                    }
                    break;
                }
                case FLUID: {
                    FluidStack fluid = resource.resolveFluidStackTemplate();
                    if (fluid != null) {
                        GuiUtils.drawFluidBarSprite(x - 1, y - 1, 18, 18, 16, fluid, true);
                        return true;
                    }
                    break;
                }
                case GAS: {
                    GasStack gas = resource.resolveGasStackTemplate();
                    if (gas != null && gas.getGas() != null) {
                        GuiUtils.drawGasBarSprite(x - 1, y - 1, 18, 18, 16, gas, true);
                        return true;
                    }
                    break;
                }
                case INFUSE:
                    CloudworksInfuseRenderer.register();
                    break;
                default:
                    break;
            }
            return renderRegistered(resource, x, y);
        } catch (RuntimeException | LinkageError unsupported) {
            if (Mekanism.logger != null)
                Mekanism.logger.debug("Could not render Cloudworks resource icon {}",
                        resource.getCodecId(), unsupported);
            return false;
        } finally {
            MekanismRenderer.resetGuiRenderState();
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean renderRegistered(QIOResourceDescriptor resource, int x, int y) {
        QIOResourceRenderer<?> renderer =
                QIOResourceRendererRegistry.INSTANCE.get(resource.getCodecId());
        Object value = resource.resolve();
        if (renderer == null || value == null || !renderer.getValueClass().isInstance(value)) return false;
        GlStateManager.pushMatrix();
        try {
            ((QIOResourceRenderer<Object>) renderer).render(value, x, y);
            return true;
        } finally {
            GlStateManager.popMatrix();
            MekanismRenderer.resetColor();
        }
    }
}
