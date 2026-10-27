package mekanism.cloudworks.common.integration.multiblockmachine;

import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.multiblockmachine.common.MultiblockMachineUpgrades;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/** Native processes * (1 + installed), including generator throughput and storage. */
public final class CloudworksThreadModule implements ICustomMachineModule<CloudworksThreadModule> {
    public static final ResourceLocation ID = new ResourceLocation("cloudworks", "mek_thread");
    public static MachineModuleType<CloudworksThreadModule> type() {
        MachineModuleType<CloudworksThreadModule> type = MachineModuleType.builtInModule(ID,
                MultiblockMachineUpgrades.THREAD.getMaxInstalled(), "upgrade.thread", new CloudworksThreadModule());
        ItemStack item = MultiblockMachineUpgrades.THREAD.getStack();
        return item.isEmpty() || item.getItem().getRegistryName() == null ? type : type.withItem(item);
    }
    @Override public RecipeContribution onRecipes(IMachineModule<CloudworksThreadModule> module, RecipeFreezeContext context) {
        MachineExecutionProfile base = context.machine().profile();
        int factor = Math.addExact(1, module.getEffectiveInstalledCount());
        ResourceLocation machine = context.machine().machine().getItem().getRegistryName();
        if (machine != null && "mekceumoremachine".equals(machine.getNamespace())
                && "voidmineralgenerator".equals(machine.getPath())) {
            // The native mineral generator chooses one ore and multiplies its count;
            // THREAD changes neither tick power nor the energy buffer on this family.
            java.util.List<CloudworksRecipeSpec> recipes = new java.util.ArrayList<>();
            for (CloudworksRecipeSpec recipe : context.baseRecipes())
                recipes.add(recipe.editor(recipe.id() + "_thread")
                        .replaceChoiceOutputs(recipe.choiceOutputs(), Math.multiplyExact(recipe.choiceAmount(), factor)).build());
            return RecipeContribution.of(RecipeContribution.Installation.PER_MACHINE, 1,
                    RecipeContribution.RecipeMode.REPLACE, recipes, new MachineSlotLayout());
        }
        MachineExecutionProfile profile = new MachineExecutionProfile(Math.multiplyExact(base.threads(), factor),
                base.energyPerTick(), base.energyCapacity() * factor, base.totalTicks(), base.upgradePolicy(), base.lineUpgradePolicy());
        return RecipeContribution.of(RecipeContribution.Installation.PER_MACHINE, 1, RecipeContribution.RecipeMode.NONE,
                java.util.Collections.emptyList(), new MachineSlotLayout()).withProfile(profile);
    }
}
