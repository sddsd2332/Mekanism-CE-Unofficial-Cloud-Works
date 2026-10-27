package mekanism.cloudworks.common.integration.ic2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import ic2.api.recipe.ISemiFluidFuelManager;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.machine.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.api.environment.CloudworksGenerationPlan;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.module.CloudworksMachineContext;
import mekanism.cloudworks.api.machine.CloudworksMachineDefinition;
import mekanism.cloudworks.api.recipe.CloudworksRecipeMode;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineIdentityPolicy;
import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.api.module.MachineUpgradePolicy;
import mekanism.cloudworks.common.recipe.CloudworksRecipeBatch;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import ic2.api.info.Info;
import ic2.api.recipe.Recipes;
import ic2.core.init.MainConfig;
import ic2.core.item.type.NuclearResourceType;
import ic2.core.ref.ItemName;
import ic2.core.util.ConfigUtil;
import mekanism.cloudworks.api.machine.CloudworksMachineRegistration;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/**
 * IndustrialCraft 2 generator registration: seven generators under the single
 * {@code ic2:te} item, one identity per TeBlock meta (generator 3, geothermal
 * 4, RTG 6, semifluid 7, solar 8, water mill 10, wind 11; kinetic 5, Stirling
 * 9 stay out by ruling, the creative generator 86 rides the pending infinite
 * energy item entry).
 *
 * <p>Fuel conservation contracts (the dynamo precedent: duration is inert,
 * energy is explicit): the generator burns vanilla furnace fuels at
 * burn/4 fuel units times {@code round(10 x cfg)} EU each
 * (TileEntityGenerator:19/:36); the geothermal generator converts lava at
 * {@code round(20 x cfg)} EU per 2 mB = 500 x production EU per bucket
 * (TileEntityGeoGenerator:17/:27/:42-50); the semifluid generator freezes the
 * live {@code Recipes.semiFluidGenerator} table at its energyPerMb
 * (TileEntitySemifluidGenerator:33-56/:90-96, conservation exact — the native
 * energyPerTick rate axis does not exist in the cloud); the water mill takes
 * the bucket contract 1000 mB → 2 EU/t x 500 t (TileEntityWaterGenerator:44-59,
 * fluid feed has no container so production stays 2) — the flow mode's
 * compounding {@code water x= multiplier} accumulator (:83-93) is the classic
 * wind-up quirk and is deliberately not replicated.</p>
 *
 * <p>Ambient plans: the RTG carries an item input port and counts stocked
 * RTG pellets per machine from the frozen node inputs — the pellets are a
 * permanent installation and are never consumed (TileEntityRTGenerator:19
 * six single-pellet slots, :23-38 counts occupied slots at 2^(n-1) x
 * efficiency); solar rides the native day curve with rain/thunder weakening
 * −5/16 each and a hard zero under the nether flag
 * (TileEntitySolarGenerator:53-65, an unobstructed 15/15 sky is assumed and
 * the desert immunity has no cloud biome flag); wind freezes the WindSim
 * height polynomial (peak 1 at sh = sea + (height − sea)/2, zero at
 * 1.125 x height, WindSim:20-41/:72-86) at a deterministic steady strength
 * 15 of the native 5..24 walk, thunder x1.5 / rain x1.25, x2.4 scale, x0.1
 * windToEnergy — obstruction and the random overheat breakage are world
 * mechanics without a cloud expression.</p>
 */
public final class CloudworksIc2Generators {

    static final int GENERATOR_META = 3;
    static final int GEO_META = 4;
    static final int RTG_META = 6;
    static final int SEMIFLUID_META = 7;
    static final int SOLAR_META = 8;
    static final int WATER_META = 10;
    static final int WIND_META = 11;

    /**
     * TileEntityGenerator:19 storage 4000.
     */
    static final int GENERATOR_STORAGE_EU = 4000;
    /**
     * TileEntityGeoGenerator:25 storage 2400.
     */
    static final int GEO_STORAGE_EU = 2400;
    /**
     * TileEntitySemifluidGenerator:27 storage 32000.
     */
    static final int SEMIFLUID_STORAGE_EU = 32_000;
    /**
     * TileEntityRTGenerator:18 storage 20000.
     */
    static final int RTG_STORAGE_EU = 20_000;
    /**
     * TileEntitySolarGenerator:23 storage 2.
     */
    static final int SOLAR_STORAGE_EU = 2;
    /**
     * TileEntityWindGenerator:30 storage 32.
     */
    static final int WIND_STORAGE_EU = 32;
    /**
     * TileEntityWaterGenerator:29 storage 4.
     */
    static final int WATER_STORAGE_EU = 4;

    /**
     * TileEntityRTGenerator:19 six single-pellet fuel slots.
     */
    static final int RTG_PELLET_SLOTS = 6;

    /**
     * Steady deterministic strength inside the native 5..24 random walk.
     */
    static final double WIND_STEADY_STRENGTH = 15D;

    static final int FLUID_PORT_CAPACITY = 10_000;

    private static boolean registered;

    private CloudworksIc2Generators() {
    }

    public static synchronized void register() {
        if (registered) return;
        Block block = Block.getBlockFromName("ic2:te");
        if (block == null)
            throw new IllegalStateException("IndustrialCraft's ic2:te machine block is unavailable");
        register(Item.getItemFromBlock(block), CloudworksIc2Machines.MekanismConfigAccess.euToJoules(),
                ConfigUtil.getFloat(MainConfig.get(), "balance/energy/generator/generator"),
                ConfigUtil.getFloat(MainConfig.get(), "balance/energy/generator/geothermal"),
                ConfigUtil.getFloat(MainConfig.get(), "balance/energy/generator/radioisotope"),
                ConfigUtil.getFloat(MainConfig.get(), "balance/energy/generator/solar"),
                ConfigUtil.getFloat(MainConfig.get(), "balance/energy/generator/wind"),
                stack -> Info.itemInfo.getFuelValue(stack, false),
                ForgeRegistries.ITEMS,
                () -> Recipes.semiFluidGenerator.getFuelProperties(),
                () -> FluidRegistry.LAVA,
                () -> FluidRegistry.WATER,
                () -> ItemName.nuclear.getItemStack(
                        NuclearResourceType.rtg_pellet));
        registered = true;
    }

    /**
     * Pure registration entry so JVM tests drive it with substitutes and no IC2 runtime.
     */
    static synchronized void register(Item generatorItem, double euToJoules,
                                      double generatorCfg, double geoCfg, double rtgEfficiency, double solarCfg, double windCfg,
                                      ToIntFunction<ItemStack> fuelValue, Iterable<Item> items,
                                      Supplier<Map<String, ISemiFluidFuelManager.FuelProperty>> semiFluidTable,
                                      Supplier<Fluid> lava, Supplier<Fluid> water, Supplier<ItemStack> rtgPellet) {
        Objects.requireNonNull(generatorItem, "generator item");
        if (!Double.isFinite(euToJoules) || euToJoules <= 0D)
            throw new IllegalArgumentException("Invalid IndustrialCraft generator registration inputs");
        int generatorProduction = (int) Math.round(10D * nonNegative(generatorCfg));
        int geoProduction = (int) Math.round(20D * nonNegative(geoCfg));

        // Generator: vanilla furnace fuels, one contract per item (duration 1, explicit energy).
        registerFuel(generatorItem, GENERATOR_META, GENERATOR_STORAGE_EU * euToJoules, itemLayout(),
                "cloudworks:ic2_generator",
                new GeneratorFuelAdapter(generatorProduction, euToJoules, fuelValue, items));

        // Geothermal: lava per bucket at the native 10 EU/mB line.
        registerFuel(generatorItem, GEO_META, GEO_STORAGE_EU * euToJoules, fluidLayout(8000),
                "cloudworks:ic2_geo_generator",
                new GeoFuelAdapter(geoProduction, euToJoules, lava));

        // Semifluid: the live table, energyPerMb exact.
        registerFuel(generatorItem, SEMIFLUID_META, SEMIFLUID_STORAGE_EU * euToJoules, fluidLayout(),
                "cloudworks:ic2_semifluid_generator",
                new SemifluidFuelAdapter(euToJoules, semiFluidTable));

        // Water mill: the bucket contract only; the flow mode is a quirk.
        registerFuel(generatorItem, WATER_META, WATER_STORAGE_EU * euToJoules, fluidLayout(2000),
                "cloudworks:ic2_water_generator",
                new WaterFuelAdapter(euToJoules, water));

        // Ambient generators.
        registerAmbient(generatorItem, RTG_META, RTG_STORAGE_EU * euToJoules,
                new MachineSlotLayout().add("item_input", QIOResourceCodecs.ITEM_STACK_ID,
                        MachineSlotLayout.PortRole.INPUT, RTG_PELLET_SLOTS),
                new RtgPlanGenerator(rtgEfficiency, euToJoules, rtgPellet));
        registerAmbient(generatorItem, SOLAR_META, SOLAR_STORAGE_EU * euToJoules, new MachineSlotLayout(),
                new SolarPlanGenerator(solarCfg, euToJoules));
        registerAmbient(generatorItem, WIND_META, WIND_STORAGE_EU * euToJoules, new MachineSlotLayout(),
                new WindPlanGenerator(WIND_STEADY_STRENGTH, windCfg, euToJoules,
                        windCoefficients(256, 62)));
        CloudworksAPI.acceptMachineUpgrade(machineStack(generatorItem, WIND_META),
                MachineModuleType.height(), 256);
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) && value > 0D ? value : 0D;
    }

    private static void registerFuel(Item generatorItem, int meta, double capacity,
                                     MachineSlotLayout layout, String modeId, CloudworksMachineAdapter adapter) {
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(
                machineStack(generatorItem, meta),
                new MachineExecutionProfile(1, 0D, capacity, 1, MachineUpgradePolicy.NONE),
                layout, MachineIdentityPolicy.none(),
                Collections.singletonList(new CloudworksRecipeMode(modeId, 0, layout, adapter))));
    }

    private static void registerAmbient(Item generatorItem, int meta, double capacity,
                                        MachineSlotLayout layout, CloudworksEnergyGenerator generator) {
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(
                machineStack(generatorItem, meta),
                new MachineExecutionProfile(1, 0D, capacity, 1, MachineUpgradePolicy.NONE),
                layout, MachineIdentityPolicy.none(),
                Collections.emptyList(), generator, null));
    }

    static ItemStack machineStack(Item generatorItem, int meta) {
        return new ItemStack(generatorItem, 1, meta);
    }

    private static MachineSlotLayout itemLayout() {
        return new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64);
    }

    private static MachineSlotLayout fluidLayout() {
        return fluidLayout(FLUID_PORT_CAPACITY);
    }

    private static MachineSlotLayout fluidLayout(int capacity) {
        return new MachineSlotLayout()
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT,
                        capacity);
    }

    // -----------------------------------------------------------------------------------------
    // Fuel conservation contracts (duration inert, energy explicit — dynamo precedent)
    // -----------------------------------------------------------------------------------------

    /**
     * Vanilla furnace fuels through the IC2 fuel-value delegate: one item =
     * burn/4 fuel units (TileEntityGenerator:36) at {@code production} EU each.
     * The meta-0 probe is faithful to the vanilla burn-time lookup, which is
     * per Item with container-item exemptions already handled by the delegate.
     */
    static final class GeneratorFuelAdapter implements CloudworksMachineAdapter {
        private final int production;
        private final double euToJoules;
        private final ToIntFunction<ItemStack> fuelValue;
        private final Iterable<Item> items;

        GeneratorFuelAdapter(int production, double euToJoules, ToIntFunction<ItemStack> fuelValue,
                             Iterable<Item> items) {
            this.production = production;
            this.euToJoules = euToJoules;
            this.fuelValue = fuelValue;
            this.items = items;
        }

        @Override
        public List<CloudworksRecipeSpec> freeze(
                CloudworksMachineRegistration registration) {
            if (production <= 0) return Collections.emptyList();
            List<Item> candidates = new ArrayList<>();
            for (Item item : items)
                if (item != null) candidates.add(item);
            candidates.sort(Comparator.comparing(Item::getRegistryName, Comparator.nullsLast(
                    Comparator.naturalOrder())));
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            for (Item candidate : candidates) {
                ItemStack probe = new ItemStack(candidate);
                if (probe.isEmpty()) continue;
                int units;
                try {
                    units = fuelValue.applyAsInt(probe) / 4;
                } catch (RuntimeException | LinkageError unavailable) {
                    // A broken modded fuel must not abort the whole generator
                    // contract; skip only that item.
                    continue;
                }
                if (units <= 0) continue;
                try {
                    result.add(new CloudworksRecipeBatch().item(probe)
                            .energy(units * (double) production * euToJoules)
                            .specification("ic2_generator_" + result.size(), 1));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable fuel rejects only itself.
                }
            }
            return Collections.unmodifiableList(result);
        }
    }

    /**
     * Geothermal: 2 mB lava per fuel unit at round(20 x cfg) EU — one bucket contract.
     */
    static final class GeoFuelAdapter implements CloudworksMachineAdapter {
        private final int production;
        private final double euToJoules;
        private final Supplier<Fluid> lava;

        GeoFuelAdapter(int production, double euToJoules, Supplier<Fluid> lava) {
            this.production = production;
            this.euToJoules = euToJoules;
            this.lava = lava;
        }

        @Override
        public List<CloudworksRecipeSpec> freeze(
                CloudworksMachineRegistration registration) {
            Fluid lavaFluid = lava.get();
            if (lavaFluid == null || production <= 0) return Collections.emptyList();
            try {
                return Collections.singletonList(new CloudworksRecipeBatch()
                        .fluid(new FluidStack(lavaFluid, 1000))
                        .energy(500D * production * euToJoules)
                        .specification("ic2_geo_generator_0", 1));
            } catch (IllegalArgumentException unsupported) {
                return Collections.emptyList();
            }
        }
    }

    /**
     * Semifluid: the live table at energyPerMb exact; the energyPerTick rate axis has no cloud carrier.
     */
    static final class SemifluidFuelAdapter implements CloudworksMachineAdapter {
        private final double euToJoules;
        private final Supplier<Map<String, ISemiFluidFuelManager.FuelProperty>> table;

        SemifluidFuelAdapter(double euToJoules,
                             Supplier<Map<String, ISemiFluidFuelManager.FuelProperty>> table) {
            this.euToJoules = euToJoules;
            this.table = table;
        }

        @Override
        public List<CloudworksRecipeSpec> freeze(
                CloudworksMachineRegistration registration) {
            Map<String, ISemiFluidFuelManager.FuelProperty> fuels = table.get();
            if (fuels == null || fuels.isEmpty()) return Collections.emptyList();
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            for (Map.Entry<String, ISemiFluidFuelManager.FuelProperty> entry : fuels.entrySet()) {
                Fluid fluid = FluidRegistry.getFluid(entry.getKey());
                ISemiFluidFuelManager.FuelProperty property = entry.getValue();
                if (fluid == null || property == null || property.energyPerMb <= 0L) continue;
                try {
                    result.add(new CloudworksRecipeBatch()
                            .fluid(new FluidStack(fluid, 1000))
                            .energy(property.energyPerMb * 1000D * euToJoules)
                            .specification("ic2_semifluid_generator_" + result.size(), 1));
                } catch (IllegalArgumentException unsupported) {
                    // One unresolved fluid rejects only itself.
                }
            }
            return Collections.unmodifiableList(result);
        }
    }

    /**
     * Water mill bucket contract: 1000 mB → 2 EU/t x 500 t; the flow mode is not replicated.
     */
    static final class WaterFuelAdapter implements CloudworksMachineAdapter {
        private final double euToJoules;
        private final Supplier<Fluid> water;

        WaterFuelAdapter(double euToJoules, Supplier<Fluid> water) {
            this.euToJoules = euToJoules;
            this.water = water;
        }

        @Override
        public List<CloudworksRecipeSpec> freeze(
                CloudworksMachineRegistration registration) {
            Fluid waterFluid = water.get();
            if (waterFluid == null) return Collections.emptyList();
            try {
                return Collections.singletonList(new CloudworksRecipeBatch()
                        .fluid(new FluidStack(waterFluid, 1000))
                        .energy(1000D * euToJoules)
                        .specification("ic2_water_generator_0", 1));
            } catch (IllegalArgumentException unsupported) {
                return Collections.emptyList();
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Ambient generation plans
    // -----------------------------------------------------------------------------------------

    /**
     * RTG: counts stocked pellets per machine from the frozen node inputs and
     * produces 2^(n-1) x efficiency — the input port never consumes anything;
     * the pellets are a permanent installation exactly like the native six
     * single-pellet slots (TileEntityRTGenerator:19/:23-38).
     */
    static final class RtgPlanGenerator implements CloudworksEnergyGenerator {
        private final double efficiency;
        private final double euToJoules;
        private final Supplier<ItemStack> pellet;

        RtgPlanGenerator(double efficiency, double euToJoules, Supplier<ItemStack> pellet) {
            this.efficiency = Math.max(0D, efficiency);
            this.euToJoules = euToJoules;
            this.pellet = pellet;
        }

        @Override
        public CloudworksGenerationPlan freeze(CloudworksMachineContext machine, int operations,
                                               CloudworksGenerationContext environment) {
            ItemStack pelletStack = pellet.get();
            if (pelletStack == null || pelletStack.isEmpty()) return CloudworksGenerationPlan.constant(0D);
            long stocked = machine.inputs().getOrDefault(
                    QIOResourceCodecs.item(pelletStack), 0L);
            long perMachine = stocked / Math.max(1L, machine.machineCount());
            int slots = (int) Math.min(RTG_PELLET_SLOTS, perMachine);
            if (slots <= 0) return CloudworksGenerationPlan.constant(0D);
            double eu = Math.pow(2D, slots - 1) * efficiency;
            return CloudworksGenerationPlan.constant(
                    Math.max(0D, eu) * euToJoules * Math.max(1, operations));
        }
    }

    /**
     * Solar: the native day curve rides Source.SOLAR; the frozen peak takes
     * rain −5/16 and thunder −5/16 on top (a storm carries both, matching the
     * vanilla thunder-implies-rain weather) and a hard zero under the nether
     * flag. Unobstructed 15/15 sky assumed; the desert immunity has no cloud
     * biome flag.
     */
    static final class SolarPlanGenerator implements CloudworksEnergyGenerator {
        private final double multiplier;
        private final double euToJoules;

        SolarPlanGenerator(double multiplier, double euToJoules) {
            this.multiplier = Math.max(0D, multiplier);
            this.euToJoules = euToJoules;
        }

        @Override
        public CloudworksGenerationPlan freeze(CloudworksMachineContext machine, int operations,
                                               CloudworksGenerationContext environment) {
            double peak = multiplier;
            if (environment.rainEnvironment() || environment.thunderEnvironment())
                peak *= 1D - 5D / 16D;
            if (environment.thunderEnvironment())
                peak *= 1D - 5D / 16D;
            if (environment.netherEnvironment()) peak = 0D;
            return CloudworksGenerationPlan.solar(Math.max(0D, peak) * euToJoules * Math.max(1, operations));
        }
    }

    /**
     * Wind: the WindSim height polynomial at a deterministic steady strength
     * (peak 1 at sh, zero at 1.125 x world height, zero at ground — no
     * constant term), weather multiplier thunder x1.5 / rain x1.25, the x2.4
     * scale and the x0.1 windToEnergy conversion.
     */
    static final class WindPlanGenerator implements CloudworksEnergyGenerator {
        private final double strength;
        private final double multiplier;
        private final double euToJoules;
        private final double[] coefficients;

        WindPlanGenerator(double strength, double multiplier, double euToJoules, double[] coefficients) {
            this.strength = Math.max(0D, strength);
            this.multiplier = Math.max(0D, multiplier);
            this.euToJoules = euToJoules;
            this.coefficients = Objects.requireNonNull(coefficients);
        }

        @Override
        public CloudworksGenerationPlan freeze(CloudworksMachineContext machine, int operations,
                                               CloudworksGenerationContext environment) {
            double weather = environment.thunderEnvironment() ? 1.5D
                    : environment.rainEnvironment() ? 1.25D : 1D;
            double heightMultiplier = windHeightMultiplier(coefficients, environment.simulatedHeight());
            double wind = strength * heightMultiplier * weather * 2.4D;
            return CloudworksGenerationPlan.wind(
                    Math.max(0D, wind * .1D * multiplier) * euToJoules * Math.max(1, operations));
        }
    }

    /**
     * The WindSim cubic solved exactly like
     * {@code WindSim.calculateCoefficients(worldHeight=256, seaLevel=62)}:
     * poly(sh)=1, poly(fh)=0, poly'(sh)=0 with no constant term (Cramer).
     */
    static double[] windCoefficients(int worldHeight, int seaLevel) {
        double height = Math.max(1, worldHeight);
        double base = Math.max(0, seaLevel) < height ? Math.max(0, seaLevel) : height * .5D;
        double sh = base + (height - base) / 2D;
        double fh = height * 1.125D;
        double a11 = sh, a12 = sh * sh, a13 = sh * sh * sh;
        double a21 = fh, a22 = fh * fh, a23 = fh * fh * fh;
        double a31 = 1D, a32 = 2D * sh, a33 = 3D * sh * sh;
        double d = a11 * (a22 * a33 - a23 * a32) - a12 * (a21 * a33 - a23 * a31)
                + a13 * (a21 * a32 - a22 * a31);
        // Column-replaced determinants with b = [1, 0, 0].
        double d1 = a22 * a33 - a23 * a32;
        double d2 = a23 * a31 - a21 * a33;
        double d3 = a21 * a32 - a22 * a31;
        return new double[]{d1 / d, d2 / d, d3 / d};
    }

    /**
     * poly(h) = c1·h + c2·h² + c3·h³ clamped at zero, WindSim:76.
     */
    static double windHeightMultiplier(double[] coefficients, double heightValue) {
        double multiplier = coefficients[0] * heightValue
                + coefficients[1] * heightValue * heightValue
                + coefficients[2] * heightValue * heightValue * heightValue;
        return Math.max(0D, multiplier);
    }
}
