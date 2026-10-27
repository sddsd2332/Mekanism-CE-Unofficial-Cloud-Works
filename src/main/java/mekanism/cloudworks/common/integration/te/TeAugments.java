package mekanism.cloudworks.common.integration.te;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import cofh.thermalexpansion.util.managers.device.TapperManager;
import cofh.thermalexpansion.util.managers.machine.CentrifugeManager;
import cofh.thermalexpansion.util.managers.machine.CompactorManager;
import cofh.thermalexpansion.util.managers.machine.CrucibleManager;
import cofh.thermalexpansion.util.managers.machine.ExtruderManager;
import cofh.thermalexpansion.util.managers.machine.FurnaceManager;
import cofh.thermalexpansion.util.managers.machine.InsolatorManager;
import cofh.thermalexpansion.util.managers.dynamo.CompressionManager;
import cofh.thermalexpansion.util.managers.dynamo.MagmaticManager;
import cofh.thermalexpansion.util.managers.dynamo.NumismaticManager;
import cofh.thermalexpansion.util.managers.dynamo.ReactantManager;
import cofh.thermalexpansion.util.managers.dynamo.SteamManager;
import cofh.thermalexpansion.util.managers.device.CoolantManager;
import cofh.thermalexpansion.util.managers.machine.BrewerManager;
import cofh.thermalexpansion.util.managers.machine.PulverizerManager;
import cofh.thermalexpansion.util.managers.machine.RefineryManager;
import cofh.thermalexpansion.util.managers.machine.SawmillManager;
import cofh.thermalexpansion.util.managers.machine.SmelterManager;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.machine.CloudworksMachineRegistration;
import mekanism.cloudworks.api.recipe.RecipeContribution;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;
import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.cloudworks.api.module.AxisContribution;
import mekanism.cloudworks.api.module.UpgradeAxis;
import mekanism.cloudworks.api.module.ICustomMachineModule;
import mekanism.cloudworks.api.module.IMachineModule;
import mekanism.cloudworks.api.work.PreWorkContext;
import mekanism.cloudworks.api.work.WorkContribution;
import mekanism.cloudworks.api.recipe.RecipeFreezeContext;
import mekanism.cloudworks.api.machine.MachineOutput;
import mekanism.cloudworks.api.work.PostWorkContext;
import mekanism.cloudworks.common.recipe.CloudworksRecipeBatch;
import mekanism.cloudworks.common.upgrade.CloudworksMachineModuleRegistry;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidRegistry;
import cofh.core.inventory.ComparableItemStack;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import mekanism.cloudworks.api.machine.MachineSlotLayout;

/**
 * Thermal Expansion augment modules. Alternate recipe lists freeze in
 * onRecipes, numerical contributions come from onPreWork, and the secondary
 * null transforms outputs in onPostWork. Module identities bind one augment
 * meta each; Runtime callbacks only read immutable snapshots.
 */
final class TeAugments {

    /**
     * ItemAugment metas for the machine-side pieces declared here.
     */
    static final int SPEED_COIL_META = 128;
    static final int SECONDARY_PLUS_META = 129;
    static final int SECONDARY_NULL_META = 130;
    static final int FURNACE_FOOD_META = 256;
    static final int FURNACE_ORE_META = 257;
    static final int PYROLYSIS_META = 258;
    static final int COMPACTOR_COIN_META = 336;
    static final int COMPACTOR_GEAR_META = 337;
    static final int REFINERY_POTION_META = 369;
    static final int CENTRIFUGE_MOBS_META = 416;
    static final int EXTRUDER_SEDIMENTARY_META = 497;
    static final int PULVERIZER_PETROTHEUM_META = 273;
    static final int SAWMILL_TAPPER_META = 288;
    static final int SMELTER_PYROTHEUM_META = 304;
    static final int DYNAMO_EFFICIENCY_META = 513;
    static final int INSOLATOR_TREE_META = 323;
    static final int CRUCIBLE_LAVA_META = 352;
    static final int DYNAMO_COIL_DUCT_META = 514;
    static final int DYNAMO_THROTTLE_META = 515;
    static final int DYNAMO_COMPRESSION_FUEL_META = 673;
    static final int DYNAMO_COMPRESSION_BIOFUEL_META = 674;
    static final int DYNAMO_NUMISMATIC_GEM_META = 720;
    static final int DYNAMO_BOILER_META = 576;
    static final int DYNAMO_STEAM_TURBINE_META = 640;
    static final int SMELTER_FLUX_META = 303;
    static final int INSOLATOR_FERTILIZER_META = 320;
    static final int INSOLATOR_MONOCULTURE_META = 324;
    static final int BREWER_REAGENT_META = 448;
    static final int EXTRUDER_NO_WATER_META = 496;
    static final int REFINERY_FOSSIL_META = 368;
    static final int DYNAMO_MAGMATIC_COOLANT_META = 656;
    static final int DYNAMO_COMPRESSION_COOLANT_META = 672;
    static final int DYNAMO_REACTANT_ELEMENTAL_META = 688;

    static final ResourceLocation SPEED_COIL_ID = id("machine_speed_coil");
    static final ResourceLocation SECONDARY_PLUS_ID = id("machine_secondary");
    static final ResourceLocation SECONDARY_NULL_ID = id("machine_secondary_null");
    static final ResourceLocation FURNACE_FOOD_ID = id("furnace_food");
    static final ResourceLocation FURNACE_ORE_ID = id("furnace_ore");
    static final ResourceLocation PYROLYSIS_ID = id("furnace_pyrolysis");
    static final ResourceLocation COMPACTOR_COIN_ID = id("compactor_coin");
    static final ResourceLocation COMPACTOR_GEAR_ID = id("compactor_gear");
    static final ResourceLocation REFINERY_POTION_ID = id("refinery_potion");
    static final ResourceLocation CENTRIFUGE_MOBS_ID = id("centrifuge_mobs");
    static final ResourceLocation EXTRUDER_SEDIMENTARY_ID = id("extruder_sedimentary");
    static final ResourceLocation PULVERIZER_PETROTHEUM_ID = id("pulverizer_petrotheum");
    static final ResourceLocation SAWMILL_TAPPER_ID = id("sawmill_tapper");
    static final ResourceLocation SMELTER_PYROTHEUM_ID = id("smelter_pyrotheum");
    static final ResourceLocation DYNAMO_EFFICIENCY_ID = id("dynamo_efficiency");
    static final ResourceLocation INSOLATOR_TREE_ID = id("insolator_tree");
    static final ResourceLocation CRUCIBLE_LAVA_ID = id("crucible_lava");
    static final ResourceLocation DYNAMO_COIL_DUCT_ID = id("dynamo_coil_duct");
    static final ResourceLocation DYNAMO_THROTTLE_ID = id("dynamo_throttle");
    static final ResourceLocation DYNAMO_COMPRESSION_FUEL_ID = id("dynamo_compression_fuel");
    static final ResourceLocation DYNAMO_COMPRESSION_BIOFUEL_ID = id("dynamo_compression_biofuel");
    static final ResourceLocation DYNAMO_NUMISMATIC_GEM_ID = id("dynamo_numismatic_gem");
    static final ResourceLocation DYNAMO_BOILER_ID = id("dynamo_boiler");
    static final ResourceLocation DYNAMO_STEAM_TURBINE_ID = id("dynamo_steam_turbine");
    static final ResourceLocation SMELTER_FLUX_ID = id("smelter_flux");
    static final ResourceLocation INSOLATOR_FERTILIZER_ID = id("insolator_fertilizer");
    static final ResourceLocation INSOLATOR_MONOCULTURE_ID = id("insolator_monoculture");
    static final ResourceLocation BREWER_REAGENT_ID = id("brewer_reagent");
    static final ResourceLocation EXTRUDER_NO_WATER_ID = id("extruder_no_water");
    static final ResourceLocation REFINERY_FOSSIL_ID = id("refinery_fossil");
    static final ResourceLocation DYNAMO_MAGMATIC_COOLANT_ID = id("dynamo_magmatic_coolant");
    static final ResourceLocation DYNAMO_COMPRESSION_COOLANT_ID = id("dynamo_compression_coolant");
    static final ResourceLocation DYNAMO_REACTANT_ELEMENTAL_ID = id("dynamo_reactant_elemental");

    /**
     * Metas accepting the secondary sieve: the pulverizer family plus the transposer.
     */
    private static final int[] SECONDARY_METAS = {CloudworksTeMachines.PULVERIZER_META,
            CloudworksTeMachines.SAWMILL_META, CloudworksTeMachines.SMELTER_META,
            CloudworksTeMachines.INSOLATOR_META, CloudworksTeMachines.CENTRIFUGE_META,
            CloudworksTeMachines.REFINERY_META, CloudworksTeMachines.TRANSPOSER_META};

    /**
     * Metas accepting the secondary null: the family without the transposer.
     */
    private static final int[] SECONDARY_NULL_METAS = {CloudworksTeMachines.PULVERIZER_META,
            CloudworksTeMachines.SAWMILL_META, CloudworksTeMachines.SMELTER_META,
            CloudworksTeMachines.INSOLATOR_META, CloudworksTeMachines.CENTRIFUGE_META,
            CloudworksTeMachines.REFINERY_META};

    private static final String KEY_PREFIX = "upgrade.cloudworks.thermalexpansion.";

    /**
     * TE-native description lines per augment (snake name -> line suffixes in
     * tooltip order). Keys resolve against TE's own lang so descriptions stay
     * authored by Thermal Expansion; untranslated keys are skipped at render.
     */
    private static final Map<String, String[]> TE_DESCRIPTIONS = new HashMap<>();

    static {
                TE_DESCRIPTIONS.put("dynamo_boiler", new String[]{"0", "a.0", "a.1", "b.0", "c.0"});
                TE_DESCRIPTIONS.put("dynamo_coil_duct", new String[]{"0", "a.0"});
                TE_DESCRIPTIONS.put("dynamo_compression_biofuel", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("dynamo_compression_coolant", new String[]{"0", "a.0", "b.0", "c.0"});
                TE_DESCRIPTIONS.put("dynamo_compression_fuel", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("dynamo_efficiency", new String[]{"0", "a.0"});
                TE_DESCRIPTIONS.put("dynamo_enervation_enchant", new String[]{"0", "a.0", "a.1", "b.0", "c.0"});
                TE_DESCRIPTIONS.put("dynamo_magmatic_coolant", new String[]{"0", "a.0", "b.0", "c.0"});
                TE_DESCRIPTIONS.put("dynamo_numismatic_gem", new String[]{"0", "a.0", "a.1", "b.0"});
                TE_DESCRIPTIONS.put("dynamo_power", new String[]{"0", "a.0"});
                TE_DESCRIPTIONS.put("dynamo_reactant_elemental", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("dynamo_steam_turbine", new String[]{"0", "a.0", "c.0"});
                TE_DESCRIPTIONS.put("dynamo_throttle", new String[]{"0", "a.0"});
                TE_DESCRIPTIONS.put("machine_brewer_reagent", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("machine_centrifuge_mobs", new String[]{"0", "a.0", "a.1", "b.0", "c.0"});
                TE_DESCRIPTIONS.put("machine_charger_repair", new String[]{"0", "a.0", "b.0", "c.0"});
                TE_DESCRIPTIONS.put("machine_charger_throughput", new String[]{"0", "a.0", "c.0"});
                TE_DESCRIPTIONS.put("machine_charger_wireless", new String[]{"0", "a.0", "b.0", "c.0"});
                TE_DESCRIPTIONS.put("machine_compactor_coin", new String[]{"0", "a.0", "a.1", "c.0"});
                TE_DESCRIPTIONS.put("machine_compactor_gear", new String[]{"0", "a.0", "a.1", "c.0"});
                TE_DESCRIPTIONS.put("machine_crafter_input", new String[]{"0", "a.0", "c.0"});
                TE_DESCRIPTIONS.put("machine_crafter_tank", new String[]{"0", "a.0", "a.1", "c.0"});
                TE_DESCRIPTIONS.put("machine_crucible_alloy", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("machine_crucible_lava", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("machine_enchanter_empowered", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("machine_extruder_no_water", new String[]{"0", "a.0"});
                TE_DESCRIPTIONS.put("machine_extruder_sedimentary", new String[]{"0", "a.0", "c.0"});
                TE_DESCRIPTIONS.put("machine_furnace_food", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("machine_furnace_ore", new String[]{"0", "a.0", "b.0", "b.1"});
                TE_DESCRIPTIONS.put("machine_furnace_pyrolysis", new String[]{"0", "a.0", "b.0", "b.1"});
                TE_DESCRIPTIONS.put("machine_insolator_fertilizer", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("machine_insolator_monoculture", new String[]{"0", "a.0", "a.1", "b.0", "b.1"});
                TE_DESCRIPTIONS.put("machine_insolator_tree", new String[]{"0", "a.0", "c.0"});
                TE_DESCRIPTIONS.put("machine_power", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("machine_pulverizer_geode", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("machine_pulverizer_petrotheum", new String[]{"0", "a.0", "b.0", "c.0"});
                TE_DESCRIPTIONS.put("machine_refinery_bio", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("machine_refinery_fossil", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("machine_refinery_potion", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("machine_sawmill_tapper", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("machine_secondary", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("machine_secondary_null", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("machine_smelter_flux", new String[]{"0", "a.0", "b.0"});
                TE_DESCRIPTIONS.put("machine_smelter_pyrotheum", new String[]{"0", "a.0", "b.0", "c.0"});
    }

    private static String[] teDescriptionKeys(String snakeName) {
        // CloudWorks' machine ids omit TE's "machine" prefix; meta 128 is
        // called machine_speed_coil here but MACHINE_POWER in TE's ItemAugment.
        String nativeName = "machine_speed_coil".equals(snakeName) ? "machine_power" : snakeName;
        if (!TE_DESCRIPTIONS.containsKey(nativeName)) nativeName = "machine_" + nativeName;
        String[] suffixes = TE_DESCRIPTIONS.get(nativeName);
        if (suffixes == null) return new String[0];
        StringBuilder camel = new StringBuilder();
        for (String part : nativeName.split("_"))
            if (camel.length() == 0) camel.append(part);
            else camel.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        String[] keys = new String[suffixes.length];
        for (int i = 0; i < keys.length; i++)
            keys[i] = "info.thermalexpansion.augment." + camel + "." + suffixes[i];
        return keys;
    }

    private TeAugments() {
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("thermalexpansion", name);
    }

    // -----------------------------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------------------------

    /**
     * Declares the augment upgrade types and appends their acceptance to every
     * registered machine tier. {@code augmentStack} maps an augment meta to its
     * physical stack (tests inject stand-ins); {@code augmentSlots} is the live
     * TE {@code NUM_AUGMENTS} table; {@code socketLimit} comes from the frozen
     * RandomComplement snapshot.
     */
    static void register(Item machineItem, IntFunction<ItemStack> augmentStack, byte[] augmentSlots,
                         int socketLimit, double coilEnergyPerAugment, double rfToJoules) {
        register(machineItem, augmentStack, augmentSlots, new int[]{100, 150, 200, 250, 300},
                socketLimit, coilEnergyPerAugment, rfToJoules);
    }

    static void register(Item machineItem, IntFunction<ItemStack> augmentStack, byte[] augmentSlots,
                         int[] powerScaling, int socketLimit, double coilEnergyPerAugment, double rfToJoules) {
        Objects.requireNonNull(machineItem, "machine item");
        int sockets = Math.max(1, socketLimit);
        int stackLimit = stackLimit(augmentSlots, sockets);

        MachineModuleType<SpeedCoil> speedCoil = MachineModuleType.custom(SPEED_COIL_ID, stackLimit,
                KEY_PREFIX + "machine_speed_coil", new SpeedCoil(coilEnergyPerAugment))
                .withUpgradeAxes(UpgradeAxis.SPEED);
        CloudworksAPI.registerMachineModule(speedCoil.withItem(augmentStack.apply(SPEED_COIL_META))
                .withDescription(teDescriptionKeys("machine_speed_coil")));

        // The secondary sieve lifts every rolled secondary by
        // 100/max(5, 100-15n) at +10% energy per piece (TileMachineBase:581-585).
        MachineModuleType<SecondarySieve> secondary = MachineModuleType.custom(SECONDARY_PLUS_ID, stackLimit,
                KEY_PREFIX + "machine_secondary", new SecondarySieve());
        CloudworksAPI.registerMachineModule(secondary.withItem(augmentStack.apply(SECONDARY_PLUS_META))
                .withDescription(teDescriptionKeys("machine_secondary")));

        // The secondary null shares the same lifecycle as the numeric and recipe modules.
        MachineModuleType<SecondaryNullStrategy> secondaryNull = MachineModuleType.custom(SECONDARY_NULL_ID, 1,
                KEY_PREFIX + "machine_secondary_null", new SecondaryNullStrategy());
        CloudworksAPI.registerMachineModule(secondaryNull.withItem(augmentStack.apply(SECONDARY_NULL_META))
                .withDescription(teDescriptionKeys("machine_secondary_null")));

        Supplier<Fluid> creosote = () -> FluidRegistry.getFluid("creosote");
        MachineModuleType pyrolysis = modeSetType(PYROLYSIS_META, PYROLYSIS_ID, "furnace_pyrolysis",
                pyrolysisMechanism(creosote, rfToJoules), augmentStack);
        MachineModuleType coin = modeSetType(COMPACTOR_COIN_META, COMPACTOR_COIN_ID, "compactor_coin",
                compactorMechanism(CompactorManager.Mode.COIN, "te_compactor_coin", rfToJoules), augmentStack);
        MachineModuleType gear = modeSetType(COMPACTOR_GEAR_META, COMPACTOR_GEAR_ID, "compactor_gear",
                compactorMechanism(CompactorManager.Mode.GEAR, "te_compactor_gear", rfToJoules), augmentStack);
        MachineModuleType potion = modeSetType(REFINERY_POTION_META, REFINERY_POTION_ID, "refinery_potion",
                potionMechanism(rfToJoules), augmentStack);
        MachineModuleType mobs = modeSetType(CENTRIFUGE_MOBS_META, CENTRIFUGE_MOBS_ID, "centrifuge_mobs",
                mobsMechanism(rfToJoules), augmentStack);
        MachineModuleType sedimentary = modeSetType(EXTRUDER_SEDIMENTARY_META, EXTRUDER_SEDIMENTARY_ID,
                "extruder_sedimentary", sedimentaryMechanism(rfToJoules), augmentStack);
        MachineModuleType furnaceFood = modeSetType(FURNACE_FOOD_META, FURNACE_FOOD_ID, "furnace_food",
                furnaceFilterMechanism("te_furnace_food", TeAugments::isFood, rfToJoules), augmentStack);
        MachineModuleType furnaceOre = modeSetType(FURNACE_ORE_META, FURNACE_ORE_ID, "furnace_ore",
                furnaceFilterMechanism("te_furnace_ore", TeAugments::isOre, rfToJoules), augmentStack);
        MachineModuleType petrotheumLine = modeSetType(PULVERIZER_PETROTHEUM_META, PULVERIZER_PETROTHEUM_ID,
                "pulverizer_petrotheum", petrotheumMechanism(
                        () -> FluidRegistry.getFluid("petrotheum"), rfToJoules),
                augmentStack);
        MachineModuleType tapperLine = modeSetType(SAWMILL_TAPPER_META, SAWMILL_TAPPER_ID, "sawmill_tapper",
                tapperMechanism(rfToJoules), augmentStack);
        MachineModuleType pyrotheumLine = modeSetType(SMELTER_PYROTHEUM_META, SMELTER_PYROTHEUM_ID,
                "smelter_pyrotheum", pyrotheumMechanism(
                        () -> FluidRegistry.getFluid("pyrotheum"), rfToJoules),
                augmentStack);

        // Dynamo-only piece: the acceptance table lives with the dynamo
        // registration (CloudworksTeDynamos), not in the machine loop below.
        MachineModuleType<DynamoEfficiency> efficiency = MachineModuleType.custom(DYNAMO_EFFICIENCY_ID,
                stackLimit, KEY_PREFIX + "dynamo_efficiency", new DynamoEfficiency());
        CloudworksAPI.registerMachineModule(efficiency.withItem(augmentStack.apply(DYNAMO_EFFICIENCY_META))
                .withDescription(teDescriptionKeys("dynamo_efficiency")));
        MachineModuleType<ModeModule> tree = modeSetType(INSOLATOR_TREE_META, INSOLATOR_TREE_ID,
                "insolator_tree", insolatorTreeMechanism(rfToJoules), augmentStack);
        MachineModuleType<ModeModule> lava = modeSetType(CRUCIBLE_LAVA_META, CRUCIBLE_LAVA_ID,
                "crucible_lava", crucibleLavaMechanism(rfToJoules), augmentStack);
        MachineModuleType<NoopModule> coilDuct = noEffectType(DYNAMO_COIL_DUCT_META,
                DYNAMO_COIL_DUCT_ID, "dynamo_coil_duct", augmentStack);
        MachineModuleType<NoopModule> throttle = noEffectType(DYNAMO_THROTTLE_META,
                DYNAMO_THROTTLE_ID, "dynamo_throttle", augmentStack);
        MachineModuleType<ModeModule> compressionFuel = modeSetType(DYNAMO_COMPRESSION_FUEL_META,
                DYNAMO_COMPRESSION_FUEL_ID, "dynamo_compression_fuel",
                dynamoFluidSubset("te_dynamo_compression_fuel", "refined_fuel", 1.5D), augmentStack);
        MachineModuleType<ModeModule> compressionBiofuel = modeSetType(DYNAMO_COMPRESSION_BIOFUEL_META,
                DYNAMO_COMPRESSION_BIOFUEL_ID, "dynamo_compression_biofuel",
                dynamoFluidSubset("te_dynamo_compression_biofuel", "refined_biofuel", 1.25D), augmentStack);
        MachineModuleType<ModeModule> numismaticGem = modeSetType(DYNAMO_NUMISMATIC_GEM_META,
                DYNAMO_NUMISMATIC_GEM_ID, "dynamo_numismatic_gem",
                dynamoGemSubset(rfToJoules), augmentStack);
        MachineModuleType<ModeModule> boiler = modeSetType(DYNAMO_BOILER_META, DYNAMO_BOILER_ID,
                "dynamo_boiler", dynamoBoilerMechanism(), augmentStack);
        MachineModuleType<ModeModule> steamTurbine = modeSetType(DYNAMO_STEAM_TURBINE_META,
                DYNAMO_STEAM_TURBINE_ID, "dynamo_steam_turbine",
                dynamoSteamTurbineMechanism(rfToJoules), augmentStack);
        // Reuse-chance family: BASIC stackable pieces with count-dependent variants.
        MachineModuleType<CountModule> smelterFlux = stackableSetType(SMELTER_FLUX_META, SMELTER_FLUX_ID,
                "smelter_flux", smelterFluxMechanism(rfToJoules), augmentStack, stackLimit);
        MachineModuleType<CountModule> insolatorFertilizer = stackableSetType(INSOLATOR_FERTILIZER_META,
                INSOLATOR_FERTILIZER_ID, "insolator_fertilizer", insolatorFertilizerMechanism(rfToJoules),
                augmentStack, stackLimit);
        MachineModuleType<CountModule> brewerReagent = stackableSetType(BREWER_REAGENT_META, BREWER_REAGENT_ID,
                "brewer_reagent", brewerReagentMechanism(rfToJoules), augmentStack, stackLimit);
        MachineModuleType<ModeModule> insolatorMonoculture = modeSetType(INSOLATOR_MONOCULTURE_META,
                INSOLATOR_MONOCULTURE_ID, "insolator_monoculture",
                insolatorMonocultureMechanism(rfToJoules), augmentStack);
        MachineModuleType<ModeModule> extruderNoWater = modeSetType(EXTRUDER_NO_WATER_META, EXTRUDER_NO_WATER_ID,
                "extruder_no_water", extruderNoWaterMechanism(rfToJoules), augmentStack);
        MachineModuleType<ModeModule> refineryFossil = modeSetType(REFINERY_FOSSIL_META, REFINERY_FOSSIL_ID,
                "refinery_fossil", refineryFossilMechanism(rfToJoules), augmentStack);
        MachineModuleType<ModeModule> magmaticCoolant = modeSetType(DYNAMO_MAGMATIC_COOLANT_META,
                DYNAMO_MAGMATIC_COOLANT_ID, "dynamo_magmatic_coolant",
                dynamoMagmaticCoolantMechanism(rfToJoules), augmentStack);
        MachineModuleType<ModeModule> compressionCoolant = modeSetType(DYNAMO_COMPRESSION_COOLANT_META,
                DYNAMO_COMPRESSION_COOLANT_ID, "dynamo_compression_coolant",
                dynamoCompressionCoolantMechanism(rfToJoules), augmentStack);
        MachineModuleType<ModeModule> elementalReactant = modeSetType(DYNAMO_REACTANT_ELEMENTAL_META,
                DYNAMO_REACTANT_ELEMENTAL_ID, "dynamo_reactant_elemental",
                dynamoElementalSubset(rfToJoules), augmentStack);

        for (int level = 0; level < augmentSlots.length; level++) {
            int numeric = augmentSlots[level] & 0xFF;
            int coilLimit = numeric * sockets;
            int modeLimit = Math.min(1, coilLimit);
            acceptAllMachines(machineItem, level, speedCoil, coilLimit);
            for (int meta : SECONDARY_METAS)
                CloudworksAPI.acceptMachineUpgrade(
                        CloudworksTeMachines.machineStack(machineItem, meta, level), secondary, coilLimit);
            for (int meta : SECONDARY_NULL_METAS)
                CloudworksAPI.acceptMachineUpgrade(
                        CloudworksTeMachines.machineStack(machineItem, meta, level), secondaryNull, modeLimit);
            acceptModeSet(machineItem, level, pyrolysis, CloudworksTeMachines.FURNACE_META, modeLimit);
            acceptModeSet(machineItem, level, furnaceFood, CloudworksTeMachines.FURNACE_META, modeLimit);
            acceptModeSet(machineItem, level, furnaceOre, CloudworksTeMachines.FURNACE_META, modeLimit);
            acceptModeSet(machineItem, level, coin, CloudworksTeMachines.COMPACTOR_META, modeLimit);
            acceptModeSet(machineItem, level, gear, CloudworksTeMachines.COMPACTOR_META, modeLimit);
            acceptModeSet(machineItem, level, potion, CloudworksTeMachines.REFINERY_META, modeLimit);
            acceptModeSet(machineItem, level, mobs, CloudworksTeMachines.CENTRIFUGE_META, modeLimit);
            acceptModeSet(machineItem, level, sedimentary, CloudworksTeMachines.EXTRUDER_META, modeLimit);
            acceptModeSet(machineItem, level, petrotheumLine, CloudworksTeMachines.PULVERIZER_META, modeLimit);
            acceptModeSet(machineItem, level, tapperLine, CloudworksTeMachines.SAWMILL_META, modeLimit);
            acceptModeSet(machineItem, level, pyrotheumLine, CloudworksTeMachines.SMELTER_META, modeLimit);
            acceptModeSet(machineItem, level, tree, CloudworksTeMachines.INSOLATOR_META, modeLimit);
            acceptModeSet(machineItem, level, lava, CloudworksTeMachines.CRUCIBLE_META, modeLimit);
            acceptModeSet(machineItem, level, smelterFlux, CloudworksTeMachines.SMELTER_META, coilLimit);
            acceptModeSet(machineItem, level, insolatorFertilizer, CloudworksTeMachines.INSOLATOR_META, coilLimit);
            acceptModeSet(machineItem, level, brewerReagent, CloudworksTeMachines.BREWER_META, coilLimit);
            acceptModeSet(machineItem, level, insolatorMonoculture, CloudworksTeMachines.INSOLATOR_META, modeLimit);
            acceptModeSet(machineItem, level, extruderNoWater, CloudworksTeMachines.EXTRUDER_META, modeLimit);
            acceptModeSet(machineItem, level, refineryFossil, CloudworksTeMachines.REFINERY_META, modeLimit);
        }
        // Dynamo-only types are accepted by CloudworksTeDynamos after its
        // six type/level identities have been registered. The registry only
        // fills at freeze (after every integration registered), so the
        // acceptance reads these direct references instead of registry
        // lookups.
        DYNAMO_TYPES.clear();
        DYNAMO_TYPES.put(DYNAMO_EFFICIENCY_ID, efficiency);
        DYNAMO_TYPES.put(DYNAMO_COIL_DUCT_ID, coilDuct);
        DYNAMO_TYPES.put(DYNAMO_THROTTLE_ID, throttle);
        DYNAMO_TYPES.put(DYNAMO_COMPRESSION_FUEL_ID, compressionFuel);
        DYNAMO_TYPES.put(DYNAMO_COMPRESSION_BIOFUEL_ID, compressionBiofuel);
        DYNAMO_TYPES.put(DYNAMO_NUMISMATIC_GEM_ID, numismaticGem);
        DYNAMO_TYPES.put(DYNAMO_BOILER_ID, boiler);
        DYNAMO_TYPES.put(DYNAMO_STEAM_TURBINE_ID, steamTurbine);
        DYNAMO_TYPES.put(DYNAMO_MAGMATIC_COOLANT_ID, magmaticCoolant);
        DYNAMO_TYPES.put(DYNAMO_COMPRESSION_COOLANT_ID, compressionCoolant);
        DYNAMO_TYPES.put(DYNAMO_REACTANT_ELEMENTAL_ID, elementalReactant);
    }

    /**
     * Dynamo-side types from the latest registration; direct references, see {@link #register}.
     */
    private static final Map<ResourceLocation, MachineModuleType<?>> DYNAMO_TYPES =
            new LinkedHashMap<>();

    static MachineModuleType<?> dynamoType(ResourceLocation id) {
        return DYNAMO_TYPES.get(id);
    }

    /**
     * Type-level maximum a stackable augment may reach on the highest tier.
     */
    private static int stackLimit(byte[] augmentSlots, int sockets) {
        int max = 0;
        for (byte slots : augmentSlots) max = Math.max(max, (slots & 0xFF) * sockets);
        return max;
    }

    private static MachineModuleType modeSetType(int meta, ResourceLocation typeId, String name,
                                                 ModeModule mechanism, IntFunction<ItemStack> augmentStack) {
        MachineModuleType<ModeModule> type = MachineModuleType.custom(
                typeId, 1, KEY_PREFIX + name, mechanism)
                .withItem(augmentStack.apply(meta))
                .withDescription(teDescriptionKeys(name));
        CloudworksAPI.registerMachineModule(type);
        return type;
    }

    /**
     * The stackable (BASIC) sibling of {@link #modeSetType}: reuse-chance pieces take the tier slot table.
     */
    private static <M extends ICustomMachineModule<M>> MachineModuleType<M> stackableSetType(int meta,
                                                                                             ResourceLocation typeId, String name, M mechanism, IntFunction<ItemStack> augmentStack, int limit) {
        MachineModuleType<M> type = MachineModuleType.custom(
                typeId, limit, KEY_PREFIX + name, mechanism)
                .withItem(augmentStack.apply(meta))
                .withDescription(teDescriptionKeys(name));
        CloudworksAPI.registerMachineModule(type);
        return type;
    }

    private static MachineModuleType<NoopModule> noEffectType(int meta, ResourceLocation typeId,
                                                              String name, IntFunction<ItemStack> augmentStack) {
        MachineModuleType<NoopModule> type = MachineModuleType.custom(typeId, 1,
                KEY_PREFIX + name, new NoopModule())
                .withItem(augmentStack.apply(meta))
                .withDescription(teDescriptionKeys(name));
        CloudworksAPI.registerMachineModule(type);
        return type;
    }

    /**
     * Dynamo mode acceptance. Per TE's VALID_AUGMENTS tables the fuel
     * conversions belong to the compression dynamo (meta 2) and the gem
     * conversion to the numismatic dynamo (meta 5); the boiler rides the
     * steam/magmatic/compression families (TileDynamoSteam:50,
     * TileDynamoMagmatic:50, TileDynamoCompression:50) and the turbine the
     * steam dynamo alone (TileDynamoSteam:51); the two cloud-inert pieces
     * stay acceptable on every dynamo family.
     */
    static void acceptDynamoModes(Item dynamoItem, int[] powerScaling, byte[] augmentSlots,
                                  int socketLimit, IntFunction<ItemStack> augmentStack) {
        if (augmentStack == null) return;
        acceptDynamoType(dynamoItem, powerScaling, augmentSlots, socketLimit,
                DYNAMO_COIL_DUCT_ID, null);
        acceptDynamoType(dynamoItem, powerScaling, augmentSlots, socketLimit,
                DYNAMO_THROTTLE_ID, null);
        acceptDynamoType(dynamoItem, powerScaling, augmentSlots, socketLimit,
                DYNAMO_COMPRESSION_FUEL_ID, new int[]{2});
        acceptDynamoType(dynamoItem, powerScaling, augmentSlots, socketLimit,
                DYNAMO_COMPRESSION_BIOFUEL_ID, new int[]{2});
        acceptDynamoType(dynamoItem, powerScaling, augmentSlots, socketLimit,
                DYNAMO_NUMISMATIC_GEM_ID, new int[]{5});
        acceptDynamoType(dynamoItem, powerScaling, augmentSlots, socketLimit,
                DYNAMO_BOILER_ID, new int[]{0, 1, 2});
        acceptDynamoType(dynamoItem, powerScaling, augmentSlots, socketLimit,
                DYNAMO_STEAM_TURBINE_ID, new int[]{0});
        acceptDynamoType(dynamoItem, powerScaling, augmentSlots, socketLimit,
                DYNAMO_MAGMATIC_COOLANT_ID, new int[]{1});
        acceptDynamoType(dynamoItem, powerScaling, augmentSlots, socketLimit,
                DYNAMO_COMPRESSION_COOLANT_ID, new int[]{2});
        acceptDynamoType(dynamoItem, powerScaling, augmentSlots, socketLimit,
                DYNAMO_REACTANT_ELEMENTAL_ID, new int[]{3});
    }

    private static void acceptDynamoType(Item dynamoItem, int[] powerScaling, byte[] augmentSlots,
                                         int socketLimit, ResourceLocation typeId, int[] metas) {
        MachineModuleType<?> type = dynamoType(typeId);
        if (type == null) return;
        for (int level = 0; level < powerScaling.length && level < augmentSlots.length; level++) {
            int limit = Math.min(1, Math.max(0, (augmentSlots[level] & 0xFF) * Math.max(1, socketLimit)));
            if (metas == null) for (int meta = 0; meta < 6; meta++)
                CloudworksAPI.acceptMachineUpgrade(
                        CloudworksTeDynamos.dynamoStack(dynamoItem, meta, level), type, limit);
            else for (int meta : metas)
                CloudworksAPI.acceptMachineUpgrade(
                        CloudworksTeDynamos.dynamoStack(dynamoItem, meta, level), type, limit);
        }
    }

    private static void acceptAllMachines(Item machineItem, int level, MachineModuleType type, int limit) {
        for (int meta : CloudworksTeMachines.MACHINE_METAS)
            CloudworksAPI.acceptMachineUpgrade(CloudworksTeMachines.machineStack(machineItem, meta, level),
                    type, limit);
    }

    private static void acceptModeSet(Item machineItem, int level, MachineModuleType type, int machineMeta,
                                      int limit) {
        CloudworksAPI.acceptMachineUpgrade(
                CloudworksTeMachines.machineStack(machineItem, machineMeta, level), type, limit);
    }

    // -----------------------------------------------------------------------------------------
    // Mode-set mechanisms
    // -----------------------------------------------------------------------------------------

    /**
     * Pyrolysis: alternate furnace list plus guaranteed creosote on a new fluid
     * port, at the install-level +50% energy penalty (TileFurnace:425,
     * PYRO_ENERGY_MOD — the native mode also locks calcEnergy to minPower,
     * which is the pending rate axis).
     */
    static ModeModule pyrolysisMechanism(Supplier<Fluid> creosote, double rfToJoules) {
        return modeMechanism("te_furnace_pyrolysis", () -> FurnaceManager.getRecipeList(true),
                recipe -> describePyrolysis(recipe, creosote),
                recipe -> recipe.getEnergy() * 150 / 100, fluidOutputPort(), rfToJoules);
    }

    /**
     * Compactor coin/gear: the mode simply swaps the manager list.
     */
    static ModeModule compactorMechanism(CompactorManager.Mode mode, String idPrefix,
                                         double rfToJoules) {
        return modeMechanism(idPrefix, () -> CompactorManager.getRecipeList(mode),
                recipe -> itemToItem(recipe.getInput(), recipe.getOutput()),
                CompactorManager.CompactorRecipe::getEnergy, new MachineSlotLayout(),
                rfToJoules);
    }

    /**
     * Refinery potions: the potion tank variant of the refinery list.
     */
    static ModeModule potionMechanism(double rfToJoules) {
        return modeMechanism("te_refinery_potion", RefineryManager::getRecipeListPotion,
                CloudworksTeMachines::describeRefinery,
                RefineryManager.RefineryRecipe::getEnergy, new MachineSlotLayout(),
                rfToJoules);
    }

    /**
     * Centrifuge mobs: the mob-drop variant of the centrifuge list.
     */
    static ModeModule mobsMechanism(double rfToJoules) {
        return modeMechanism("te_centrifuge_mobs", CentrifugeManager::getRecipeListMobs,
                CloudworksTeMachines::describeCentrifuge,
                CentrifugeManager.CentrifugeRecipe::getEnergy, new MachineSlotLayout(),
                rfToJoules);
    }

    /**
     * Extruder sedimentary: the sedimentary variant of the extruder list.
     */
    static ModeModule sedimentaryMechanism(double rfToJoules) {
        return modeMechanism("te_extruder_sedimentary", () -> ExtruderManager.getRecipeList(true),
                CloudworksTeMachines::describeExtruder,
                ExtruderManager.ExtruderRecipe::getEnergy, new MachineSlotLayout(),
                rfToJoules);
    }

    /**
     * The pyrolysis mode adds the creosote fluid output port to the furnace.
     */
    private static MachineSlotLayout fluidOutputPort() {
        return new MachineSlotLayout()
                .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID,
                        MachineSlotLayout.PortRole.OUTPUT,
                        4000);
    }

    /**
     * The food/ore furnace lines: the standard smelting list filtered by the
     * augment's classifier with the native {@code +max(1, count/2)} primary
     * boost. Frozen-spec identities cannot be re-classified (item matchers
     * carry no extractable payload), so the transformer re-freezes the live
     * manager list with the filter applied — same recipes, same energies,
     * same tier-derived durations as the base mode.
     */
    static ModeModule furnaceFilterMechanism(String idPrefix,
                                             Predicate<ItemStack> inputFilter, double rfToJoules) {
        return new ModeModule(context -> RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                freezeFiltered(idPrefix, inputFilter, context.machine(), rfToJoules),
                new MachineSlotLayout()));
    }

    /**
     * Freezes the classifier-passing subset of the standard furnace list.
     */
    static List<CloudworksRecipeSpec> freezeFiltered(String idPrefix,
                                                     Predicate<ItemStack> inputFilter, CloudworksMachineRegistration machine,
                                                     double rfToJoules) {
        FurnaceManager.FurnaceRecipe[] recipes = FurnaceManager.getRecipeList(false);
        List<CloudworksRecipeSpec> result = new ArrayList<>();
        if (recipes == null) return Collections.unmodifiableList(result);
        for (FurnaceManager.FurnaceRecipe recipe : recipes) {
            if (recipe == null) continue;
            ItemStack input = recipe.getInput();
            ItemStack output = recipe.getOutput();
            if (input.isEmpty() || output.isEmpty() || !inputFilter.test(input)) continue;
            try {
                ItemStack boosted = output.copy();
                boosted.setCount(boostedItemCount(boosted.getCount()));
                result.add(new CloudworksRecipeBatch()
                        .item(input)
                        .output(boosted)
                        .specification(idPrefix + "_" + result.size(),
                                // TileFurnace:413/:419 — FOOD/ORE_ENERGY_MOD is a
                                // +50% cost penalty on the specialized line.
                                durationTicks(recipe.getEnergy() * 150 / 100, machine, rfToJoules)));
            } catch (IllegalArgumentException unsupported) {
                // One unfreezable recipe rejects only itself.
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * TE's own food classifier, straight from the manager.
     */
    static boolean isFood(ItemStack stack) {
        return FurnaceManager.isFood(stack);
    }

    /**
     * TE's own ore classifier, straight from the manager.
     */
    static boolean isOre(ItemStack stack) {
        return FurnaceManager.isOre(stack);
    }

    /**
     * TE output slots are ordinary 1.12 item stacks. Clamp package-provided
     * primary counts before the +50% augment so a large external recipe cannot
     * overflow the stack/port amount during contract validation.
     */
    static int boostedItemCount(int base) {
        if (base <= 0) return 0;
        long boosted = (long) base + Math.max(1L, base / 2L);
        return (int) Math.min(64L, boosted);
    }

    // -----------------------------------------------------------------------------------------
    // Petrotheum / tapper / pyrotheum lines (recipe + port pieces)
    // -----------------------------------------------------------------------------------------

    /**
     * Petrotheum line (TilePulverizer:210-244, install energyMod :413-416): ore
     * recipes drain 100mB petrotheum per operation through a new fluid input
     * port, gain the {@code +max(1, 50%)} primary boost and the
     * {@code chance/(100-25)} secondary roll; every recipe pays the install-level
     * {@code +50%} energy penalty.
     */
    static ModeModule petrotheumMechanism(Supplier<Fluid> petrotheum, double rfToJoules) {
        return modeMechanism("te_pulverizer_petrotheum", PulverizerManager::getRecipeList,
                recipe -> describePetrotheum(recipe, petrotheum),
                recipe -> recipe.getEnergy() * 150 / 100,
                fluidInputPort("petrotheum_input"), rfToJoules);
    }

    /**
     * Tapper line (TileSawmill:219-222 fluid effect, :455-460 install): pure
     * additive fluid output — logs with a TapperManager mapping additionally
     * produce their resin on a new fluid output port — behind the install-level
     * +50% energy penalty (TAPPER_ENERGY_MOD). No chance side effects.
     */
    static ModeModule tapperMechanism(double rfToJoules) {
        return modeMechanism("te_sawmill_tapper", SawmillManager::getRecipeList,
                recipe -> describeTapper(recipe, TeAugments::tapperResin),
                recipe -> recipe.getEnergy() * 150 / 100,
                new MachineSlotLayout()
                        .add("resin_output", QIOResourceCodecs.FLUID_STACK_ID,
                                MachineSlotLayout.PortRole.OUTPUT,
                                4000),
                rfToJoules);
    }

    /**
     * Per-log resin lookup; null when the log has no tapper mapping. The
     * manager's static initializer pulls Thermal Foundation fluid state, so a
     * headless JVM (unit tests without a TE runtime) degrades to "no
     * mappings" instead of poisoning the freeze — the same
     * degrade-don't-break pattern the tapper device table uses.
     */
    private static FluidStack tapperResin(ItemStack log) {
        try {
            return TapperManager.mappingExists(log) ? TapperManager.getFluid(log) : null;
        } catch (LinkageError | RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * Pyrotheum line (TileSmelter:228-263, install energyMod :533-536): the
     * smelter twin of petrotheum — either input slot being ore qualifies, the
     * secondary denominator drops by 30 and every recipe pays {@code +50%}
     * energy.
     */
    static ModeModule pyrotheumMechanism(Supplier<Fluid> pyrotheum, double rfToJoules) {
        return modeMechanism("te_smelter_pyrotheum", SmelterManager::getRecipeList,
                recipe -> describePyrotheum(recipe, pyrotheum),
                recipe -> recipe.getEnergy() * 150 / 100,
                fluidInputPort("pyrotheum_input"), rfToJoules);
    }

    static CloudworksRecipeBatch describePetrotheum(PulverizerManager.PulverizerRecipe recipe,
                                                    Supplier<Fluid> petrotheum) {
        ItemStack input = recipe.getInput();
        ItemStack primary = recipe.getPrimaryOutput();
        if (input.isEmpty() || primary.isEmpty()) return new CloudworksRecipeBatch();
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        batch.item(input);
        // Without the fluid the native gate never fires, so the recipe keeps
        // its base semantics and only the install-level energy penalty stays.
        boolean ore = PulverizerManager.isOre(input) && petrotheum.get() != null;
        if (ore) {
            ItemStack boosted = primary.copy();
            boosted.setCount(boostedItemCount(boosted.getCount()));
            batch.output(boosted);
            batch.fluid(new FluidStack(petrotheum.get(), 100), "petrotheum_input");
        } else {
            batch.output(primary);
        }
        ItemStack secondary = recipe.getSecondaryOutput();
        int chance = recipe.getSecondaryOutputChance();
        if (!secondary.isEmpty() && chance > 0)
            batch.chanceOutput(secondary, ore ? Math.min(1D, chance / 75D) : chance / 100D);
        return batch;
    }

    static CloudworksRecipeBatch describeTapper(SawmillManager.SawmillRecipe recipe,
                                                Function<ItemStack, FluidStack> resin) {
        ItemStack input = recipe.getInput();
        ItemStack primary = recipe.getPrimaryOutput();
        if (input.isEmpty() || primary.isEmpty()) return new CloudworksRecipeBatch();
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        batch.item(input);
        batch.output(primary);
        ItemStack secondary = recipe.getSecondaryOutput();
        int chance = recipe.getSecondaryOutputChance();
        if (!secondary.isEmpty() && chance > 0) batch.chanceOutput(secondary, chance / 100D);
        FluidStack treeFluid = resin.apply(input);
        if (treeFluid != null && treeFluid.amount > 0)
            batch.output(treeFluid.copy(), "resin_output");
        return batch;
    }

    static CloudworksRecipeBatch describePyrotheum(SmelterManager.SmelterRecipe recipe,
                                                   Supplier<Fluid> pyrotheum) {
        ItemStack primaryIn = recipe.getPrimaryInput();
        ItemStack primaryOut = recipe.getPrimaryOutput();
        if (primaryIn.isEmpty() || primaryOut.isEmpty()) return new CloudworksRecipeBatch();
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        batch.item(primaryIn);
        ItemStack secondaryIn = recipe.getSecondaryInput();
        if (!secondaryIn.isEmpty()) batch.item(secondaryIn);
        boolean ore = pyrotheum.get() != null && (SmelterManager.isOre(primaryIn)
                || !secondaryIn.isEmpty() && SmelterManager.isOre(secondaryIn));
        if (ore) {
            ItemStack boosted = primaryOut.copy();
            boosted.setCount(boostedItemCount(boosted.getCount()));
            batch.output(boosted);
            batch.fluid(new FluidStack(pyrotheum.get(), 100), "pyrotheum_input");
        } else {
            batch.output(primaryOut);
        }
        ItemStack secondaryOut = recipe.getSecondaryOutput();
        int chance = recipe.getSecondaryOutputChance();
        if (!secondaryOut.isEmpty() && chance > 0)
            batch.chanceOutput(secondaryOut, ore ? Math.min(1D, chance / 70D) : chance / 100D);
        return batch;
    }

    /**
     * Tree infusion (TileInsolator:141): without the augment TREE recipes
     * refuse to start, so the base registration freezes only the normal
     * subset (see {@link CloudworksTeMachines#nonTreeInsolatorList()}). The
     * contribution lifts the gate — REPLACE with the complete list.
     */
    static ModeModule insolatorTreeMechanism(double rfToJoules) {
        return new ModeModule(context -> RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                CloudworksTeMachines.freezeInsolatorTree(context.machine(), rfToJoules),
                context.basePorts()));
    }

    static ModeModule crucibleLavaMechanism(double rfToJoules) {
        return new ModeModule(context -> {
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            CrucibleManager.CrucibleRecipe[] recipes = CrucibleManager.getRecipeList();
            if (recipes != null) for (CrucibleManager.CrucibleRecipe recipe : recipes) {
                if (recipe == null || recipe.getInput().isEmpty() || recipe.getOutput() == null
                        || !CrucibleManager.isLava(recipe.getInput())) continue;
                try {
                    // TileCrucible:125-131 keeps the total RF and multiplies the
                    // draw (LAVA_MULTIPLIER x10, maxTransfer x10 at :407): one
                    // tenth the duration plus nine extra rates' worth of per-tick
                    // energy lands on the same total.
                    result.add(new CloudworksRecipeBatch()
                            .item(recipe.getInput())
                            .unscaledOutput(QIOResourceCodecs.fluid(recipe.getOutput()), recipe.getOutput().amount, "")
                            .extraEnergy(context.machine().profile().energyPerTick() * (LAVA_MULTIPLIER - 1))
                            .specification("te_crucible_lava_" + result.size(),
                                    Math.max(1, durationTicks(recipe.getEnergy(), context.machine(), rfToJoules) / LAVA_MULTIPLIER)));
                } catch (IllegalArgumentException ignored) {
                    // One unsupported lava recipe never blocks the manager.
                }
            }
            return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                    result, context.basePorts());
        });
    }

    /**
     * Compression fuel conversion (TileDynamoCompression:179/:294/:317-322):
     * the tank locks to one fuel fluid and every burned unit yields its table
     * energy times the install-level energy mod (+50% fuel, +25% biofuel).
     * The contribution filters the frozen fuel contracts down to the locked
     * fluid and rescales their energy per operation — REPLACE, never APPEND.
     */
    static ModeModule dynamoFluidSubset(String id, String fluidName, double energyFactor) {
        return new ModeModule(context -> {
            Fluid fluid = FluidRegistry.getFluid(fluidName);
            if (fluid == null) return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                    Collections.emptyList(), context.basePorts());
            QIOResourceDescriptor descriptor = QIOResourceCodecs.fluid(new FluidStack(fluid, 1));
            List<CloudworksRecipeSpec> selected = new ArrayList<>();
            for (CloudworksRecipeSpec spec : context.baseRecipes())
                if (!spec.inputs().isEmpty() && spec.inputs().get(0).matcher().matches(descriptor))
                    selected.add(spec.toBuilder(id + "_" + selected.size())
                            .energyPerOperation(spec.energyPerOperation() * energyFactor).build());
            return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                    selected, context.basePorts());
        });
    }

    /**
     * Numismatic gem conversion (TileDynamoNumismatic:86-95): the augment
     * switches the whole fuel lookup to the manager's separate gem table with
     * its own energies — not a subset of the coin table. The contribution
     * freezes the gem table fresh; the coin entries leave with the REPLACE.
     */
    static ModeModule dynamoGemSubset(double rfToJoules) {
        return new ModeModule(context -> {
            List<CloudworksRecipeSpec> selected = new ArrayList<>();
            Set<ComparableItemStack> fuels = NumismaticManager.getGemFuels();
            if (fuels != null) for (ComparableItemStack fuel : fuels) {
                if (fuel == null) continue;
                ItemStack gem = fuel.toItemStack();
                int energy = NumismaticManager.getGemFuelEnergy(gem);
                if (gem.isEmpty() || energy <= 0) continue;
                try {
                    // TileDynamoNumismatic:202-208 — the gem install also carries
                    // energyMod +25, so the separate table's energies pay x1.25.
                    selected.add(new CloudworksRecipeBatch().item(gem)
                            .energy(energy * 1.25D * rfToJoules)
                            .specification("te_dynamo_numismatic_gem_" + selected.size(), 1));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable gem never blocks the table.
                }
            }
            return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                    selected, context.basePorts());
        });
    }

    // -----------------------------------------------------------------------------------------
    // Boiler / steam turbine conversions (TileDynamoSteam family)
    // -----------------------------------------------------------------------------------------

    /**
     * TileDynamoSteam:316-323 — the steam boiler install carries energyMod +15.
     */
    static final double STEAM_BOILER_ENERGY_MOD = 1.15D;
    /**
     * TileDynamoCompression:304-311 / TileDynamoMagmatic:315-322 — fluid boilers pay energyMod -40.
     */
    static final double FLUID_BOILER_ENERGY_MOD = 0.6D;
    /**
     * TileDynamoSteam:148-154 — the turbine settles at 2 RF per mB of steam.
     */
    static final double TURBINE_RF_PER_MB = 2D;
    /**
     * The turbine contract denominates per bucket like every other fluid fuel.
     */
    static final int TURBINE_CONTRACT_MB = 1000;
    /**
     * TileCrucible:51 LAVA_MULTIPLIER — ten times the draw at one tenth the duration.
     */
    static final int LAVA_MULTIPLIER = 10;

    static MachineSlotLayout steamOutputPort(int capacity) {
        return new MachineSlotLayout()
                .add("steam_output", QIOResourceCodecs.FLUID_STACK_ID,
                        MachineSlotLayout.PortRole.OUTPUT,
                        Math.max(1, capacity));
    }

    /**
     * The fuel-contract id prefix naming this dynamo family, or "" when unknown.
     */
    static String dynamoFamily(RecipeFreezeContext context) {
        for (CloudworksRecipeSpec spec : context.baseRecipes()) {
            String id = spec.id();
            if (id.startsWith("te_dynamo_steam_")) return "te_dynamo_steam";
            if (id.startsWith("te_dynamo_magmatic_")) return "te_dynamo_magmatic";
            if (id.startsWith("te_dynamo_compression_")) return "te_dynamo_compression";
            if (id.startsWith("te_dynamo_reactant_")) return "te_dynamo_reactant";
            if (id.startsWith("te_dynamo_enervation_")) return "te_dynamo_enervation";
            if (id.startsWith("te_dynamo_numismatic_")) return "te_dynamo_numismatic";
        }
        return "";
    }

    /**
     * Boiler conversion (TileDynamoSteam:316-323/:123-147, TileDynamoMagmatic:315-322,
     * TileDynamoCompression:304-311): the dynamo stops making RF and ships steam
     * through a new fluid output port instead. The steam line burns solid fuel at
     * its own +15% energy mod with water in lockstep (25,000 adjusted fuel RF
     * per 100 mB water; the adjusted RF value is emitted as steam mB); the
     * magmatic and compression lines pay the −40 penalty (×0.6) and take
     * their water through the coolant table at getCoolantRF100mB rates.
     * The native maxPower pacing is the pending rate axis; conservation is exact.
     */
    static ModeModule dynamoBoilerMechanism() {
        return new ModeModule(context -> {
            Fluid steam = FluidRegistry.getFluid("steam");
            String family = dynamoFamily(context);
            List<CloudworksRecipeBatch> batches = new ArrayList<>();
            if (steam != null && "te_dynamo_steam".equals(family)) {
                Set<ComparableItemStack> fuels = SteamManager.getFuels();
                if (fuels != null) for (ComparableItemStack fuel : fuels) {
                    if (fuel == null) continue;
                    ItemStack stack = fuel.toItemStack();
                    int rf = safeInt(() -> SteamManager.getFuelEnergy(stack));
                    if (stack.isEmpty() || rf <= 0) continue;
                    double steamRF = rf * STEAM_BOILER_ENERGY_MOD;
                    int waterAmount = TeDynamoFuels.fluidAmountForBatchedEnergy(steamRF,
                            TeDynamoFuels.STEAM_RF_PER_100_MB_WATER);
                    int steamAmount = safeFluidAmount(steamRF);
                    if (waterAmount <= 0 || steamAmount <= 0) continue;
                    batches.add(new CloudworksRecipeBatch()
                            .item(stack)
                            .fluid(new FluidStack(FluidRegistry.WATER, waterAmount))
                            .output(new FluidStack(steam, steamAmount), "steam_output"));
                }
            } else if (steam != null && ("te_dynamo_magmatic".equals(family)
                    || "te_dynamo_compression".equals(family))) {
                boolean magmatic = "te_dynamo_magmatic".equals(family);
                Set<String> fuels = magmatic ? MagmaticManager.getFuels() : CompressionManager.getFuels();
                int coolantRF100 = CoolantManager.getCoolantRF100mB(FluidRegistry.WATER);
                if (fuels != null && coolantRF100 > 0) for (String name : fuels) {
                    Fluid fluid = FluidRegistry.getFluid(name);
                    if (fluid == null) continue;
                    int rf = magmatic
                            ? safeInt(() -> MagmaticManager.getFuelEnergy(new FluidStack(fluid, 1000)))
                            : safeInt(() -> CompressionManager.getFuelEnergy(new FluidStack(fluid, 1000)));
                    if (rf <= 0) continue;
                    double steamRF = rf * FLUID_BOILER_ENERGY_MOD;
                    int coolantAmount = TeDynamoFuels.fluidAmountForBatchedEnergy(steamRF, coolantRF100);
                    int steamAmount = safeFluidAmount(steamRF);
                    if (coolantAmount <= 0 || steamAmount <= 0) continue;
                    batches.add(new CloudworksRecipeBatch()
                            .fluid(new FluidStack(fluid, 1000))
                            .fluid(new FluidStack(FluidRegistry.WATER, coolantAmount))
                            .output(new FluidStack(steam, steamAmount), "steam_output"));
                }
            }
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            for (CloudworksRecipeBatch batch : batches) {
                try {
                    result.add(TeDynamoFuels.streamSideInput(batch.specification("te_dynamo_boiler_" + family + "_" + result.size(), 1)));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable fuel rejects only itself.
                }
            }
            /*
             * Boiler output is a per-recipe alternative: one operation emits the
             * steam value of the selected fuel, so the port only needs to hold
             * the largest single recipe output. A fixed 10,000 mB port rejects
             * valid TE fuel quotes (SteamManager permits up to 200,000,000 RF).
             * Keep the public slot bounded to the largest representable FluidStack
             * amount and let the contract validator check each recipe against it.
             */
            long maximum = 1L;
            for (CloudworksRecipeSpec spec : result)
                for (CloudworksRecipeSpec.Output output : spec.outputs())
                    if ("steam_output".equals(output.port()))
                        maximum = Math.max(maximum, output.amount());
            int capacity = (int) Math.min(Integer.MAX_VALUE, maximum);
            return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                    Collections.unmodifiableList(result), steamOutputPort(capacity));
        });
    }

    /**
     * Steam turbine conversion (TileDynamoSteam:324-331/:104-169): the tank locks
     * to steam, solid fuel becomes invalid, and the one contract settles at
     * 2 RF per mB (lastEnergy RF for lastEnergy/2 mB). The 2000 mB operating
     * reserve (STEAM_MINIMUM) and the 250 mB idle vent are buffer mechanics, not
     * conservation, and stay out of the contract; the native maxPower pacing is
     * the pending rate axis.
     */
    static ModeModule dynamoSteamTurbineMechanism(double rfToJoules) {
        return new ModeModule(context -> {
            Fluid steam = FluidRegistry.getFluid("steam");
            if (steam == null || !"te_dynamo_steam".equals(dynamoFamily(context)))
                return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                        Collections.emptyList(), new MachineSlotLayout());
            return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                    Collections.singletonList(new CloudworksRecipeBatch()
                            .fluid(new FluidStack(steam, TURBINE_CONTRACT_MB))
                            .energy(TURBINE_RF_PER_MB * TURBINE_CONTRACT_MB * rfToJoules)
                            .specification("te_dynamo_turbine_0", 1)),
                    new MachineSlotLayout());
        });
    }

    // -----------------------------------------------------------------------------------------
    // Reuse-chance family + late MODE pieces (@303/@320/@448/@324/@496/@368, 656/672/688)
    // -----------------------------------------------------------------------------------------

    /**
     * TileMachineBase:68 REUSE_MAX — the survival chance tops out at 95%.
     */
    private static final double REUSE_CHANCE_CAP = 0.95D;
    /**
     * TileRefinery:51 SPEC_FLUID_BOOST.
     */
    private static final int REFINERY_FOSSIL_BOOST_MB = 50;

    /**
     * Survival chance of the aux input at n installed pieces (per-piece 15/20/15%).
     */
    static double reuseChance(int pieces, double perPiece) {
        return Math.min(REUSE_CHANCE_CAP, perPiece * pieces);
    }

    /**
     * Mode module whose contribution depends on the effective installed count.
     */
    static final class CountModule implements ICustomMachineModule<CountModule> {
        private final BiFunction<IMachineModule<CountModule>, RecipeFreezeContext,
                RecipeContribution> recipes;

        CountModule(BiFunction<IMachineModule<CountModule>, RecipeFreezeContext,
                RecipeContribution> recipes) {
            this.recipes = recipes;
        }

        @Override
        public RecipeContribution onRecipes(IMachineModule<CountModule> module, RecipeFreezeContext context) {
            return recipes.apply(module, context);
        }
    }

    /**
     * Metallurgical flux (TileSmelter:529-532 effects, :267-287 the survival roll):
     * each BASIC piece adds a 15% chance the flux input survives the operation
     * and +15% energy. The survival rides the native chance-output axis — the
     * same per-operation dice TE throws — and the count variant is cached per
     * installed map like the dynamo efficiency line.
     */
    static CountModule smelterFluxMechanism(double rfToJoules) {
        return new CountModule((module, context) -> {
            int count = module.getEffectiveInstalledCount();
            double survive = reuseChance(count, 0.15D);
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            SmelterManager.SmelterRecipe[] recipes = SmelterManager.getRecipeList();
            if (recipes != null) for (SmelterManager.SmelterRecipe recipe : recipes) {
                if (recipe == null) continue;
                try {
                    result.add(describeFlux(recipe, survive).specification("te_smelter_flux_" + result.size(),
                            durationTicks(recipe.getEnergy() * (100 + 15 * count) / 100,
                                    context.machine(), rfToJoules)));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable recipe rejects only itself.
                }
            }
            return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                    Collections.unmodifiableList(result), new MachineSlotLayout());
        });
    }

    /**
     * The smelter line plus the flux survival roll on flux-bearing recipes.
     */
    private static CloudworksRecipeBatch describeFlux(SmelterManager.SmelterRecipe recipe, double survive) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        ItemStack primaryIn = recipe.getPrimaryInput();
        ItemStack primaryOut = recipe.getPrimaryOutput();
        if (primaryIn.isEmpty() || primaryOut.isEmpty()) return batch;
        batch.item(primaryIn);
        ItemStack secondaryIn = recipe.getSecondaryInput();
        if (!secondaryIn.isEmpty()) batch.item(secondaryIn);
        batch.output(primaryOut);
        if (recipe.hasFlux() && !secondaryIn.isEmpty() && survive > 0D)
            batch.chanceOutput(secondaryIn, survive);
        ItemStack secondaryOut = recipe.getSecondaryOutput();
        int chance = recipe.getSecondaryOutputChance();
        if (!secondaryOut.isEmpty() && chance > 0) batch.chanceOutput(secondaryOut, chance / 100D);
        return batch;
    }

    /**
     * Nutrient recovery (TileInsolator:531-534, :251-262): 20% per piece that
     * the fertilizer survives, +15% energy per piece, over the gated (non-TREE)
     * base list.
     */
    static CountModule insolatorFertilizerMechanism(double rfToJoules) {
        return new CountModule((module, context) -> {
            int count = module.getEffectiveInstalledCount();
            double survive = reuseChance(count, 0.20D);
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            InsolatorManager.InsolatorRecipe[] recipes = CloudworksTeMachines.nonTreeInsolatorList();
            if (recipes != null) for (InsolatorManager.InsolatorRecipe recipe : recipes) {
                if (recipe == null) continue;
                try {
                    result.add(describeInsolatorVariant(recipe, survive, false)
                            .specification("te_insolator_fertilizer_" + result.size(),
                                    durationTicks(recipe.getEnergy() * (100 + 15 * count) / 100,
                                            context.machine(), rfToJoules)));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable recipe rejects only itself.
                }
            }
            return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                    Collections.unmodifiableList(result), new MachineSlotLayout());
        });
    }

    /**
     * Reagent recovery (TileBrewer:388-391): 15% per piece that the reagent
     * survives, +15% energy per piece.
     */
    static CountModule brewerReagentMechanism(double rfToJoules) {
        return new CountModule((module, context) -> {
            int count = module.getEffectiveInstalledCount();
            double survive = reuseChance(count, 0.15D);
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            BrewerManager.BrewerRecipe[] recipes = BrewerManager.getRecipeList();
            if (recipes != null) for (BrewerManager.BrewerRecipe recipe : recipes) {
                if (recipe == null) continue;
                ItemStack input = recipe.getInput();
                FluidStack inputFluid = recipe.getInputFluid();
                FluidStack output = recipe.getOutputFluid();
                if (input.isEmpty() || inputFluid == null || inputFluid.amount <= 0
                        || output == null || output.amount <= 0) continue;
                try {
                    CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
                    batch.item(input);
                    batch.fluid(inputFluid);
                    batch.output(output);
                    if (survive > 0D) batch.chanceOutput(input, survive);
                    result.add(batch.specification("te_brewer_reagent_" + result.size(),
                            durationTicks(recipe.getEnergy() * (100 + 15 * count) / 100,
                                    context.machine(), rfToJoules)));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable recipe rejects only itself.
                }
            }
            // The brewer has no native item output slot; the surviving reagent
            // comes back through the mechanism's own return port (the same
            // port-addition pattern as the tapper's resin output).
            return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                    Collections.unmodifiableList(result), new MachineSlotLayout()
                            .add("reagent_return", QIOResourceCodecs.ITEM_STACK_ID,
                                    MachineSlotLayout.PortRole.OUTPUT, 64));
        });
    }

    /**
     * Monoculture cycle (TileInsolator:535-541 install, :231-233/:248 effects):
     * the seed (primary input) survives every operation, the seed secondary
     * never drops (recipeChance − 100), the fertilizer gains a 10% survival
     * roll (install reuseChance +10) and every recipe pays the +50% energy
     * penalty (MONOCULTURE_ENERGY_MOD).
     */
    static ModeModule insolatorMonocultureMechanism(double rfToJoules) {
        return new ModeModule(context -> {
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            InsolatorManager.InsolatorRecipe[] recipes = CloudworksTeMachines.nonTreeInsolatorList();
            if (recipes != null) for (InsolatorManager.InsolatorRecipe recipe : recipes) {
                if (recipe == null) continue;
                try {
                    result.add(describeInsolatorVariant(recipe, 0.10D, true)
                            .specification("te_insolator_monoculture_" + result.size(),
                                    durationTicks(recipe.getEnergy() * 150 / 100,
                                            context.machine(), rfToJoules)));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable recipe rejects only itself.
                }
            }
            return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                    Collections.unmodifiableList(result), new MachineSlotLayout());
        });
    }

    /**
     * The insolator line with optional fertilizer survival and monoculture seed semantics.
     */
    private static CloudworksRecipeBatch describeInsolatorVariant(InsolatorManager.InsolatorRecipe recipe,
                                                                  double fertilizerSurvive, boolean monoculture) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        ItemStack primaryIn = recipe.getPrimaryInput();
        ItemStack primaryOut = recipe.getPrimaryOutput();
        if (primaryIn.isEmpty() || primaryOut.isEmpty()) return batch;
        batch.item(primaryIn);
        ItemStack secondaryIn = recipe.getSecondaryInput();
        if (!secondaryIn.isEmpty()) batch.item(secondaryIn);
        int water = recipe.getWater();
        if (water > 0) batch.fluid(new FluidStack(FluidRegistry.WATER, water));
        batch.output(primaryOut);
        boolean fertilized = recipe.hasFertilizer() && !secondaryIn.isEmpty();
        if (monoculture && fertilized)
            batch.unscaledOutput(QIOResourceCodecs.item(primaryIn), primaryIn.getCount(), "");
        ItemStack secondaryOut = recipe.getSecondaryOutput();
        int chance = recipe.getSecondaryOutputChance();
        if (!secondaryOut.isEmpty() && chance > 0
                && !(monoculture && secondaryOut.isItemEqual(primaryIn)))
            batch.chanceOutput(secondaryOut, chance / 100D);
        if (fertilized && fertilizerSurvive > 0D)
            batch.chanceOutput(secondaryIn, fertilizerSurvive);
        return batch;
    }

    /**
     * No-water extrusion (TileExtruder:455-459 install, :145/:191-193 effects):
     * the cold (water) side never gates or drains; hot side, output and energy
     * unchanged. Zero-amount native recipes (cobblestone carries lava 0) stay
     * excluded exactly like the base line.
     */
    static ModeModule extruderNoWaterMechanism(double rfToJoules) {
        return new ModeModule(context -> {
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            ExtruderManager.ExtruderRecipe[] recipes = ExtruderManager.getRecipeList(false);
            if (recipes != null) for (ExtruderManager.ExtruderRecipe recipe : recipes) {
                if (recipe == null) continue;
                FluidStack hot = recipe.getInputHot();
                ItemStack output = recipe.getOutput();
                if (hot == null || hot.amount <= 0 || output.isEmpty()) continue;
                try {
                    result.add(new CloudworksRecipeBatch()
                            .fluid(hot)
                            .output(output)
                            .specification("te_extruder_no_water_" + result.size(),
                                    durationTicks(recipe.getEnergy(), context.machine(), rfToJoules)));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable recipe rejects only itself.
                }
            }
            return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                    Collections.unmodifiableList(result), new MachineSlotLayout());
        });
    }

    /**
     * Fossil specialization (TileRefinery:438-443 install, :211-215 effects):
     * only fossil fuels convert, the output gains +50 mB (SPEC_FLUID_BOOST)
     * and every operation pays double energy (SPEC_ENERGY_MOD 100).
     */
    static ModeModule refineryFossilMechanism(double rfToJoules) {
        return new ModeModule(context -> {
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            RefineryManager.RefineryRecipe[] recipes = RefineryManager.getRecipeList();
            if (recipes != null) for (RefineryManager.RefineryRecipe recipe : recipes) {
                if (recipe == null) continue;
                FluidStack input = recipe.getInput();
                FluidStack output = recipe.getOutputFluid();
                if (input == null || input.amount <= 0 || output == null || output.amount <= 0
                        || !RefineryManager.isFossilFuel(input)) continue;
                try {
                    FluidStack boosted = output.copy();
                    boosted.amount += REFINERY_FOSSIL_BOOST_MB;
                    CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
                    batch.fluid(input);
                    batch.output(boosted);
                    ItemStack outputItem = recipe.getOutputItem();
                    int chance = recipe.getChance();
                    if (!outputItem.isEmpty() && chance > 0) batch.chanceOutput(outputItem, chance / 100D);
                    result.add(batch.specification("te_refinery_fossil_" + result.size(),
                            durationTicks(recipe.getEnergy() * 200 / 100, context.machine(), rfToJoules)));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable recipe rejects only itself.
                }
            }
            return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                    Collections.unmodifiableList(result), new MachineSlotLayout());
        });
    }

    /**
     * Elemental catalysis (TileDynamoReactant:254-260): the reaction table
     * narrows to the elemental subset (validReactantElemental ×
     * validFluidElemental) and every reaction pays energyMod +25 (×1.25).
     */
    static ModeModule dynamoElementalSubset(double rfToJoules) {
        return new ModeModule(context -> {
            List<CloudworksRecipeBatch> batches = new ArrayList<>();
            ReactantManager.Reaction[] reactions = ReactantManager.getReactionList();
            if (reactions != null) for (ReactantManager.Reaction reaction : reactions) {
                if (reaction == null || reaction.getReactant().isEmpty() || reaction.getFluid() == null
                        || reaction.getEnergy() <= 0) continue;
                if (!ReactantManager.validReactantElemental(reaction.getReactant())
                        || !ReactantManager.validFluidElemental(reaction.getFluid())) continue;
                batches.add(new CloudworksRecipeBatch()
                        .item(reaction.getReactant())
                        .fluid(new FluidStack(reaction.getFluid(), TeDynamoFuels.REACTANT_FLUID_MB))
                        .energy(reaction.getEnergy() * 1.25D * rfToJoules));
            }
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            for (CloudworksRecipeBatch batch : batches) {
                try {
                    result.add(batch.specification("te_dynamo_reactant_elemental_" + result.size(), 1));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable reaction rejects only itself.
                }
            }
            return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                    Collections.unmodifiableList(result), context.basePorts());
        });
    }

    /**
     * Isentropic reservoir (TileDynamoMagmatic:323-329 install, :121-131/:148-155
     * effects): the coolant factor joins the fuel mod — fuelRF = table ×
     * (energyMod + coolantFactor − WATER_FACTOR)/100 with energyMod 115 — and
     * the coolant burns in lockstep at getCoolantRF100mB rates. One contract
     * variant per (fuel × coolant) pair; which coolant applies is a pipeline
     * choice, the same "quality rides the piped fluid" rule as the heat sink.
     */
    static ModeModule dynamoMagmaticCoolantMechanism(double rfToJoules) {
        return new ModeModule(context -> {
            List<CloudworksRecipeBatch> batches = new ArrayList<>();
            Set<String> fuels = MagmaticManager.getFuels();
            Set<String> coolants = CoolantManager.getCoolantFluids();
            if (fuels != null && coolants != null) for (String coolantName : coolants) {
                Fluid coolant = FluidRegistry.getFluid(coolantName);
                if (coolant == null) continue;
                int factor = CoolantManager.getCoolantFactor(coolant);
                int rf100 = CoolantManager.getCoolantRF100mB(coolant);
                if (factor <= 0 || rf100 <= 0) continue;
                double energyMod = 100 + 15 + factor - CoolantManager.WATER_FACTOR;
                for (String fuelName : fuels) {
                    Fluid fuel = FluidRegistry.getFluid(fuelName);
                    if (fuel == null || fuel == coolant) continue;
                    int rf = MagmaticManager.getFuelEnergy(new FluidStack(fuel, 1000));
                    if (rf <= 0) continue;
                    double rfEff = rf * energyMod / 100D;
                    int coolantAmount = TeDynamoFuels.fluidAmountForBatchedEnergy(rfEff, rf100);
                    if (coolantAmount <= 0) continue;
                    batches.add(new CloudworksRecipeBatch()
                            .fluid(new FluidStack(fuel, 1000), "fluid_input")
                            .fluid(new FluidStack(coolant, coolantAmount), "fluid_input_coolant")
                            .energy(rfEff * rfToJoules));
                }
            }
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            for (CloudworksRecipeBatch batch : batches) {
                try {
                    result.add(TeDynamoFuels.streamSideInput(batch.specification("te_dynamo_magmatic_coolant_" + result.size(), 1)));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable pair rejects only itself.
                }
            }
            return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                    Collections.unmodifiableList(result), new MachineSlotLayout()
                            .add("fluid_input_coolant", QIOResourceCodecs.FLUID_STACK_ID,
                                    MachineSlotLayout.PortRole.INPUT, CloudworksTeDynamos.DYNAMO_FLUID_PORT_CAPACITY));
        });
    }

    /**
     * Closed-loop cooling (TileDynamoCompression:312-316 install, :121/:139
     * effects): the coolant factor still applies to the fuel energy
     * (×(100 + factor − WATER_FACTOR)/100) but the coolant stream stops
     * draining after the initial fill. Per-operation translation: a flat
     * 100 mB coolant prime per burned bucket, the native fluidAmount.
     */
    static ModeModule dynamoCompressionCoolantMechanism(double rfToJoules) {
        return new ModeModule(context -> {
            List<CloudworksRecipeBatch> batches = new ArrayList<>();
            Set<String> fuels = CompressionManager.getFuels();
            Set<String> coolants = CoolantManager.getCoolantFluids();
            if (fuels != null && coolants != null) for (String coolantName : coolants) {
                Fluid coolant = FluidRegistry.getFluid(coolantName);
                if (coolant == null) continue;
                int factor = CoolantManager.getCoolantFactor(coolant);
                if (factor <= 0) continue;
                double energyMod = 100 + factor - CoolantManager.WATER_FACTOR;
                for (String fuelName : fuels) {
                    Fluid fuel = FluidRegistry.getFluid(fuelName);
                    if (fuel == null || fuel == coolant) continue;
                    int rf = CompressionManager.getFuelEnergy(new FluidStack(fuel, 1000));
                    if (rf <= 0) continue;
                    batches.add(new CloudworksRecipeBatch()
                            .fluid(new FluidStack(fuel, 1000), "fluid_input")
                            .fluid(new FluidStack(coolant, 100), "fluid_input_coolant")
                            .energy(rf * energyMod / 100D * rfToJoules));
                }
            }
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            for (CloudworksRecipeBatch batch : batches) {
                try {
                    result.add(batch.specification("te_dynamo_compression_coolant_" + result.size(), 1));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable pair rejects only itself.
                }
            }
            return RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                    Collections.unmodifiableList(result), context.basePorts());
        });
    }

    private static MachineSlotLayout fluidInputPort(String id) {
        return new MachineSlotLayout()
                .add(id, QIOResourceCodecs.FLUID_STACK_ID,
                        MachineSlotLayout.PortRole.INPUT,
                        4000);
    }

    /**
     * One REPLACE mechanism around one alternate manager list. The transformer
     * ignores the base recipes and freezes the alternate list directly; REPLACE
     * joins the shared {@code cloudworks:primary} exclusive group, matching the
     * native "one mode augment per machine" rule.
     */
    private static <R> ModeModule modeMechanism(String idPrefix, Supplier<R[]> list,
                                                Function<R, CloudworksRecipeBatch> describe, ToIntFunction<R> energy,
                                                MachineSlotLayout ports, double rfToJoules) {
        return new ModeModule(context -> RecipeContribution.perNode(1, RecipeContribution.RecipeMode.REPLACE,
                freezeSet(idPrefix, list, describe, energy, context.machine(), rfToJoules), ports));
    }

    /**
     * Freezes one alternate manager list into specs. Durations derive from the
     * machine registration's tier energy rate: {@code ceil(rf / (J-rate / rfToJ))}.
     * One unfreezable recipe rejects only itself.
     */
    static <R> List<CloudworksRecipeSpec> freezeSet(String idPrefix, Supplier<R[]> list,
                                                    Function<R, CloudworksRecipeBatch> describe, ToIntFunction<R> energy,
                                                    CloudworksMachineRegistration machine, double rfToJoules) {
        R[] recipes = list.get();
        if (recipes == null) return Collections.emptyList();
        List<CloudworksRecipeSpec> result = new ArrayList<>();
        for (R recipe : recipes) {
            if (recipe == null) continue;
            try {
                result.add(describe.apply(recipe).specification(idPrefix + "_" + result.size(),
                        durationTicks(energy.applyAsInt(recipe), machine, rfToJoules)));
            } catch (IllegalArgumentException unsupported) {
                // One unfreezable ingredient rejects a single recipe, never the set.
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Tier-derived duration: the registration carries the tier's J/t rate.
     */
    static int durationTicks(int energyRf, CloudworksMachineRegistration machine, double rfToJoules) {
        double perTick = machine.profile().energyPerTick();
        if (!(perTick > 0D)) return 1;
        return (int) Math.max(1L, Math.ceil(energyRf * rfToJoules / perTick));
    }

    private static int safeFluidAmount(double amount) {
        if (!Double.isFinite(amount) || amount <= 0D || amount > Integer.MAX_VALUE) return 0;
        return (int) Math.ceil(amount);
    }

    private static int safeInt(IntSupplier supplier) {
        try {
            return supplier.getAsInt();
        } catch (RuntimeException | LinkageError unavailable) {
            return 0;
        }
    }

    // -----------------------------------------------------------------------------------------
    // Completion strategy
    // -----------------------------------------------------------------------------------------

    /**
     * The secondary-null behavior: a rolled secondary that cannot land is
     * discarded instead of blocking the machine, and a partial fit commits
     * partially. Primaries always pass through untouched (the normal capacity
     * check still applies). Centrifuge exception per TE source (TileCentrifuge
     * canStart:131): only the byproduct fluid is exempt; chance items still
     * block there. The strategy is worker-safe pure data code.
     */
    static final class SecondaryNullStrategy implements ICustomMachineModule<SecondaryNullStrategy> {

        @Override
        public boolean discardsChanceOnOverflow() {
            return true;
        }

        @Override
        public List<MachineOutput> onPostWork(IMachineModule<SecondaryNullStrategy> module,
                                              PostWorkContext context, List<MachineOutput> planned) {
            boolean centrifuge = context.recipeId().startsWith("te_centrifuge");
            List<MachineOutput> kept = new ArrayList<>();
            Map<ResourceLocation, Long> used = new HashMap<>();
            for (MachineOutput output : planned)
                if (!output.chanceOutput()
                        || centrifuge && !QIOResourceCodecs.FLUID_STACK_ID.equals(output.descriptor().getCodecId()))
                    used.merge(output.descriptor().getCodecId(), output.amount(), Math::addExact);
            for (MachineOutput output : planned) {
                if (!output.chanceOutput()) {
                    kept.add(output);
                    continue;
                }
                if (centrifuge && !QIOResourceCodecs.FLUID_STACK_ID.equals(output.descriptor().getCodecId())) {
                    // On the centrifuge the null only exempts the byproduct fluid.
                    kept.add(output);
                    continue;
                }
                ResourceLocation codec = output.descriptor().getCodecId();
                long room = Math.max(0L, context.room(output.descriptor()) - used.getOrDefault(codec, 0L));
                if (room <= 0) continue;
                long amount = Math.min(output.amount(), room);
                kept.add(output.amount(amount));
                used.merge(codec, amount, Math::addExact);
            }
            return kept;
        }
    }

    static final class ModeModule implements ICustomMachineModule<ModeModule> {
        private final Function<RecipeFreezeContext, RecipeContribution> recipes;

        ModeModule(Function<RecipeFreezeContext, RecipeContribution> recipes) {
            this.recipes = recipes;
        }

        @Override
        public RecipeContribution onRecipes(IMachineModule<ModeModule> module, RecipeFreezeContext context) {
            return recipes.apply(context);
        }
    }

    static final class NoopModule implements ICustomMachineModule<NoopModule> {
    }

    static final class SpeedCoil implements ICustomMachineModule<SpeedCoil> {
        private final double energyPenalty;

        SpeedCoil(double energyPenalty) {
            this.energyPenalty = energyPenalty;
        }

        @Override
        public AxisContribution onUpgradeAxis(IMachineModule<SpeedCoil> module, UpgradeAxis axis) {
            int count = module.getEffectiveInstalledCount();
            return axis == UpgradeAxis.SPEED
                    ? AxisContribution.of(
                            (1D + energyPenalty * count) / (1D + count), 1D + count, 1D) : null;
        }
    }

    static final class SecondarySieve implements ICustomMachineModule<SecondarySieve> {
        @Override
        public WorkContribution onPreWork(IMachineModule<SecondarySieve> module, PreWorkContext context) {
            int count = module.getEffectiveInstalledCount();
            return WorkContribution.builder().secondaryChanceMultiplier(100D / Math.max(5D, 100D - 15D * count))
                    .energyPerTickMultiplier(1D + .1D * count).build();
        }
    }

    /**
     * Dynamo efficiency (TileDynamoBase:640-644): each piece adds +15% to the
     * fuel energy mod. The contribution re-scales every frozen fuel contract's
     * energy per operation by the effective per-machine piece count — REPLACE,
     * never APPEND, because duplicate fuel entries would double-burn in the
     * fuel settlement path. Count-dependent variants are cached per installed
     * map by the node-mechanism variant cache.
     */
    static final class DynamoEfficiency implements ICustomMachineModule<DynamoEfficiency> {
        @Override
        public RecipeContribution onRecipes(IMachineModule<DynamoEfficiency> module,
                                            RecipeFreezeContext context) {
            int count = module.getEffectiveInstalledCount();
            double factor = 1D + .15D * count;
            List<CloudworksRecipeSpec> scaled = new ArrayList<>();
            for (CloudworksRecipeSpec spec : context.baseRecipes())
                scaled.add(spec.toBuilder(spec.id() + "_eff" + count)
                        .energyPerOperation(spec.energyPerOperation() * factor).build());
            return RecipeContribution.perMachine(1, RecipeContribution.RecipeMode.REPLACE, scaled,
                    new MachineSlotLayout());
        }
    }

    // -----------------------------------------------------------------------------------------
    // Describe helpers
    // -----------------------------------------------------------------------------------------

    private static CloudworksRecipeBatch describePyrolysis(FurnaceManager.FurnaceRecipe recipe,
                                                           Supplier<Fluid> creosote) {
        CloudworksRecipeBatch batch = itemToItem(recipe.getInput(), recipe.getOutput());
        int creosoteAmount = recipe.getCreosote();
        if (creosoteAmount > 0) {
            Fluid fluid = creosote.get();
            if (fluid != null) batch.output(new FluidStack(fluid, creosoteAmount), "fluid_output");
        }
        return batch;
    }

    static CloudworksRecipeBatch itemToItem(ItemStack input, ItemStack output) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        if (!input.isEmpty() && !output.isEmpty()) {
            batch.item(input);
            batch.output(output);
            return batch;
        }
        return batch;
    }
}
