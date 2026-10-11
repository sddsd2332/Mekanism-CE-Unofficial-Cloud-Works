package mekanism.cloudworks.common.runtime;

import java.util.Arrays;

/** Cloud, warehouse-relay and per-line energy state owned by one Runtime. */
public final class CloudworksRuntimeEnergyStore {
    private double cloudEnergy;
    private double cloudCapacity;
    private double receivingEnergy;
    private double sendingEnergy;
    private double receivingCapacity, sendingCapacity;
    private boolean unlimited;
    private int size;
    private long version;
    private double[] lineEnergy = new double[8];
    private double[] lineCapacity = new double[8];

    public double cloudEnergy() { return cloudEnergy; }
    public double cloudCapacity() { return cloudCapacity; }
    public double receivingEnergy() { return receivingEnergy; }
    public double sendingEnergy() { return sendingEnergy; }
    public double receivingCapacity() { return receivingCapacity; }
    public double sendingCapacity() { return sendingCapacity; }
    public boolean unlimited() { return unlimited; }
    public long version() { return version; }

    public void setCloud(double energy, double capacity) {
        setCloud(energy, capacity, false);
    }

    public void setCloud(double energy, double capacity, boolean infinite) {
        if (!Double.isFinite(energy) || Double.isNaN(capacity) || !infinite && !Double.isFinite(capacity)
                || energy < 0 || capacity < 0)
            throw new IllegalArgumentException("Invalid runtime cloud energy");
        double nextEnergy = infinite ? 0 : Math.min(energy, capacity);
        if (unlimited != infinite || cloudCapacity != capacity || cloudEnergy != nextEnergy) version++;
        unlimited = infinite;
        cloudCapacity = capacity;
        cloudEnergy = nextEnergy;
    }

    public void setRelays(double receiving, double sending, double capacity) {
        setRelays(receiving, sending, capacity, capacity);
    }

    public void setRelays(double receiving, double sending, double receivingCap, double sendingCap) {
        if (!Double.isFinite(receiving) || !Double.isFinite(sending) || !Double.isFinite(receivingCap)
                || !Double.isFinite(sendingCap) || receiving < 0 || sending < 0 || receivingCap < 0 || sendingCap < 0)
            throw new IllegalArgumentException("Invalid runtime relay energy");
        double nextReceiving = Math.min(receiving, receivingCap), nextSending = Math.min(sending, sendingCap);
        if (receivingCapacity != receivingCap || sendingCapacity != sendingCap
                || receivingEnergy != nextReceiving || sendingEnergy != nextSending) version++;
        receivingCapacity = receivingCap;
        sendingCapacity = sendingCap;
        receivingEnergy = nextReceiving;
        sendingEnergy = nextSending;
    }

    public int addLine(double energy, double capacity) {
        if (!Double.isFinite(energy) || !Double.isFinite(capacity) || energy < 0 || capacity < 0)
            throw new IllegalArgumentException("Invalid runtime line energy");
        int index = size;
        ensure(index + 1);
        lineEnergy[index] = Math.max(0, Math.min(energy, capacity));
        lineCapacity[index] = Math.max(0, capacity);
        size++;
        return index;
    }

    public int lineCount() {
        return size;
    }

    public double lineEnergy(int index) { return lineEnergy[check(index)]; }
    public double lineCapacity(int index) { return lineCapacity[check(index)]; }

    public void setLineEnergy(int index, double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid runtime line energy");
        int checked = check(index);
        double next = Math.min(value, lineCapacity[checked]);
        if (lineEnergy[checked] != next) version++;
        lineEnergy[checked] = next;
    }

    public void clear() {
        Arrays.fill(lineEnergy, 0, size, 0);
        Arrays.fill(lineCapacity, 0, size, 0);
        size = 0;
        cloudEnergy = cloudCapacity = receivingEnergy = sendingEnergy = receivingCapacity = sendingCapacity = 0;
        unlimited = false;
        version = 0;
    }

    private int check(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("Runtime energy line index");
        return index;
    }

    private void ensure(int required) {
        if (required <= lineEnergy.length) return;
        int next = lineEnergy.length;
        while (next < required) next *= 2;
        lineEnergy = Arrays.copyOf(lineEnergy, next);
        lineCapacity = Arrays.copyOf(lineCapacity, next);
    }
}
