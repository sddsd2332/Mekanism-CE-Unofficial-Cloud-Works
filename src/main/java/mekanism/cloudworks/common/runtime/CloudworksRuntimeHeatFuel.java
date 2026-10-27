package mekanism.cloudworks.common.runtime;

import java.util.ArrayList;
import java.util.List;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.common.recipe.CloudworksHeatGeneratorAdapter;
import mekanism.cloudworks.common.service.CloudworksResourceSymbols;
import mekanism.common.config.MekanismConfig;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import mekanism.common.config.GeneratorsConfig;

/** Immutable item-to-lava equivalents; all native item hooks are evaluated during compilation. */
final class CloudworksRuntimeHeatFuel {
    final int lava, rate;
    final double incrementalGeneration;
    final Fuel[] fuels;

    private CloudworksRuntimeHeatFuel(int lava, int rate, double incrementalGeneration, List<Fuel> fuels) {
        this.lava = lava;
        this.rate = rate;
        this.incrementalGeneration = incrementalGeneration;
        this.fuels = fuels.toArray(new Fuel[0]);
    }

    static CloudworksRuntimeHeatFuel freeze(CloudworksResourceSymbols symbols, CloudworksGenerationContext context) {
        int lava = symbols.id(QIOResourceCodecs.fluid(new FluidStack(FluidRegistry.LAVA, 1)));
        GeneratorsConfig generators = MekanismConfig.current().generators;
        int capacity = generators == null ? 10000 : Math.max(1, generators.heatTankCapacity.val());
        int rate = generators == null ? 1 : Math.max(1, Math.min(capacity, generators.heatGenerationFluidRate.val()));
        double delta = generators == null ? 0 : Math.max(0, CloudworksHeatGeneratorAdapter.output(context, true)
                - CloudworksHeatGeneratorAdapter.output(context, false));
        if (!Double.isFinite(delta)) throw new IllegalArgumentException("Invalid heat generation contract");
        List<Fuel> fuels = new ArrayList<>();
        List<QIOResourceDescriptor> resources = symbols.descriptors();
        for (int resource = 0; resource < resources.size(); resource++) {
            QIOResourceDescriptor descriptor = resources.get(resource);
            if (!descriptor.getCodecId().equals(QIOResourceCodecs.ITEM_STACK_ID)) continue;
            ItemStack item = descriptor.resolveItemStackTemplate();
            if (item == null || item.isEmpty()) continue;
            item = item.copy();
            item.setCount(1);
            int burnTicks = TileEntityFurnace.getItemBurnTime(item);
            int units = burnTicks / 20;
            if (burnTicks <= 0) continue;
            ItemStack container = item.getItem().hasContainerItem(item)
                    ? item.getItem().getContainerItem(item) : ItemStack.EMPTY;
            int returned = container.isEmpty() ? -1 : symbols.id(QIOResourceCodecs.item(container));
            fuels.add(new Fuel(resource, units, returned, container.isEmpty() ? 0 : container.getCount(), burnTicks));
        }
        return new CloudworksRuntimeHeatFuel(lava, rate, delta, fuels);
    }

    static final class Fuel {
        final int resource, units, container, containerCount, burnTicks;

        Fuel(int resource, int units, int container, int containerCount, int burnTicks) {
            this.resource = resource;
            this.units = units;
            this.container = container;
            this.containerCount = containerCount;
            this.burnTicks = burnTicks;
        }
    }
}
