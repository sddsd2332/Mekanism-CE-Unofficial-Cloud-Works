package mekanism.cloudworks.common.data;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Unified declaration of every built-in flow gate (8.4). Exactly one
 * declaration exists per gate; the owning data class keeps its stored field
 * as the single truth and exposes reads/writes through a
 * {@link CloudworksFlowGates} view, so no second state copy exists.
 *
 * <p>The wire key marks the gates the editor protocols may flip. Gates whose
 * wire key is {@code null} (the cloud-side relay-to-lines gates and the
 * explicit-routing toggle) are deliberately outside the protocol surface:
 * legacy packets that mention them must keep being ignored.</p>
 */
public enum CloudworksFlowGate {
    // Cloud scope: receiving relay -> cloud pool, receiving relay -> line pools,
    // cloud pool -> sending relay, line pools -> sending relay.
    RECEIVING_TO_CLOUD(Scope.CLOUD, "flowReceivingToCloud", "r2c", true),
    RECEIVING_TO_LINES(Scope.CLOUD, "flowReceivingToLines", null, true),
    CLOUD_TO_SENDING(Scope.CLOUD, "flowCloudToSending", "c2s", false),
    LINES_TO_SENDING(Scope.CLOUD, "flowLinesToSending", null, false),
    // Line scope: line pool feeding, line pool -> sending relay, and the
    // routing model toggle folded into the same mechanism (storage key unchanged).
    EXPLICIT_ROUTING(Scope.LINE, "explicitRouting", null, false),
    RECEIVING_TO_LINE(Scope.LINE, "energyFlowReceivingToLine", "r2l", true),
    LINE_TO_SENDING(Scope.LINE, "energyFlowLineToSending", "l2s", false);

    public enum Scope {
        CLOUD, LINE
    }

    private final Scope scope;
    private final String storageKey;
    private final String wireKey;
    private final boolean defaultValue;

    CloudworksFlowGate(Scope scope, String storageKey, String wireKey, boolean defaultValue) {
        this.scope = scope;
        this.storageKey = storageKey;
        this.wireKey = wireKey;
        this.defaultValue = defaultValue;
    }

    /** Which host stores this gate: cloud data or production line data. */
    public Scope scope() {
        return scope;
    }

    /** NBT key of the stored field backing this gate (unchanged schema). */
    public String storageKey() {
        return storageKey;
    }

    /** Protocol key accepted by the editor wire format, or {@code null} when not protocol-editable. */
    public String wireKey() {
        return wireKey;
    }

    /** Default value a fresh host starts with; must stay pinned by tests. */
    public boolean defaultValue() {
        return defaultValue;
    }

    /** Wire lookup: {@code null} when the key is not an editable gate of this scope. */
    public static CloudworksFlowGate fromWire(Scope scope, String key) {
        for (CloudworksFlowGate gate : values())
            if (gate.scope == scope && key != null && key.equals(gate.wireKey)) return gate;
        return null;
    }

    /**
     * Parses a {@code "k=1;k2=0"} protocol for one scope. Malformed segments
     * and keys that are not editable gates of this scope are ignored, exactly
     * like the legacy inline parsers did. The returned map is fresh and
     * contains only mentioned gates.
     */
    public static Map<CloudworksFlowGate, Boolean> parseWire(Scope scope, String protocol) {
        if (protocol == null || protocol.isEmpty()) return Collections.emptyMap();
        Map<CloudworksFlowGate, Boolean> parsed = new EnumMap<>(CloudworksFlowGate.class);
        for (String segment : protocol.split(";")) {
            String[] pair = segment.split("=", 2);
            if (pair.length != 2) continue;
            CloudworksFlowGate gate = fromWire(scope, pair[0]);
            if (gate != null) parsed.put(gate, "1".equals(pair[1]));
        }
        return parsed;
    }
}
