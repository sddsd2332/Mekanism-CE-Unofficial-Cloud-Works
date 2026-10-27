package examplemod;

import java.util.ArrayList;
import java.util.List;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.*;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/** Register from the addon's common lifecycle before CloudWorks loadComplete. Not installed automatically. */
public final class CloudworksFluidMechanismExample {
    private CloudworksFluidMechanismExample() {}

    public static void register(ItemStack existingMachine, ItemStack moduleItem,
            QIOResourceDescriptor fluidInput, QIOResourceDescriptor fluidOutput) {
        MachineModuleType<FluidModule> module = MachineModuleType.custom(
                new ResourceLocation("examplemod", "fluid_conversion"), 1,
                "upgrade.examplemod.fluid_conversion", new FluidModule(fluidInput, fluidOutput)).withItem(moduleItem);
        CloudworksAPI.registerMachineModule(module);
        CloudworksAPI.acceptMachineUpgrade(existingMachine, module, 1);
    }

    static final class FluidModule implements ICustomMachineModule<FluidModule> {
        private final QIOResourceDescriptor input, output;
        FluidModule(QIOResourceDescriptor input, QIOResourceDescriptor output) { this.input = input; this.output = output; }
        @Override public RecipeContribution onRecipes(IMachineModule<FluidModule> module, RecipeFreezeContext context) {
            MachineSlotLayout extra = new MachineSlotLayout()
                .add("example_fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 4000)
                .add("example_fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 4000);
            List<CloudworksRecipeSpec> derived = new ArrayList<>();
            for (CloudworksRecipeSpec original : context.baseRecipes())
                derived.add(original.toBuilder("examplemod:fluid_conversion_" + derived.size())
                        .input(input, 100, "example_fluid_input")
                        .output(output, 100, "example_fluid_output").build());
            return RecipeContribution.perMachine(1, RecipeContribution.RecipeMode.REPLACE, derived, extra);
        }
    }
}
