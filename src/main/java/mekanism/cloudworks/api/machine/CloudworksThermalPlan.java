package mekanism.cloudworks.api.machine;

import net.minecraft.util.ResourceLocation;
import java.util.Objects;

/** Immutable registration-time parameters. No configuration suppliers or world objects escape. */
public final class CloudworksThermalPlan {
    public static final ResourceLocation HEAT = new ResourceLocation("cloudworks", "heat");
    public enum Kind { RESISTIVE, FUELWOOD, HEAT_GENERATOR, FUSION, FISSION, BOILER, EVAPORATION }
    public final Kind kind;
    public final double capacity, conduction, insulation, heatPerUnit, efficiency, threshold, transfer,
            processHeat, coolantHeat, coolantEfficiency, processRate, solarGain, maxTemperature, plasmaCapacity;
    public final int rate;
    public final boolean cooled;
    public final double lavaEnvironmentHeat, netherEnvironmentHeat;
    public final int solarPanels;

    public CloudworksThermalPlan(Kind kind, double capacity, double conduction, double insulation,
            double heatPerUnit, double efficiency, double threshold, double transfer, double processHeat,
            double coolantHeat, double coolantEfficiency, double processRate, double solarGain,
            double maxTemperature, double plasmaCapacity, int rate, boolean cooled) {
        this(kind, capacity, conduction, insulation, heatPerUnit, efficiency, threshold, transfer, processHeat,
                coolantHeat, coolantEfficiency, processRate, solarGain, maxTemperature, plasmaCapacity, rate, cooled, 0, 0, 0);
    }

    private CloudworksThermalPlan(Kind kind, double capacity, double conduction, double insulation,
            double heatPerUnit, double efficiency, double threshold, double transfer, double processHeat,
            double coolantHeat, double coolantEfficiency, double processRate, double solarGain,
            double maxTemperature, double plasmaCapacity, int rate, boolean cooled, double lavaEnvironmentHeat,
            double netherEnvironmentHeat, int solarPanels) {
        this.kind = Objects.requireNonNull(kind);
        double[] values = {capacity, conduction, insulation, heatPerUnit, efficiency, threshold, transfer,
                processHeat, coolantHeat, coolantEfficiency, processRate, solarGain, maxTemperature, plasmaCapacity};
        for (double value : values)
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid thermal parameter");
        if (capacity < 1 || conduction < 1 || plasmaCapacity < 1 || rate < 0 || rate == 0 && kind != Kind.FUSION || efficiency > 1
                || coolantEfficiency > 1 || maxTemperature < 300
                || (kind == Kind.BOILER || kind == Kind.FISSION || kind == Kind.FUSION && cooled) && processHeat <= 0
                || kind == Kind.BOILER && coolantHeat <= 0)
            throw new IllegalArgumentException("Invalid thermal contract");
        this.capacity = capacity; this.conduction = conduction; this.insulation = insulation;
        this.heatPerUnit = heatPerUnit; this.efficiency = efficiency; this.threshold = threshold;
        this.transfer = transfer; this.processHeat = processHeat; this.coolantHeat = coolantHeat;
        this.coolantEfficiency = coolantEfficiency; this.processRate = processRate; this.solarGain = solarGain;
        this.maxTemperature = maxTemperature; this.plasmaCapacity = plasmaCapacity; this.rate = rate; this.cooled = cooled;
        if (!Double.isFinite(lavaEnvironmentHeat) || lavaEnvironmentHeat < 0
                || !Double.isFinite(netherEnvironmentHeat) || netherEnvironmentHeat < 0 || solarPanels < 0 || solarPanels > 4)
            throw new IllegalArgumentException("Invalid frozen thermal environment");
        this.lavaEnvironmentHeat = lavaEnvironmentHeat; this.netherEnvironmentHeat = netherEnvironmentHeat;
        this.solarPanels = solarPanels;
    }

    public CloudworksThermalPlan withCapacitor(double capacity, double conduction, double insulation) {
        return new CloudworksThermalPlan(kind, capacity, conduction, insulation, heatPerUnit, efficiency,
                threshold, transfer, processHeat, coolantHeat, coolantEfficiency, processRate, solarGain,
                maxTemperature, plasmaCapacity, rate, cooled, lavaEnvironmentHeat, netherEnvironmentHeat, solarPanels);
    }

    public CloudworksThermalPlan withEnvironment(double lava, double nether, int solarPanels) {
        return new CloudworksThermalPlan(kind, capacity, conduction, insulation, heatPerUnit, efficiency,
                threshold, transfer, processHeat, coolantHeat, coolantEfficiency, processRate, solarGain,
                maxTemperature, plasmaCapacity, rate, cooled, lava, nether, solarPanels);
    }
    /** Fusion permits zero injection while keeping its residual thermal reservoirs intact. */
    public CloudworksThermalPlan withRate(int rate) {
        return new CloudworksThermalPlan(kind, capacity, conduction, insulation, heatPerUnit, efficiency,
                threshold, transfer, processHeat, coolantHeat, coolantEfficiency, processRate, solarGain,
                maxTemperature, plasmaCapacity, rate, cooled, lavaEnvironmentHeat, netherEnvironmentHeat, solarPanels);
    }

    public static MachineSlotLayout ports(MachineSlotLayout layout) {
        MachineSlotLayout result = new MachineSlotLayout();
        for (MachineSlotLayout.Slot slot : layout.slots())
            result.add(slot.id(), slot.codecId(), slot.role(), slot.capacity(), slot.multipleConnections(), slot.recipeFilteredInput());
        for (String id : new String[]{"heat_input", "heat_output"}) {
            MachineSlotLayout.Slot slot = result.find(id);
            if (slot != null && (!slot.codecId().equals(HEAT)
                    || slot.role() != (id.equals("heat_input") ? MachineSlotLayout.PortRole.INPUT : MachineSlotLayout.PortRole.OUTPUT)))
                throw new IllegalArgumentException("Reserved thermal port has an incompatible definition: " + id);
        }
        if (result.find("heat_input") == null) result.add("heat_input", HEAT, MachineSlotLayout.PortRole.INPUT, 1);
        if (result.find("heat_output") == null) result.add("heat_output", HEAT, MachineSlotLayout.PortRole.OUTPUT, 1);
        return result;
    }
}
