# CloudWorks CraftTweaker Example

Chinese instructions and the translated script are available in [README.zh-CN.md](README.zh-CN.md).

`cloudworks_complete.zs` is a complete CRT API example for this project. It exercises
the public ZenScript registration, resource, recipe, generator, multiblock, thermal
and query APIs, and lists all Java-registered Mekanism,
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
