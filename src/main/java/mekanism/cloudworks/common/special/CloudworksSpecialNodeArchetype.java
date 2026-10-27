package mekanism.cloudworks.common.special;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import mekanism.cloudworks.api.node.CloudworksPortDefinition;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.node.CloudworksSpecialNodeType;
import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.cloudworks.common.data.CloudworksChannelNode;

/**
 * Server-owned declaration for a non-physical production-line boundary.
 *
 * <p>The declaration is deliberately data-only. Runtime workers consume the
 * frozen id/kind/port values and never resolve registries or invoke Minecraft
 * world APIs. The optional configuration validator runs only on the manager
 * lock when an edit or blueprint placement commits.</p>
 */
public final class CloudworksSpecialNodeArchetype {
    public enum Kind {
        SOURCE,
        SINK
    }

    private final ResourceLocation id;
    private final String translationKey;
    private final Kind kind;
    private final List<CloudworksPortDefinition> ports;
    private final CloudworksSpecialNodeType.ConfigValidator configValidator;
    private final boolean resourceSelectionRequired;
    private final MachineModuleType<?> module;

    CloudworksSpecialNodeArchetype(ResourceLocation id, String translationKey, Kind kind) {
        this(id, translationKey, kind, null, null);
    }

    CloudworksSpecialNodeArchetype(ResourceLocation id, String translationKey, Kind kind,
            List<CloudworksPortDefinition> ports,
            CloudworksSpecialNodeType.ConfigValidator configValidator) {
        this(id, translationKey, kind, ports, configValidator, true);
    }
    CloudworksSpecialNodeArchetype(ResourceLocation id, String translationKey, Kind kind,
            List<CloudworksPortDefinition> ports,
            CloudworksSpecialNodeType.ConfigValidator configValidator, boolean resourceSelectionRequired) {
        this(id, translationKey, kind, ports, configValidator, resourceSelectionRequired, null);
    }
    CloudworksSpecialNodeArchetype(ResourceLocation id, String translationKey, Kind kind,
            List<CloudworksPortDefinition> ports,
            CloudworksSpecialNodeType.ConfigValidator configValidator, boolean resourceSelectionRequired,
            MachineModuleType<?> module) {
        this.id = Objects.requireNonNull(id, "id");
        this.translationKey = Objects.requireNonNull(translationKey, "translationKey");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.ports = ports;
        this.configValidator = configValidator;
        this.resourceSelectionRequired = resourceSelectionRequired;
        this.module = module;
    }
    public boolean requiresResourceSelection() { return resourceSelectionRequired; }
    public MachineModuleType<?> module() { return module; }

    public ResourceLocation id() {
        return id;
    }

    public String idString() {
        return id.toString();
    }

    public String translationKey() {
        return translationKey;
    }

    public Kind kind() {
        return kind;
    }

    public boolean source() {
        return kind == Kind.SOURCE;
    }

    public boolean sink() {
        return kind == Kind.SINK;
    }

    /**
     * Effective port set: the declared ports when present, else the
     * kind-default item port. Editor canvas, topology validator and Runtime
     * compiler derive special-channel ports through {@link #channelPorts}.
     */
    public List<CloudworksPortDefinition> ports() {
        if (ports != null) return ports;
        CloudworksSpecialNodeType.Kind apiKind = source()
                ? CloudworksSpecialNodeType.Kind.SOURCE
                : CloudworksSpecialNodeType.Kind.SINK;
        return Collections.singletonList(CloudworksSpecialNodeType.defaultPort(
                QIOResourceCodecs.ITEM_STACK_ID.toString(), apiKind));
    }

    /** Whether the archetype declared an explicit port set. */
    public boolean explicitPorts() {
        return ports != null;
    }

    /** Runs the declared configuration validator on the manager lock; no-op when absent. */
    public void validateConfig(NBTTagCompound config) {
        if (configValidator != null) configValidator.validate(config);
    }

    /**
     * The one special-channel port derivation (9.1): explicit archetype ports
     * replace the mode-derived per-codec set; archetypes without explicit
     * ports keep the historical mode behaviour, so the built-in is unchanged.
     */
    public static List<CloudworksPortDefinition> channelPorts(String archetypeId,
            CloudworksChannelNode.Mode mode, List<String> codecs) {
        CloudworksSpecialNodeArchetype archetype = archetypeId == null || archetypeId.isEmpty()
                ? null : CloudworksSpecialNodeArchetypes.get(archetypeId);
        if (archetype != null && archetype.explicitPorts()) return archetype.ports();
        List<CloudworksPortDefinition> derived = new ArrayList<>(codecs.size());
        for (String codec : codecs) derived.add(mode.port(codec));
        return derived;
    }
}
