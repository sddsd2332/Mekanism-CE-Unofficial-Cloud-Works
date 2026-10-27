package mekanism.cloudworks.common.data;

import java.util.Map;

/**
 * Query/mutation surface over one host's flow gates (8.4). Implementations
 * keep their stored fields as the single truth; this view is the one
 * mechanism consumers go through, which is what keeps the gate model from
 * forking into per-feature copies.
 */
public interface CloudworksFlowGates {

    /** Storage scope of this view; gates of the other scope are rejected. */
    CloudworksFlowGate.Scope scope();

    /** Current value of a gate of this scope. */
    boolean gate(CloudworksFlowGate gate);

    /** Writes a gate of this scope. */
    void set(CloudworksFlowGate gate, boolean value);

    /**
     * Applies a {@code "k=1;k=0"} editor protocol. Gates that are not
     * mentioned — and keys that are not editable gates of this scope — keep
     * their current value.
     */
    default void applyWire(String protocol) {
        for (Map.Entry<CloudworksFlowGate, Boolean> edit : CloudworksFlowGate.parseWire(scope(), protocol).entrySet())
            set(edit.getKey(), edit.getValue());
    }
}
