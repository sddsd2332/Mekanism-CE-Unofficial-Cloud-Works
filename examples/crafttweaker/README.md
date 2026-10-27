# CloudWorks CraftTweaker Example

Builtin pumps use environment recipes: Nether lava, ocean water, and FILTER heavy water. `Pumps.add/biome/remove/removeAll` configure other mod biome fluids; see `cloudworks_pumps*.zs` and the [API guide](../../docs/CLOUDWORKS_PUMP_ENVIRONMENT_RULES.zh-CN.md). The builtin fusion controller has a per-node injection setting preserved in blueprints; CRT `Multiblocks.fusion` retains its explicit registration-time injection parameter.

Line pools now use CloudWorks modules; effective node modules take over each axis independently. The complete scripts include `Machines.setLineUpgradePolicy(machine, speedUnits, energyUnits)`: zero refuses an axis. Mek speed/energy items are node CUSTOM modules, so rebinding a line item to the same native item is rejected as ambiguous.

All ordinary processing nodes share partial-batch concurrency: declared parallelism is an upper bound, and different recipes may progress independently. The scripts demonstrate `Slots.recipeFilteredInput(port, true/false)`. Ordinary input ports filter new resources by frozen recipe inputs by default; disable it explicitly for generic buffers or module-only consumables. Existing buffers are preserved. See the [input and parallelism contract](../../docs/CLOUDWORKS_MACHINE_INPUT_AND_PARALLELISM_20261025.zh-CN.md) for Java APIs and persistence rules.

Chinese instructions and the translated script are available in [README.zh-CN.md](README.zh-CN.md).

The October 25 expansion adds water/heavy-water pumps, fusion control, power-driven SPS, prepaid biofuel, manual ore rules and Mek/IC2 node modules. `Recipes` now exposes `operationsPerCycle`, `powerDriven` and `secondaryFuelInputs`. See the [contract and API guide](../../docs/CLOUDWORKS_MEK_IC2_EXPANSION_20261025.zh-CN.md). Players configure ore rules in the node editor; blueprints preserve those rules.

`cloudworks_complete.zs` is a complete CRT API example for this project. It exercises
the public ZenScript registration, resource, recipe, generator, multiblock, thermal,
loot-injection and query APIs, and lists all Java-registered Mekanism,
MoreMachine and MultiblockMachine families. The script is not loaded by the mod or
Gradle automatically; copy it to the instance `scripts` directory only after
replacing the tagged `minecraft:stick` demo identities with dedicated integration
items.

Mekanism, MoreMachine and MultiblockMachine machines are registered automatically
by CloudWorks when their dependencies are present. Their recipes are frozen from
the native recipe tables, so do not duplicate those declarations in CRT. The
`Machines.dump()` action writes the final machine/profile/adapter listing to the
CraftTweaker log.

The Fusion, Turbine and Fission examples require `mekanismgenerators` and are
commented out in the script. Uncomment them only when that module is installed.
All writes are startup actions; after editing the script, restart Minecraft and
the server. ZenUtils reload does not rebuild CloudWorks' frozen registry.

`mods.cloudworks.LootDrops` layers independent probability drops over the frozen
entity/chest loot pipeline. Entity rules roll per matching simulated kill; axis
rules roll per simulated kill and per chest-table event on that axis. Metadata is
supported, while NBT item templates are intentionally rejected.
