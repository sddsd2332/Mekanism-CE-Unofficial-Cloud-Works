package mekanism.cloudworks.api.client;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.item.ItemStack;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only projection of one line node handed to client panel providers.
 * Never exposes the internal container, editor draft or runtime state; the
 * backing data is an immutable snapshot taken when the node was clicked.
 */
public interface CloudworksClientNodeView {
    /** Stable node id within the line. */
    UUID id();

    /** Line id the node belongs to. */
    UUID line();

    /** Node category: {@code "machine"} or {@code "channel"}. Links have their own view. */
    String kind();

    /** Namespaced special-node archetype id, or {@code ""} for plain nodes. */
    String archetypeId();

    /** Localization key for the node title; empty when the icon carries its own localized name. */
    String titleKey();

    /**
     * Whether the current session is editing. Panel windows must stay
     * read-only when this is {@code false}; observer mode cannot be bypassed
     * by the mere presence of a panel.
     */
    boolean editing();

    /** Safe identity icon. Implementations must return a defensive copy. */
    default ItemStack icon() { return ItemStack.EMPTY.copy(); }
    default String machineRegistryId() { return ""; }
    default int machineMetadata() { return 0; }

    /** Current configured machine count; zero for non-machine views. */
    default long machineCount() { return 0L; }

    /** Current configured enabled state. */
    default boolean active() { return false; }

    /** Selected machine recipe mode, empty for automatic mode. */
    default String recipeType() { return ""; }

    /** Current runtime state label when available. */
    default String state() { return ""; }

    default int progress() { return 0; }
    default int duration() { return 0; }

    /** Runtime resource snapshots; amounts are read-only UI projections. */
    default List<Resource> inputs() { return Collections.emptyList(); }
    default List<Resource> outputs() { return Collections.emptyList(); }
    default List<Resource> templates() { return Collections.emptyList(); }
    default List<Resource> resources() { return Collections.emptyList(); }
    default List<Port> ports() { return Collections.emptyList(); }
    default List<ExecutionBranch> executionBranches() { return Collections.emptyList(); }
    default Map<String, Long> installedCustomUpgrades() { return Collections.emptyMap(); }

    /** Channel mode, or empty for machine nodes. */
    default String channelMode() { return ""; }

    final class ExecutionBranch {
        private final String id, state;
        private final int progress, duration;
        public ExecutionBranch(String id, String state, int progress, int duration) {
            if (progress < 0 || duration < 1 || progress >= duration) throw new IllegalArgumentException("Invalid branch progress");
            this.id = Objects.requireNonNull(id); this.state = Objects.requireNonNull(state);
            this.progress = progress; this.duration = duration;
        }
        public String id() { return id; }
        public String state() { return state; }
        public int progress() { return progress; }
        public int duration() { return duration; }
    }

    final class Resource {
        private final QIOResourceDescriptor descriptor;
        private final long amount;

        public Resource(QIOResourceDescriptor descriptor, long amount) {
            this.descriptor = Objects.requireNonNull(descriptor);
            if (amount < 0) throw new IllegalArgumentException("Negative client resource amount");
            this.amount = amount;
        }

        public QIOResourceDescriptor descriptor() { return descriptor; }
        public long amount() { return amount; }
    }

    final class Port {
        private final String id, codec;
        private final boolean output;
        private final String role;

        public Port(String id, String codec, boolean output) {
            this(id, codec, output ? "OUTPUT" : "INPUT");
        }

        public Port(String id, String codec, String role) {
            this.id = Objects.requireNonNull(id);
            this.codec = Objects.requireNonNull(codec);
            if (!"INPUT".equals(role) && !"OUTPUT".equals(role) && !"CONFIGURATION".equals(role))
                throw new IllegalArgumentException("Invalid client port role");
            this.role = role;
            this.output = "OUTPUT".equals(role);
        }

        public String id() { return id; }
        public String codec() { return codec; }
        public boolean output() { return output; }
        public String role() { return role; }
    }
}
