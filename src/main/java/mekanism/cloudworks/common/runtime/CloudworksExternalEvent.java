package mekanism.cloudworks.common.runtime;

/** Immutable port mutation. No callback, descriptor, world or inventory object crosses the mailbox. */
public final class CloudworksExternalEvent {
    public enum Kind {RECEIVING_RESOURCE, SENDING_RESOURCE, RECEIVING_ENERGY, SENDING_ENERGY}

    private final long sequence;
    private final Kind kind;
    private final int resource;
    private final long amount;
    private final double energy;

    public CloudworksExternalEvent(long sequence, Kind kind, int resource, long amount, double energy) {
        if (sequence <= 0 || kind == null || !Double.isFinite(energy) || energy < 0)
            throw new IllegalArgumentException("Invalid Runtime ingress event");
        boolean resourceEvent = kind == Kind.RECEIVING_RESOURCE || kind == Kind.SENDING_RESOURCE;
        if (resourceEvent ? resource < 0 || amount <= 0 || energy != 0
                : resource != -1 || amount != 0 || energy <= 0)
            throw new IllegalArgumentException("Invalid Runtime ingress quantities");
        this.sequence = sequence;
        this.kind = kind;
        this.resource = resource;
        this.amount = amount;
        this.energy = energy;
    }

    public long sequence() { return sequence; }
    public Kind kind() { return kind; }
    public int resource() { return resource; }
    public long amount() { return amount; }
    public double energy() { return energy; }

    void apply(CloudworksPlayerRuntime runtime) {
        runtime.applyIngress(this);
    }
}
