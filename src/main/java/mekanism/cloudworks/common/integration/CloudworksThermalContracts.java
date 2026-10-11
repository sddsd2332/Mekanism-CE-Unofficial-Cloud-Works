package mekanism.cloudworks.common.integration;

import mekanism.cloudworks.api.CloudworksThermalPlan;
import mekanism.cloudworks.api.CloudworksThermalPlan.Kind;
import mekanism.common.config.MekanismConfig;
import mekanism.common.content.boiler.SynchronizedBoilerData;

/** Native parameters are read at startup, never on the Runtime worker. */
public final class CloudworksThermalContracts {
    private CloudworksThermalContracts() {}

    public static CloudworksThermalPlan resistive() {
        return plan(Kind.RESISTIVE, 100, 5, 10, 0,
                Math.max(0, Math.min(1, MekanismConfig.current().general.resistiveHeaterEfficiency.val())),
                0, 0, 0, 0, 0, 0, 0, 3000, 1, 1, false);
    }
    public static CloudworksThermalPlan fuelwood() {
        return plan(Kind.FUELWOOD, 100, 5, 10, MekanismConfig.current().general.heatPerFuelTick.val(),
                0, 0, 0, 0, 0, 0, 0, 0, 3000, 1,
                Math.max(1, MekanismConfig.current().general.fuelwoodTickMultiplier.val()), false);
    }
    public static CloudworksThermalPlan heatGenerator() {
        return plan(Kind.HEAT_GENERATOR, 10, 5, 100, MekanismConfig.current().generators.heatGeneration.val(),
                0.5, 300, 0, 0, 0, 0,
                MekanismConfig.current().generators.heatGeneration.val()
                        + 6 * MekanismConfig.current().generators.heatGenerationLava.val()
                        + MekanismConfig.current().generators.heatGenerationNether.val(),
                0, 1e12, 1, 1, false)
                .withEnvironment(MekanismConfig.current().generators.heatGenerationLava.val(),
                        MekanismConfig.current().generators.heatGenerationNether.val(), 0);
    }
    public static CloudworksThermalPlan fusion(int injection, boolean cooled) {
        return plan(Kind.FUSION, Math.max(1, mekanism.generators.common.FusionReactor.caseHeatCapacity),
                Math.max(1, 1 / Math.max(0.001, MekanismConfig.current().generators.fusionCasingThermalConductivity.val())), 100000,
                MekanismConfig.current().generators.energyPerFusionFuel.val(),
                MekanismConfig.current().generators.fusionThermocoupleEfficiency.val(),
                mekanism.generators.common.FusionReactor.burnTemperature,
                mekanism.generators.common.FusionReactor.plasmaCaseConductivity,
                SynchronizedBoilerData.getHeatEnthalpy() / SynchronizedBoilerData.getSteamEnergyEfficiency(),
                0, 0, MekanismConfig.current().generators.fusionWaterHeatingRatio.val(),
                0, 1e12, Math.max(1, mekanism.generators.common.FusionReactor.plasmaHeatCapacity), injection, cooled);
    }
    public static CloudworksThermalPlan fission(boolean sodium, int ticks) {
        return plan(Kind.FISSION, MekanismConfig.current().generators.fissionCasingHeatCapacity.val(), 10, 10000,
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
        return plan(Kind.BOILER, 50, 1, 100000, 0, 0, SynchronizedBoilerData.BASE_BOIL_TEMP,
                SynchronizedBoilerData.getSuperheatingTransfer(),
                SynchronizedBoilerData.getHeatEnthalpy() / SynchronizedBoilerData.getSteamEnergyEfficiency(),
                SynchronizedBoilerData.SODIUM_THERMAL_ENTHALPY, SynchronizedBoilerData.COOLANT_COOLING_EFFICIENCY,
                MekanismConfig.current().general.boilerWaterConductivity.val(), 0, 1e12, 1, water, false);
    }
    public static CloudworksThermalPlan evaporation() {
        return plan(Kind.EVAPORATION, MekanismConfig.current().general.evaporationHeatCapacity.val(), 1,
                100000, 0, 0, 300, MekanismConfig.current().general.evaporationHeatDissipation.val(), 0, 0, 0,
                MekanismConfig.current().general.evaporationTempMultiplier.val(),
                MekanismConfig.current().general.evaporationSolarMultiplier.val(),
                MekanismConfig.current().general.evaporationMaxTemp.val(), 1, 1, false);
    }

    private static CloudworksThermalPlan plan(Kind kind, double capacity, double conduction, double insulation,
            double heat, double efficiency, double threshold, double transfer, double processHeat,
            double coolantHeat, double coolantEfficiency, double rate, double solar, double maximum,
            double plasmaCapacity, int quota, boolean cooled) {
        return new CloudworksThermalPlan(kind, capacity, conduction, insulation, heat, efficiency,
                threshold, transfer, processHeat, coolantHeat, coolantEfficiency, rate, solar, maximum, plasmaCapacity, quota, cooled);
    }
}
