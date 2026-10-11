package mekanism.cloudworks.common.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * Main-thread boundary for physical upload/download components.
 *
 * <p>Each owner has its own contiguous sequence. Events carry data rather than
 * closures capturing a physical source or a mutable service lookup.</p>
 */
public final class CloudworksExternalEventBridge {
    public boolean submit(UUID owner, CloudworksRuntimeService service, CloudworksExternalEvent event) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(service);
        Objects.requireNonNull(event);
        return service.runtime(owner).enqueue(event);
    }
}
