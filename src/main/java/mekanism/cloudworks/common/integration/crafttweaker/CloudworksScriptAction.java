package mekanism.cloudworks.common.integration.crafttweaker;

import crafttweaker.CraftTweakerAPI;
import crafttweaker.IAction;
import mekanism.cloudworks.common.integration.CloudworksScriptRegistry;

/** Intentionally not reloadable: scripts never mutate the running frozen registry. */
final class CloudworksScriptAction implements IAction {
    private final String description;
    private final Runnable declaration;
    private final boolean finalPhase;

    private CloudworksScriptAction(String description, Runnable declaration, boolean finalPhase) {
        this.description = description;
        this.declaration = declaration;
        this.finalPhase = finalPhase;
    }

    static void submit(String description, Runnable declaration) { submit(description, declaration, false); }

    static void submit(String description, Runnable declaration, boolean finalPhase) {
        CraftTweakerAPI.apply(new CloudworksScriptAction(description, declaration, finalPhase));
    }

    @Override
    public void apply() {
        Runnable checked = () -> {
            try { declaration.run(); }
            catch (RuntimeException failure) {
                CraftTweakerAPI.logError(description, failure);
                throw new IllegalStateException(description + ": " + failure.getMessage(), failure);
            }
        };
        if (finalPhase) CloudworksScriptRegistry.enqueueFinal(checked);
        else CloudworksScriptRegistry.enqueue(checked);
    }

    @Override
    public String describe() { return description; }
}
