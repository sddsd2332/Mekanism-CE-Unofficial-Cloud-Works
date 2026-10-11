package mekanism.cloudworks.common.runtime;

import mekanism.cloudworks.common.service.CloudworksDetachedState;
import mekanism.cloudworks.common.service.CloudworksResourceSymbols;

/**
 * Builds the hot Runtime stores from the current persistence projection.
 * This is intentionally a one-way boundary; the Runtime never keeps the
 * persistence objects or their mutable collections.
 */
public final class CloudworksRuntimeProjection {
    private CloudworksRuntimeProjection() {
    }

    public static void load(CloudworksPlayerRuntime runtime, CloudworksDetachedState.Capture capture,
            CloudworksRuntimeCatalog catalog, CloudworksResourceSymbols symbols) {
        if (!runtime.owner().equals(capture.view().owner))
            throw new IllegalArgumentException("Runtime owner mismatch");
        if (runtime.isRunning()) throw new IllegalStateException("Cannot project running Runtime");
        runtime.install(new CloudworksRuntimeProgram(capture.view(), catalog, symbols.families(), runtime));
    }
}
