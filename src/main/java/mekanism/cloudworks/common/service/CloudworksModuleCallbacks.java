package mekanism.cloudworks.common.service;

import java.util.List;
import mekanism.cloudworks.api.module.ICustomMachineModule;
import mekanism.cloudworks.api.module.IMachineModule;
import mekanism.cloudworks.api.recipe.RecipeCandidateContext;
import mekanism.cloudworks.api.work.PostWorkContext;
import mekanism.cloudworks.api.work.PreWorkContext;
import mekanism.cloudworks.api.work.WorkCancelledContext;
import mekanism.cloudworks.api.work.WorkCommittedContext;
import mekanism.cloudworks.api.work.WorkStartedContext;

/** Class-scoped callback classification, evaluated only while binding modules. */
public final class CloudworksModuleCallbacks {
    private static final ClassValue<CloudworksModuleCallbacks> CACHE = new ClassValue<CloudworksModuleCallbacks>() {
        @Override protected CloudworksModuleCallbacks computeValue(Class<?> type) {
            return new CloudworksModuleCallbacks(type);
        }
    };
    public final boolean preWork, candidate, postWork, started, committed, cancelled;

    private CloudworksModuleCallbacks(Class<?> type) {
        preWork = overrides(type, "onPreWork", PreWorkContext.class);
        candidate = overrides(type, "onRecipeCandidate", RecipeCandidateContext.class);
        postWork = overrides(type, "onPostWork", PostWorkContext.class, List.class);
        started = overrides(type, "onWorkStarted", WorkStartedContext.class);
        committed = overrides(type, "onWorkCommitted", WorkCommittedContext.class);
        cancelled = overrides(type, "onWorkCancelled", WorkCancelledContext.class);
    }

    static CloudworksModuleCallbacks of(ICustomMachineModule<?> module) {
        return CACHE.get(module.getClass());
    }

    private static boolean overrides(Class<?> type, String method, Class<?>... context) {
        Class<?>[] parameters = new Class<?>[context.length + 1];
        parameters[0] = IMachineModule.class;
        System.arraycopy(context, 0, parameters, 1, context.length);
        try {
            return type.getMethod(method, parameters).getDeclaringClass() != ICustomMachineModule.class;
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            // Unclassifiable addons retain all callbacks.
            return true;
        }
    }
}
