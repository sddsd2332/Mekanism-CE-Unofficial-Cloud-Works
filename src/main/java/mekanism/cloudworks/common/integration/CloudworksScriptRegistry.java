package mekanism.cloudworks.common.integration;

import java.util.ArrayList;
import java.util.List;
import mekanism.cloudworks.common.registry.CloudworksRegistries;

/** CT-independent startup queue; declarations are resolved after optional machine registrations. */
public final class CloudworksScriptRegistry {
    private static final List<Runnable> DECLARATIONS = new ArrayList<>();
    private static final List<Runnable> FINAL_DECLARATIONS = new ArrayList<>();
    private static boolean applied;

    private CloudworksScriptRegistry() {}

    public static synchronized void enqueue(Runnable declaration) {
        if (applied || CloudworksRegistries.isFrozen())
            throw new IllegalStateException("Cloudworks scripts require a restart");
        DECLARATIONS.add(java.util.Objects.requireNonNull(declaration));
    }

    public static synchronized void enqueueFinal(Runnable declaration) {
        if (applied || CloudworksRegistries.isFrozen())
            throw new IllegalStateException("Cloudworks scripts require a restart");
        FINAL_DECLARATIONS.add(java.util.Objects.requireNonNull(declaration));
    }

    public static synchronized void apply() {
        if (applied) return;
        try {
            for (Runnable declaration : DECLARATIONS) applyOne(declaration);
            try {
                CloudworksScriptMachines.finish();
            } catch (RuntimeException failure) {
                mekanism.common.Mekanism.logger.error("Cloudworks script machine finalization failed", failure);
            }
            for (Runnable declaration : FINAL_DECLARATIONS) applyOne(declaration);
        } finally {
            DECLARATIONS.clear();
            FINAL_DECLARATIONS.clear();
            applied = true;
        }
    }

    private static void applyOne(Runnable declaration) {
        try {
            declaration.run();
        } catch (RuntimeException failure) {
            mekanism.common.Mekanism.logger.error("Cloudworks script declaration failed", failure);
        }
    }
}
