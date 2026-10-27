package mekanism.cloudworks.api.module;

import java.util.Collections;
import java.util.List;
import java.util.Collection;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.machine.MachineOutput;
import mekanism.cloudworks.api.work.PostWorkContext;
import mekanism.cloudworks.api.work.PreWorkContext;
import mekanism.cloudworks.api.recipe.RecipeCandidateContext;
import mekanism.cloudworks.api.recipe.RecipeCandidateDecision;
import mekanism.cloudworks.api.recipe.RecipeContribution;
import mekanism.cloudworks.api.recipe.RecipeFreezeContext;
import mekanism.cloudworks.api.work.WorkCancelledContext;
import mekanism.cloudworks.api.work.WorkCommittedContext;
import mekanism.cloudworks.api.work.WorkContribution;
import mekanism.cloudworks.api.work.WorkStartedContext;

/**
 * Stateless behavior contract for modules that affect a CloudWorks machine.
 *
 * <p>Runtime callbacks receive only immutable snapshots. They must not access
 * worlds, tiles, players, inventories, mutable static state, or external
 * services. Implementations should be safe to invoke concurrently.</p>
 */
public interface ICustomMachineModule<MODULE extends ICustomMachineModule<MODULE>> {
    /** All additional resource identities a post-work transformation may return; evaluated before worker dispatch. */
    default Collection<QIOResourceDescriptor> outputResources() {
        return Collections.emptyList();
    }
    /** Opts into configuration-time capacity evaluation (empty recipe id, operation count zero). */
    default boolean affectsCapacity() { return false; }
    /** Optional shared native socket group; empty has no shared socket restriction. */
    default String installationGroup() { return ""; }
    default int installationSlotsPerMachine() { return 0; }
    default int installationItemsPerSlot() { return 1; }
    /** Lower priorities run first; ids and source ids break ties deterministically. */
    default int postWorkPriority() { return 0; }
    default void onAdded(IMachineModule<MODULE> module, ModuleChangeContext change) {}

    default void onRemoved(IMachineModule<MODULE> module, ModuleChangeContext change) {}

    default RecipeContribution onRecipes(IMachineModule<MODULE> module, RecipeFreezeContext context) {
        return RecipeContribution.none();
    }

    default RecipeCandidateDecision onRecipeCandidate(
            IMachineModule<MODULE> module, RecipeCandidateContext context) {
        return RecipeCandidateDecision.accept();
    }

    default WorkContribution onPreWork(IMachineModule<MODULE> module, PreWorkContext context) {
        return WorkContribution.neutral();
    }

    /**
     * Configuration-time numerical takeover of one explicitly declared upgrade
     * axis. Null uses the machine policy's axis curve. These multipliers must
     * not be returned again by onPreWork; non-axis work behavior remains there.
     */
    default AxisContribution onUpgradeAxis(IMachineModule<MODULE> module, UpgradeAxis axis) {
        return null;
    }

    default List<MachineOutput> onPostWork(IMachineModule<MODULE> module, PostWorkContext context,
            List<MachineOutput> outputs) {
        return outputs;
    }

    default boolean discardsChanceOnOverflow() {
        return false;
    }

    default void onWorkStarted(IMachineModule<MODULE> module, WorkStartedContext context) {}

    default void onWorkCommitted(IMachineModule<MODULE> module, WorkCommittedContext context) {}

    default void onWorkCancelled(IMachineModule<MODULE> module, WorkCancelledContext context) {}

}
