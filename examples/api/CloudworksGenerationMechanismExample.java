package examplemod;

import java.util.Collections;
import java.util.function.Function;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.*;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/** Examples only. No automatic registration; callers supply real items, codecs and balance. */
public final class CloudworksGenerationMechanismExample {
    private CloudworksGenerationMechanismExample() {}

    public static void convertExistingGenerator(ItemStack machine, ItemStack moduleItem,
            QIOResourceDescriptor output, double unitsPerEnergy, int minimumHeight) {
        MachineSlotLayout ports = new MachineSlotLayout().add("example_resource_output",
                output.getCodecId(), MachineSlotLayout.PortRole.OUTPUT, 64000);
        register(machine, moduleItem, "resource_converter", context ->
                RecipeContribution.perMachine(1, RecipeContribution.RecipeMode.REPLACE, context.baseRecipes(), ports)
                        .withGenerationTransformer((original, node, operations, environment) ->
                                environment.simulatedHeight() < minimumHeight ? original
                                        : original.withElectricityFraction(0).withResourceOutput(output, unitsPerEnergy,
                                                "example_resource_output")));
    }

    public static void addIndependentGeneratorOutput(ItemStack machine, ItemStack moduleItem,
            QIOResourceDescriptor output, double unitsPerEnergy) {
        MachineSlotLayout ports = new MachineSlotLayout().add("example_parallel_output",
                output.getCodecId(), MachineSlotLayout.PortRole.OUTPUT, 64000);
        register(machine, moduleItem, "night_resource_branch", context ->
                RecipeContribution.perMachine(1, RecipeContribution.RecipeMode.PARALLEL, Collections.emptyList(), ports)
                        .withGenerationTransformer((original, node, operations, environment) ->
                                original.withElectricityFraction(0).withResourceOutput(output, unitsPerEnergy,
                                        "example_parallel_output").during(13000, 23000)));
    }

    public static void replaceWithThermalContract(ItemStack machine, ItemStack moduleItem,
            CloudworksThermalPlan thermal, MachineExecutionProfile profile,
            Function<RecipeFreezeContext, java.util.List<CloudworksRecipeSpec>> recipes) {
        register(machine, moduleItem, "thermal_converter", context ->
                RecipeContribution.perMachine(1, RecipeContribution.RecipeMode.REPLACE, recipes.apply(context),
                        new MachineSlotLayout()).withoutGenerator().withThermal(thermal).withProfile(profile));
    }

    private static void register(ItemStack machine, ItemStack item, String path,
            Function<RecipeFreezeContext, RecipeContribution> recipes) {
        MachineModuleType<GenerationModule> type = MachineModuleType.custom(new ResourceLocation("examplemod", path),
                1, "upgrade.examplemod." + path, new GenerationModule(recipes)).withItem(item);
        CloudworksAPI.registerMachineModule(type);
        CloudworksAPI.acceptMachineUpgrade(machine, type, 1);
    }

    static final class GenerationModule implements ICustomMachineModule<GenerationModule> {
        private final Function<RecipeFreezeContext, RecipeContribution> recipes;
        GenerationModule(Function<RecipeFreezeContext, RecipeContribution> recipes) { this.recipes = recipes; }
        @Override public RecipeContribution onRecipes(IMachineModule<GenerationModule> module, RecipeFreezeContext context) {
            return recipes.apply(context);
        }
    }
}
