package mekanism.cloudworks.common.integration;

import java.util.Collections;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.cloudworks.common.recipe.CloudworksPumpRecipeAdapter;
import mekanism.common.Upgrade;
import mekanism.common.block.states.BlockStateMachine.MachineType;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

public final class CloudworksPumpIntegration {
    public static final ResourceLocation FILTER = new ResourceLocation("cloudworks", "mek_filter");
    private CloudworksPumpIntegration() {}
    public static void register(ItemStack machine, int processes, MachineIdentityPolicy identity) {
        CloudworksAPI.registerMachine(machine, new MachineExecutionProfile(1,
                MachineType.ELECTRIC_PUMP.getUsage() * processes,
                MachineType.ELECTRIC_PUMP.getStorage() * processes, 20),
                new MachineSlotLayout().add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID,
                        MachineSlotLayout.PortRole.OUTPUT, 10000 * processes),
                new CloudworksPumpRecipeAdapter(processes), identity);
        CloudworksAPI.acceptMachineUpgrade(machine, filter(), 1);
    }
    public static MachineModuleType<Filter> filter() {
        MachineModuleType<Filter> type = MachineModuleType.builtInModule(FILTER, 1, "upgrade.filter", new Filter());
        try {
            ItemStack item = Upgrade.FILTER.getStack();
            return item.isEmpty() || item.getItem().getRegistryName() == null ? type : type.withItem(item);
        } catch (LinkageError unavailable) { return type; }
    }
    public static final class Filter implements ICustomMachineModule<Filter> {
        @Override public RecipeContribution onRecipes(IMachineModule<Filter> module, RecipeFreezeContext context) {
            int processes = mekanism.cloudworks.common.registry.CloudworksMachineAdapters.getAll(context.machine().machine()).stream()
                    .filter(adapter -> adapter instanceof CloudworksPumpRecipeAdapter)
                    .mapToInt(adapter -> ((CloudworksPumpRecipeAdapter) adapter).processes()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("FILTER has no pump contract"));
            return RecipeContribution.of(RecipeContribution.Installation.PER_MACHINE, 1,
                    RecipeContribution.RecipeMode.REPLACE,
                    mekanism.cloudworks.common.recipe.CloudworksPumpRecipes.recipes(processes, true), new MachineSlotLayout());
        }
    }
}
