package mekanism.cloudworks.api.node;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import java.util.ArrayList;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.module.MachineModuleSource;
import mekanism.cloudworks.api.module.MachineModuleType;

/**
 * One special-node archetype declaration: a non-physical production-line
 * boundary. The declaration is data-only; runtime workers consume the frozen
 * id/kind/port values and never resolve registries or invoke Minecraft world
 * APIs.
 *
 * <p>Besides direction and display info, the contract carries the node's
 * {@link CloudworksPortDefinition port set} and an optional server-thread
 * {@link ConfigValidator configuration validator} (9.1 deepening). The core
 * interprets special nodes through the player-granted resource machinery
 * (card unlock, single resource per node, placement quota, configured rate),
 * which the built-in infinite-resources source keeps as the first consumer.</p>
 */
public final class CloudworksSpecialNodeType {
    /** Boundary direction of the archetype. */
    public enum Kind {
        SOURCE,
        SINK
    }

    /**
     * Server-thread configuration validator. It runs when an edit or blueprint
     * placement commits, on the manager lock; it must be stateless and must
     * throw to refuse a configuration. Never invoked on Runtime workers.
     */
    @FunctionalInterface
    public interface ConfigValidator {
        void validate(NBTTagCompound config);
    }

    private final ResourceLocation id;
    private final String translationKey;
    private final Kind kind;
    private final List<CloudworksPortDefinition> ports;
    private final ConfigValidator configValidator;
    private final boolean resourceSelectionRequired;
    private final MachineModuleType<?> module;

    private CloudworksSpecialNodeType(ResourceLocation id, String translationKey, Kind kind,
            List<CloudworksPortDefinition> ports, ConfigValidator configValidator) {
        this(id, translationKey, kind, ports, configValidator, true);
    }

    private CloudworksSpecialNodeType(ResourceLocation id, String translationKey, Kind kind,
            List<CloudworksPortDefinition> ports, ConfigValidator configValidator, boolean resourceSelectionRequired) {
        this(id, translationKey, kind, ports, configValidator, resourceSelectionRequired, null);
    }
    private CloudworksSpecialNodeType(ResourceLocation id, String translationKey, Kind kind,
            List<CloudworksPortDefinition> ports, ConfigValidator configValidator, boolean resourceSelectionRequired,
            MachineModuleType<?> module) {
        this.id = Objects.requireNonNull(id, "id");
        this.translationKey = Objects.requireNonNull(translationKey, "translationKey");
        this.kind = Objects.requireNonNull(kind, "kind");
        if ("cloudworks".equals(id.getNamespace()) || "mekanism".equals(id.getNamespace()))
            throw new IllegalArgumentException("Reserved special-node namespace: " + id.getNamespace());
        if (translationKey.isEmpty()) throw new IllegalArgumentException("Empty translation key");
        if (ports != null) {
            Set<String> portIds = new LinkedHashSet<>();
            for (CloudworksPortDefinition port : ports)
                if (port == null || !portIds.add(port.id()))
                    throw new IllegalArgumentException("Duplicate special-node port: " + (port == null ? "null" : port.id()));
            this.ports = Collections.unmodifiableList(new ArrayList<>(ports));
        } else {
            this.ports = null; // kind-default derivation, see ports()
        }
        this.configValidator = configValidator;
        this.resourceSelectionRequired = resourceSelectionRequired;
        this.module = module;
    }

    /** Declares a source boundary; ports default to the kind-default item port. */
    public static CloudworksSpecialNodeType source(ResourceLocation id, String translationKey) {
        return new CloudworksSpecialNodeType(id, translationKey, Kind.SOURCE, null, null);
    }

    /** Declares a sink boundary; ports default to the kind-default item port. */
    public static CloudworksSpecialNodeType sink(ResourceLocation id, String translationKey) {
        return new CloudworksSpecialNodeType(id, translationKey, Kind.SINK, null, null);
    }

    /** Replaces the port set; port ids must be unique within the node. */
    public CloudworksSpecialNodeType withPorts(List<CloudworksPortDefinition> declared) {
        return new CloudworksSpecialNodeType(id, translationKey, kind, declared, configValidator, resourceSelectionRequired, module);
    }

    /** Attaches the server-thread configuration validator. */
    public CloudworksSpecialNodeType withConfigValidator(ConfigValidator validator) {
        return new CloudworksSpecialNodeType(id, translationKey, kind, ports, validator, resourceSelectionRequired, module);
    }

    /** Declares whether blueprint/configuration manifests require one selected resource or must be empty. */
    public CloudworksSpecialNodeType withResourceSelection(boolean required) {
        if (module != null && required) throw new IllegalArgumentException("Environment module sources have no resource selection");
        return new CloudworksSpecialNodeType(id, translationKey, kind, ports, configValidator, required, module);
    }
    public boolean requiresResourceSelection() { return resourceSelectionRequired; }
    public CloudworksSpecialNodeType withMachineModule(MachineModuleType<?> module) {
        Objects.requireNonNull(module);
        if (kind != Kind.SOURCE || !module.acceptsSource(MachineModuleSource.ENVIRONMENT_NODE) || module.environment() == null)
            throw new IllegalArgumentException("Special environment sources require an environment module");
        return new CloudworksSpecialNodeType(id, translationKey, kind,
                ports == null ? Collections.emptyList() : ports, configValidator, false, module);
    }
    public MachineModuleType<?> module() { return module; }

    /** Kind-default port shape: SOURCE anchors as an output, SINK accepts input. */
    public static CloudworksPortDefinition defaultPort(String codecId, Kind kind) {
        return kind == Kind.SOURCE
                ? CloudworksPortDefinition.channel(codecId, true, false, true)
                : CloudworksPortDefinition.channel(codecId, false, true, false);
    }

    public ResourceLocation id() {
        return id;
    }

    public String translationKey() {
        return translationKey;
    }

    public Kind kind() {
        return kind;
    }

    /** Declared port set, or the kind-default item port when none was declared. */
    public List<CloudworksPortDefinition> ports() {
        if (ports != null) return ports;
        return Collections.singletonList(defaultPort(
                QIOResourceCodecs.ITEM_STACK_ID.toString(), kind));
    }

    /** Whether an explicit port set was declared. */
    public boolean explicitPorts() {
        return ports != null;
    }

    /** Configuration validator; null means any (size-bounded) config is accepted. */
    public ConfigValidator configValidator() {
        return configValidator;
    }
}
