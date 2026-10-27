// CloudWorks CRT complete integration example for Mekanism CE 1.12.2.
// This file is an example only; the project does not load scripts from examples/.
//
// IMPORTANT: The minecraft:stick + NBT stacks below are isolated demo identities.
// Replace every demo stack with a dedicated item from your integration pack before
// using this script on a real server. Registering a vanilla identity grants its
// CloudWorks machine behavior to every matching item on the server.
//
// The Mekanism, MoreMachine and MultiblockMachine default adapters are registered
// by Java and already read their native recipe tables. Do not re-register those
// same machine identities or copy their native recipes into CloudWorks.

import mods.cloudworks.Machines;
import mods.cloudworks.Slots;
import mods.cloudworks.Resources;
import mods.cloudworks.Recipes;
import mods.cloudworks.Generators;
import mods.cloudworks.Multiblocks;
import mods.cloudworks.Thermal;
import mods.cloudworks.Upgrades;
import mods.cloudworks.LootDrops;
import mods.cloudworks.Pumps;
import mods.cloudworks.Storage;
import crafttweaker.player.IPlayer;
import crafttweaker.item.IItemStack;

// Call explicitly to replace the shared defaults; see cloudworks_pumps.zs for a standalone script.
function cloudworksPumpRulesExample() {
    Pumps.remove("cloudworks:pump_water");
    Pumps.biome("cloudworks_example:ocean_water", "minecraft:ocean", <liquid:water> * 1000, 20, 100, "native_water", "without");
    Pumps.remove("cloudworks:pump_lava");
    Pumps.add("cloudworks_example:nether_lava", "cloudworks:nether_heat_source", <liquid:lava> * 1000, 20, 100, "fixed", "any");
}

// Runtime reward helpers. These functions are not called by loading this example.
// The pack owns ingredient consumption and exactly-once reward delivery.
// terminalItemUploadsEnabled only gates terminals, not these trusted deposits.
function rewardCloudMachines(player as IPlayer, supportedMachine as IItemStack) {
    val request = Storage.addMachine(player, supportedMachine, 4);
    // Poll request.done from a later server callback before inspecting accepted/error.
    // Do not block the server thread or resend a request while it is still pending.
}

function rewardCloudSpeed(player as IPlayer) {
    val request = Storage.addUpgradeType(player, "speed", 8);
    // Custom ids: Storage.addUpgradeType(player, "addonmod:module", 1);
    // Item binding: Storage.addUpgrade(player, Upgrades.getItem("energy"), 8);
    // Team cloud: Storage.addMachineTo(player, ownerUuid, supportedMachine, 4);
}

// External MMCE-style transfer: the pack must debit/credit its own IO inventory.
function mmceCloudTransfer(player as IPlayer, machine as IItemStack) {
    val upload = Storage.uploadMachine(player, machine, 1);
    val download = Storage.downloadMachine(player, machine, 1);
    // Poll upload.done/download.done later. For a successful download, move
    // download.machines into the MMCE output inventory; CloudWorks never does it.
}
// Normal output() and chanceOutput() use the owner's cloud output multiplier.
// Declare container returns with unscaledOutput(resource[, port]).
// Conserved conversion recipes call unscaledOutputs() on the builder.

// Line pools default to cloudworks speed_module/energy_module. Mek upgrades are node CUSTOM modules.
// Optional item replacement does not change axis curves. Final bindings must not collide with CUSTOM items.
// Upload and download use exactly the same item, metadata and NBT identity.
// Existing numeric pool balances remain, and are withdrawn as the current item.
// Upgrades.setItem("speed", <minecraft:paper>.withTag({cloudUpgrade: "speed"}));
// Upgrades.setItem("energy", <minecraft:paper>.withTag({cloudUpgrade: "energy"}));
// useNativeItem("speed") conflicts when the same Mek item is already bound to its node module.
// Upgrades.getItem("energy");

// -----------------------------------------------------------------------------
// Probability drops layered on the frozen entity/chest loot results
// -----------------------------------------------------------------------------
// These are startup declarations. They do not rewrite loot JSON and never merge
// equal item stacks; each entity/axis declaration keeps its own probability.
// LootDrops.entity("minecraft:zombie", <minecraft:diamond>, 0.05);
// LootDrops.entityRange("minecraft:skeleton", <minecraft:bone>, 1, 2, 0.25);
// LootDrops.entityMeta("minecraft:zombie", <minecraft:coal>, 1, 1, 3, 0.10);
//
// Axis declarations roll per simulated entity kill and per declared chest-table
// event. A multi-table structure gets one independent axis roll for each table.
// LootDrops.axis("cloudworks:nether_fortress", <minecraft:emerald>, 0.01);
// LootDrops.axisRange("cloudworks:nether_fortress", <minecraft:gold_ingot>, 1, 2, 0.05);
// LootDrops.axisMeta("cloudworks:nether_fortress", <minecraft:coal>, 1, 1, 2, 0.05);

// -----------------------------------------------------------------------------
// Built-in coverage (automatic; this is a coverage index, not duplicate calls)
// -----------------------------------------------------------------------------
// Mek single machines: Crusher, Enrichment Chamber, Combiner, Energized Smelter,
// Stamping, Rolling, Brushed, Turning, Alloy, Metallurgic Infuser, Precision
// Sawmill, Purification Chamber, Osmium Compressor, Chemical Injection Chamber,
// Chemical Oxidizer, Ambient Accumulator, Ambient Accumulator Energy, Thermal
// Evaporation Controller, Resistive Heater, Fuelwood Heater, Pressurized Reaction
// Chamber, Chemical Infuser, Chemical Crystallizer, Electrolytic Separator,
// Rotary Condensentrator, Chemical Dissolution Chamber, Chemical Washer, Isotopic
// Centrifuge, Solar Neutron Activator, Nutritional Liquifier, Antiprotonic
// Nucleosynthesizer, Organic Farm, Cell Extractor, Cell Separator, Recycler and
// Radioactive Waste Barrel.
//
// Mek factories: all four tiers (Basic, Advanced, Elite, Ultimate) of all 19
// registered RecipeType modes: Smelting, Enriching, Crushing, Infusing, Purifying,
// Injecting, Combining, Alloy, Compressing, Sawing, Extractor, Separator, Recycler,
// Stamping, Rolling, Brushed, Turning, PRC and Nucleosynthesizer.
//
// Mek Generators (when installed): Wind, Solar, Advanced Solar, Heat and Gas.
// MoreMachine (when installed): four tiers each of Chemical Infuser, Chemical
// Washer, Electrolytic Separator, Isotopic Centrifuge, Ambient Accumulator Energy,
// Solar Neutron Activator, Rotary Condensentrator, Void Mineral Generator,
// Radioactive Waste Barrel, Gas Generator, Wind Generator, Solar Generator,
// Advanced Solar Generator, Chemical Crystallizer, Chemical Oxidizer, Nutritional
// Liquifier, Chemical Dissolution Chamber and Organic Farm; plus Item, Gas and
// Fluid Replicators. MoreMachine's 18 tier families plus 3 replicators produce
// 75 default machine identities.
// MultiblockMachine (when installed): Large Chemical Infuser, Large Chemical Washer,
// Large Electrolytic Separator, Large Solar Neutron Activator, Large Wind Generator
// and Large Gas Generator.
// Four tiered electric pumps bring MoreMachine to 79 identities (19 tier families plus 3 replicators).
// Mek also includes the pump, power-driven SPS and manual ore converter; Generators includes bio and fusion.
// The fixed defaults total 207 identities when every optional dependency is
// present. Runtime recipe contents are read from the installed mods; this script
// intentionally does not freeze a second, potentially stale recipe list.

// -----------------------------------------------------------------------------
// Resource, port, profile, recipe, template, chance and sunlight APIs
// -----------------------------------------------------------------------------
val demoProcessor = <minecraft:stick>.withTag({cloudworksExample: "processor"});
val processorSlots = Slots.create()
    .input("item_input", "mekanism:item_stack", 64)
    .recipeFilteredInput("item_input", true) // default: accept declared recipe inputs
    .input("catalyst_input", "mekanism:item_stack", 64)
    .template("item_template", "mekanism:item_stack", 64)
    .output("item_output", "mekanism:item_stack", 64)
    .add("single_link_input", "mekanism:item_stack", "INPUT", 64, false)
    .recipeFilteredInput("single_link_input", false); // generic consumables; independent of the link limit

Machines.register(demoProcessor, 1, 10.0, 64000.0, 40, processorSlots);
Machines.setProfile(demoProcessor, 2, 12.0, 96000.0, 40);
// 2 is a per-machine upper bound. Smaller batches and different recipes can run independently.
// Additional opt-in recipe contracts:
// Recipes.create("pack:cycle", 20).operationsPerCycle(4)...
// Recipes.create("pack:powered", 1).input(oneExactInput).unscaledOutput(oneUnitOutput).powerDriven(1000000.0, 1000)...
// Recipes.create("pack:secondary", 20).secondaryFuelInputs(true, false)... // marks existing gas inputs
// Recipes.create("pack:fuel", 1).input(oneExactFuel).prepaidFuel(canonicalFuel, 200, 5.0)... // joules per credit unit
// Builtin/CRT SPS share power-driven processing without output multipliers; ticks is retained for API compatibility.
// Configure ore targets in the node editor; no physical template port or startup script overriding player rules.
Machines.setLineUpgradePolicy(demoProcessor, 8, 8); // line units per physical machine; zero refuses an axis

// ingredient() snapshots the current ore-dictionary matches at startup.
Recipes.create("cloudworks_example:ore_processor", 40)
    .input(Resources.ingredient(<ore:ingotIron>, 1), "item_input")
    .input(Resources.item(<minecraft:coal>).withAmount(1), "catalyst_input")
    .template(Resources.item(<minecraft:iron_ingot>), "item_template")
    .output(Resources.item(<minecraft:gold_ingot>), "item_output")
    .chanceOutput(Resources.item(<minecraft:diamond>), 0.05)
    .energy(5.0)
    .sunlight()
    .register(demoProcessor);

// descriptor() consumes QIOResourceDescriptor.write() data. The payload shape is
// codec-specific; this is the native item-stack descriptor for one iron ingot.
val serializedIron = Resources.descriptor({
    codec: "mekanism:item_stack",
    family: "item",
    codecVersion: 1,
    storageUnitsPerUnit: 1000L,
    payload: {id: "minecraft:iron_ingot", Count: 1 as byte, Damage: 0 as short}
}, 1);
val serializedIronCodec = serializedIron.codec;
val serializedIronAmount = serializedIron.amount;

Recipes.create("cloudworks_example:serialized_descriptor", 20)
    .input(serializedIron)
    .output(Resources.item(<minecraft:iron_nugget>))
    .register(demoProcessor);

// The no-port overloads are useful when exactly one compatible port can accept
// the resource (or when a recipe intentionally targets the codec-wide buffer).
Recipes.create("cloudworks_example:default_ports", 20)
    .input(Resources.item(<minecraft:coal>))
    .output(Resources.item(<minecraft:charcoal>))
    .register(demoProcessor);

val demoChemicalProcessor = <minecraft:stick>.withTag({cloudworksExample: "chemical_processor"});
Machines.register(demoChemicalProcessor, 1, 20.0, 128000.0, 100,
    Slots.create()
        .input("fluid_input", "mekanism:fluid_stack", 10000)
        .input("gas_input", "mekanism:gas_stack", 10000)
        .input("infuse_input", "cloudworks:infuse", 1000)
        .output("gas_output", "mekanism:gas_stack", 10000));

Recipes.create("cloudworks_example:fluid_gas_infusion", 100)
    .input(Resources.fluid(<liquid:water>), "fluid_input")
    .input(Resources.gas(<gas:oxygen>), "gas_input")
    .input(Resources.infuse("CARBON", 10), "infuse_input")
    .output(Resources.gas(<gas:hydrogen>), "gas_output")
    .register(demoChemicalProcessor);

// -----------------------------------------------------------------------------
// Derived profiles, exact-NBT aliases and registry queries
// -----------------------------------------------------------------------------
val demoFastProcessor = <minecraft:stick>.withTag({cloudworksExample: "fast_processor"});
Machines.registerFrom(demoFastProcessor, demoProcessor, 3, 18.0, 144000.0, 30);

// Alias registration runs after recipe/template declarations are finalized.
val demoProcessorAlias = <minecraft:stick>.withTag({cloudworksExample: "processor_alias"});
Machines.registerAlias(demoProcessorAlias, demoProcessor);

// These immediate queries are intended for machines already registered by the
// built-in Java integrations. Script declarations above are deferred until startup
// finalization, so use Machines.dump() below to inspect their final applied state.
val nativeCrusher = <mekanism:machineblock:3>;
val crusherRegistered = Machines.isRegistered(nativeCrusher);
val crusherPorts = Machines.getPorts(nativeCrusher);
val crusherProfile = Machines.getProfile(nativeCrusher);
val crusherParallel = Machines.getParallel(nativeCrusher);
val crusherEnergyPerTick = Machines.getEnergyPerTick(nativeCrusher);
val crusherEnergyCapacity = Machines.getEnergyCapacity(nativeCrusher);
val crusherTicks = Machines.getTicks(nativeCrusher);
val crusherAliasTarget = Machines.getAliasTarget(nativeCrusher);

// -----------------------------------------------------------------------------
// Fixed and resource-fueled generators
// -----------------------------------------------------------------------------
val demoConstantGenerator = <minecraft:stick>.withTag({cloudworksExample: "constant_generator"});
Machines.register(demoConstantGenerator, 1, 0.0, 100000.0, 1, Slots.create());
Generators.constant(demoConstantGenerator, 250.0);

val demoFuelGenerator = <minecraft:stick>.withTag({cloudworksExample: "fuel_generator"});
Machines.register(demoFuelGenerator, 1, 0.0, 100000.0, 1,
    Slots.create()
        .input("item_input", "mekanism:item_stack", 64)
        .output("item_output", "mekanism:item_stack", 64));
Generators.fuel(demoFuelGenerator, "cloudworks_example:coal_generator",
    [Resources.item(<minecraft:coal>)],
    [Resources.item(<minecraft:charcoal>)],
    5000.0);

// -----------------------------------------------------------------------------
// CRT-only multiblock contracts
// -----------------------------------------------------------------------------
// These are the seven supported type names returned by Multiblocks.getTypes():
// fusion, turbine, evaporation, boiler, fission_water, fission_sodium and sps.
val supportedMultiblockTypes = Multiblocks.getTypes();

val demoEvaporation = <minecraft:stick>.withTag({cloudworksExample: "evaporation"});
Multiblocks.evaporation(demoEvaporation, 1, 100, 100000, 100000);
Thermal.solarPanels(demoEvaporation, 4);
Thermal.capacitor(demoEvaporation, 50000.0, 10.0, 100.0);

val demoBoiler = <minecraft:stick>.withTag({cloudworksExample: "boiler"});
Multiblocks.boiler(demoBoiler, 100, 20, 100000, 100000);
Thermal.capacitor(demoBoiler, 50000.0, 10.0, 100.0);

val demoSps = <minecraft:stick>.withTag({cloudworksExample: "sps"});
Multiblocks.sps(demoSps, 100, 1000000000.0);

// The following contracts require mekanismgenerators. Leave them commented when
// that module is not installed. Use distinct stable identities for each controller.
// val demoWaterFission = <minecraft:stick>.withTag({cloudworksExample: "fission_water"});
// Multiblocks.fission(demoWaterFission, false, 20, 100000, 100000);
// Thermal.capacitor(demoWaterFission, 100000.0, 10.0, 100.0);
//
// val demoSodiumFission = <minecraft:stick>.withTag({cloudworksExample: "fission_sodium"});
// Multiblocks.fission(demoSodiumFission, true, 20, 100000, 100000);
// Thermal.capacitor(demoSodiumFission, 100000.0, 10.0, 100.0);
//
// val demoTurbine = <minecraft:stick>.withTag({cloudworksExample: "turbine"});
// Multiblocks.turbine(demoTurbine, 1000, 28, 7, 1, 64000, 64000, 16000000.0);
//
// val demoFusion = <minecraft:stick>.withTag({cloudworksExample: "fusion"});
// Multiblocks.fusion(demoFusion, 2, false, 10000, 1000000, 1000000000.0);
// Thermal.capacitor(demoFusion, 100000.0, 10.0, 100.0);

// -----------------------------------------------------------------------------
// Thermal machines and safe maintenance examples
// -----------------------------------------------------------------------------
val demoResistiveHeater = <minecraft:stick>.withTag({cloudworksExample: "resistive_heater"});
Thermal.resistive(demoResistiveHeater, 100.0, 40000.0);

val demoFuelwoodHeater = <minecraft:stick>.withTag({cloudworksExample: "fuelwood_heater"});
Thermal.fuelwood(demoFuelwoodHeater, 64);

// Recipe deletion is deliberately opt-in; uncomment only when replacing a prior
// script declaration for this same exact machine identity.
// Recipes.remove(demoProcessor, "cloudworks_example:ore_processor");
// Recipes.removeAll(demoProcessor);

// Delayed final-phase audit includes Java built-ins, optional integrations and the
// custom machines declared by this script.
Machines.dump();
