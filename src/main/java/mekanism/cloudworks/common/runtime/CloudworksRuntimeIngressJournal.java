package mekanism.cloudworks.common.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Boundary-side revision journal. A gap means a structural/administrative
 * mutation, not a port event; old Runtime results must then be rejected.
 */
public final class CloudworksRuntimeIngressJournal {
    private static final int MAX_ENTRIES = 8192;
    private final NavigableMap<Long, CloudworksExternalEvent> byRevision = new TreeMap<>();
    private final NavigableMap<Long, CloudworksExternalEvent> bySequence = new TreeMap<>();
    private long sequence;

    public long sequence() { return sequence; }

    public void record(long revision, CloudworksExternalEvent.Kind kind, int resource, long amount, double energy) {
        CloudworksExternalEvent event = new CloudworksExternalEvent(Math.addExact(sequence, 1),
                kind, resource, amount, energy);
        if (byRevision.containsKey(revision)) throw new IllegalArgumentException("Duplicate Runtime ingress revision");
        byRevision.put(revision, event);
        bySequence.put(event.sequence(), event);
        sequence = event.sequence();
        while (bySequence.size() > MAX_ENTRIES) {
            bySequence.pollFirstEntry();
            byRevision.pollFirstEntry();
        }
    }

    public boolean covers(long previousRevision, long currentRevision) {
        if (previousRevision == currentRevision) return true;
        if (previousRevision > currentRevision || currentRevision - previousRevision > MAX_ENTRIES) return false;
        return byRevision.subMap(previousRevision, false, currentRevision, true).size()
                == currentRevision - previousRevision;
    }

    /** Null means the requested sequence was evicted or belongs to another epoch. */
    public List<CloudworksExternalEvent> after(long appliedSequence) {
        if (appliedSequence > sequence || appliedSequence < 0
                || appliedSequence < sequence && (bySequence.isEmpty()
                    || appliedSequence + 1 < bySequence.firstKey())) return null;
        return new ArrayList<>(bySequence.tailMap(appliedSequence, false).values());
    }
}
