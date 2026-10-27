package mekanism.cloudworks.api.machine;

import mekanism.api.qio.resource.QIOResourceDescriptor;

/** Immutable resource promised by one completed operation. */
public final class MachineOutput {
    private final QIOResourceDescriptor descriptor;
    private final long amount;
    private final String port;
    private final boolean chanceOutput;
    public MachineOutput(QIOResourceDescriptor descriptor, long amount, String port) {
        this(descriptor, amount, port, false);
    }

    public MachineOutput(QIOResourceDescriptor descriptor, long amount, String port, boolean chanceOutput) {
        if (descriptor == null || !descriptor.isResolved() || amount <= 0)
            throw new IllegalArgumentException("Invalid machine output");
        this.descriptor = descriptor;
        this.amount = amount;
        this.port = port == null ? "" : port;
        this.chanceOutput = chanceOutput;
    }

    public QIOResourceDescriptor descriptor() { return descriptor; }
    public long amount() { return amount; }
    public String port() { return port; }
    public boolean chanceOutput() { return chanceOutput; }
    public MachineOutput amount(long value) { return new MachineOutput(descriptor, value, port, chanceOutput); }
    public MachineOutput descriptor(QIOResourceDescriptor value) { return new MachineOutput(value, amount, port, chanceOutput); }
    public MachineOutput port(String value) { return new MachineOutput(descriptor, amount, value, chanceOutput); }
    public MachineOutput chanceOutput(boolean value) { return new MachineOutput(descriptor, amount, port, value); }
}
