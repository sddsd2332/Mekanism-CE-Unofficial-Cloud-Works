package mekanism.cloudworks.common.integration.te;

import cofh.thermalexpansion.block.device.TileHeatSink;
import cofh.thermalexpansion.block.device.BlockDevice;
import cofh.thermalexpansion.init.TEBlocks;
import cofh.thermalexpansion.util.managers.device.CoolantManager;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.*;
import mekanism.cloudworks.api.card.*;
import mekanism.cloudworks.api.environment.*;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.api.node.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.cloudworks.api.transfer.*;
import mekanism.cloudworks.api.work.*;
import mekanism.cloudworks.api.node.CloudworksAuxiliaryNodeType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraft.util.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.common.config.MekanismConfig;

/** Thermal Expansion auxiliary-node declarations. */
public final class CloudworksTeAuxiliaries {
    public static final ResourceLocation HEAT_SINK_ID =
            new ResourceLocation("thermalexpansion", "heat_sink");
    private static final int RANGE = 132 + 24;
    private static boolean registered;

    private CloudworksTeAuxiliaries() {}

    public static synchronized void register() {
        if (registered) return;
        Item device = Item.getItemFromBlock(TEBlocks.blockDevice);
        ItemStack heatSink = new ItemStack(device, 1, BlockDevice.Type.HEAT_SINK.getMetadata());
        MachineModuleType<HeatSinkModule> module = MachineModuleType.custom(HEAT_SINK_ID, 1,
                "auxiliary.cloudworks.thermalexpansion.heat_sink",
                new HeatSinkModule(MekanismConfig.current().general.FROM_RF.val()))
                .allowedSources(MachineModuleSource.AUXILIARY_NODE)
                .withArea(new ModuleArea(RANGE, .5D, ModuleArea.MergeMode.INDEPENDENT, 0x303E1B50, 0xFFCF70FF));
        CloudworksAPI.registerMachineModule(module);
        CloudworksAPI.registerAuxiliaryNodeType(CloudworksAuxiliaryNodeType.area(
                HEAT_SINK_ID,
                "auxiliary.cloudworks.thermalexpansion.heat_sink",
                heatSink,
                RANGE,
                6,
                CloudworksTeAuxiliaries::accepts,
                module,
                Collections.singletonList(CloudworksPortDefinition.channel(
                        QIOResourceCodecs.FLUID_STACK_ID.toString(), false, true, false))));
        registered = true;
    }

    private static boolean accepts(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item machine = Item.getItemFromBlock(TEBlocks.blockMachine);
        Item dynamo = Item.getItemFromBlock(TEBlocks.blockDynamo);
        return stack.getItem() == machine || stack.getItem() == dynamo;
    }

    static final class HeatSinkModule implements ICustomMachineModule<HeatSinkModule> {
        private final Map<QIOResourceDescriptor, double[]> coolants;
        private final double rfToJoules;
        HeatSinkModule(double rfToJoules) {
            if (!Double.isFinite(rfToJoules) || rfToJoules <= 0) throw new IllegalArgumentException("Invalid RF conversion");
            this.rfToJoules = rfToJoules;
            Map<QIOResourceDescriptor, double[]> values = new LinkedHashMap<>();
            for (String name : CoolantManager.getCoolantFluids()) {
                Fluid fluid = FluidRegistry.getFluid(name);
                if (fluid == null) continue;
                values.put(QIOResourceCodecs.fluid(new FluidStack(fluid, 1)),
                        new double[]{CoolantManager.getCoolantFactor(fluid) / 100D,
                                Math.max(1, CoolantManager.getCoolantRF100mB(fluid))});
            }
            coolants = Collections.unmodifiableMap(values);
        }
        @Override public WorkContribution onPreWork(IMachineModule<HeatSinkModule> module, PreWorkContext context) {
            double[] coolant = coolants.get(context.costResource());
            if (coolant == null) return WorkContribution.neutral();
            double chance = coolant[0];
            double cost = chance * TileHeatSink.USE_FACTOR * context.baseEnergyPerTick() / rfToJoules * 100D / coolant[1];
            return WorkContribution.builder().durationMultiplier(1D / (1D + chance))
                    .cost(new WorkContribution.ResourceCost(context.costResource(), cost,
                            WorkContribution.ResourceCost.Unit.PER_BASE_MACHINE_TICK)).build();
        }
    }
}
