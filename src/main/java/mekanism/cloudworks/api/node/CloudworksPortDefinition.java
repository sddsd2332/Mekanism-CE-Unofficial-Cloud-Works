package mekanism.cloudworks.api.node;

import java.util.Objects;
import net.minecraft.util.ResourceLocation;
import mekanism.cloudworks.api.machine.MachineSlotLayout;

/**
 * Immutable port contract shared by the line editor canvas, the server
 * topology validator and the Runtime compiler (8.1 closeout: the contract is
 * part of the public API surface and carries no internal-state dependency).
 *
 * <p>Channel ports are derived from the owning channel's declared shape via
 * the explicit-capability factory; the mode-to-shape mapping stays with the
 * internal channel data so no internal type appears in this contract.</p>
 */
public final class CloudworksPortDefinition {
    private final String id;
    private final ResourceLocation codec;
    private final boolean canOutput;
    private final boolean canInput;
    private final boolean outputAnchor;

    private CloudworksPortDefinition(String id, ResourceLocation codec,
            boolean canOutput, boolean canInput, boolean outputAnchor) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Empty Cloudworks port id");
        this.id = id;
        this.codec = Objects.requireNonNull(codec);
        this.canOutput = canOutput;
        this.canInput = canInput;
        this.outputAnchor = outputAnchor;
    }

    /** Machine port derived from a declared slot; OUTPUT slots anchor as sources. */
    public static CloudworksPortDefinition machine(MachineSlotLayout.Slot slot) {
        Objects.requireNonNull(slot);
        boolean output = slot.role() == MachineSlotLayout.PortRole.OUTPUT;
        return new CloudworksPortDefinition(slot.id(), slot.codecId(),
                output, slot.role().isInput(), output);
    }

    /**
     * Channel port with explicit capabilities. The port id is the codec id,
     * mirroring the historical single-port-per-codec channel shape.
     */
    public static CloudworksPortDefinition channel(String codecId,
            boolean canOutput, boolean canInput, boolean outputAnchor) {
        ResourceLocation codec = new ResourceLocation(codecId);
        return new CloudworksPortDefinition(codec.toString(), codec, canOutput, canInput, outputAnchor);
    }

    public String id() { return id; }
    public ResourceLocation codec() { return codec; }
    public boolean canOutput() { return canOutput; }
    public boolean canInput() { return canInput; }
    public boolean outputAnchor() { return outputAnchor; }

    /**
     * Whether two ports may be linked at all: same resource codec and a
     * resolvable direction. The editor canvas, the server topology validator
     * and the runtime compiler agree on this one rule, so a link drawn in the
     * GUI can never be accepted by a different rule set on the server.
     */
    public static boolean compatible(CloudworksPortDefinition a, CloudworksPortDefinition b) {
        return orientation(a, b) != null;
    }

    /**
     * Directional orientation of a candidate pair: [source, target] when the
     * resource flow is unambiguous, null when incompatible. Ambidirectional
     * pairs resolve by the anchor flag, mirroring the historical canvas rule.
     */
    public static CloudworksPortDefinition[] orientation(CloudworksPortDefinition a, CloudworksPortDefinition b) {
        if (a == null || b == null || !a.codec().equals(b.codec())) return null;
        boolean aToB = a.canOutput() && b.canInput();
        boolean bToA = b.canOutput() && a.canInput();
        if (!aToB && !bToA) return null;
        if (aToB && !bToA) return new CloudworksPortDefinition[]{a, b};
        if (bToA && !aToB) return new CloudworksPortDefinition[]{b, a};
        if (a.outputAnchor() != b.outputAnchor()) return a.outputAnchor() ? new CloudworksPortDefinition[]{a, b}
                : new CloudworksPortDefinition[]{b, a};
        return new CloudworksPortDefinition[]{a, b};
    }
}
