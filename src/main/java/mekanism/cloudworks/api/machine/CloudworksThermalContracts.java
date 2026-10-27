package mekanism.cloudworks.api.machine;

import mekanism.common.config.MekanismConfig;
import mekanism.common.content.boiler.SynchronizedBoilerData;

/**
 * Core-safe thermal parameter factories. Native configuration is read at
 * startup, never on the Runtime worker. Plans that hard-reference Mekanism
 * Generators classes (fusion) live in the generators-gated integration.
 */
public final class CloudworksThermalContracts {
    private CloudworksThermalContracts() {}

    public static CloudworksThermalPlan resistive() {
        return plan(CloudworksThermalPlan.Kind.RESISTIVE, 100, 5, 10, 0,
                Math.max(0, Math.min(1, MekanismConfig.current().general.resistiveHeaterEfficiency.val())),
                0, 0, 0, 0, 0, 0, 0, 3000, 1, 1, false);
    }
    public static CloudworksThermalPlan fuelwood() {
        return plan(CloudworksThermalPlan.Kind.FUELWOOD, 100, 5, 10, MekanismConfig.current().general.heatPerFuelTick.val(),
                0, 0, 0, 0, 0, 0, 0, 0, 3000, 1,
                Math.max(1, MekanismConfig.current().general.fuelwoodTickMultiplier.val()), false);
    }
    public static CloudworksThermalPlan heatGenerator() {
        return plan(CloudworksThermalPlan.Kind.HEAT_GENERATOR, 10, 5, 100, MekanismConfig.current().generators.heatGeneration.val(),
                0.5, 300, 0, 0, 0, 0,
                MekanismConfig.current().generators.heatGeneration.val()
                        + 7 * MekanismConfig.current().generators.heatGenerationLava.val()
                        + MekanismConfig.current().generators.heatGenerationNether.val(),
                0, 1e12, 1, 1, false)
                .withEnvironment(MekanismConfig.current().generators.heatGenerationLava.val(),
                        MekanismConfig.current().generators.heatGenerationNether.val(), 0);
    }
    public static CloudworksThermalPlan fission(boolean sodium, int ticks) {
        return plan(CloudworksThermalPlan.Kind.FISSION, MekanismConfig.current().generators.fissionCasingHeatCapacity.val(), 10, 10000,
                MekanismConfig.current().generators.energyPerFissionFuel.val(), 0, 373.15, 0,
                sodium ? MekanismConfig.current().generators.fissionSodiumThermalEnthalpy.val()
                        : MekanismConfig.current().general.maxEnergyPerSteam.val()
                                / MekanismConfig.current().generators.fissionSteamEfficiency.val(),
                0, 0, sodium ? MekanismConfig.current().generators.fissionSodiumConductivity.val()
                        : MekanismConfig.current().generators.fissionWaterConductivity.val(),
                0, 1e12, 1, ticks, sodium);
    }
    public static CloudworksThermalPlan boiler(int water, int ticks) {
        if (ticks < 1) throw new IllegalArgumentException("Invalid boiler period");
        return plan(CloudworksThermalPlan.Kind.BOILER, 50, 1, 100000, 0, 0, SynchronizedBoilerData.BASE_BOIL_TEMP,
                SynchronizedBoilerData.getSuperheatingTransfer(),
                SynchronizedBoilerData.getHeatEnthalpy() / SynchronizedBoilerData.getSteamEnergyEfficiency(),
                SynchronizedBoilerData.SODIUM_THERMAL_ENTHALPY, SynchronizedBoilerData.COOLANT_COOLING_EFFICIENCY,
                MekanismConfig.current().general.boilerWaterConductivity.val(), 0, 1e12, 1, water, false);
    }
    public static CloudworksThermalPlan evaporation() {
        return plan(CloudworksThermalPlan.Kind.EVAPORATION, MekanismConfig.current().general.evaporationHeatCapacity.val(), 1,
                100000, 0, 0, 300, MekanismConfig.current().general.evaporationHeatDissipation.val(), 0, 0, 0,
                MekanismConfig.current().general.evaporationTempMultiplier.val(),
                MekanismConfig.current().general.evaporationSolarMultiplier.val(),
                MekanismConfig.current().general.evaporationMaxTemp.val(), 1, 1, false);
    }

    private static CloudworksThermalPlan plan(CloudworksThermalPlan.Kind kind, double capacity, double conduction, double insulation,
            double heat, double efficiency, double threshold, double transfer, double processHeat,
            double coolantHeat, double coolantEfficiency, double rate, double solar, double maximum,
            double plasmaCapacity, int quota, boolean cooled) {
        return new CloudworksThermalPlan(kind, capacity, conduction, insulation, heat, efficiency,
                threshold, transfer, processHeat, coolantHeat, coolantEfficiency, rate, solar, maximum, plasmaCapacity, quota, cooled);
    }
}
