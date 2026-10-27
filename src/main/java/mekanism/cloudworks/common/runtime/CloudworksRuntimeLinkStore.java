package mekanism.cloudworks.common.runtime;

import java.util.Arrays;

/** Integer endpoints and compiled port ids, in topology/priority order. */
public final class CloudworksRuntimeLinkStore {
    public static final byte CHANNEL_INPUT = 0, CHANNEL_TO_MACHINE = 1, MACHINE_TO_MACHINE = 2,
            MACHINE_TO_CHANNEL = 3, CHANNEL_OUTPUT = 4, HEAT = 5, AUXILIARY_INPUT = 6;
    private int size;
    private long version;
    private int[] source = new int[32], target = new int[32], sourcePort = new int[32],
            targetPort = new int[32], line = new int[32];
    private int[][] filters = new int[32][];
    private byte[] phase = new byte[32];
    private double[] ratio = new double[32], remainder = new double[32];

    public int size() { return size; }
    public long version() { return version; }
    public int source(int index) { return source[index]; }
    public int target(int index) { return target[index]; }
    public int sourcePort(int index) { return sourcePort[index]; }
    public int targetPort(int index) { return targetPort[index]; }
    public int line(int index) { return line[index]; }
    public byte phase(int index) { return phase[index]; }
    public double remainder(int index) { return remainder[index]; }
    public double ratio(int index) { return ratio[index]; }

    public int add(int lineIndex, byte stage, int from, int to, int fromPort, int toPort,
            int[] resourceFilters, double fraction, double carried) {
        if (lineIndex < 0 || stage < CHANNEL_INPUT || stage > AUXILIARY_INPUT || from < 0 || to < 0
                || fromPort < 0 || toPort < 0 || resourceFilters == null || !Double.isFinite(fraction)
                || fraction < 0 || fraction > 1 || !Double.isFinite(carried) || carried < 0 || carried >= 1)
            throw new IllegalArgumentException("Invalid runtime link");
        int[] sorted = resourceFilters.clone();
        Arrays.sort(sorted);
        for (int i = 0; i < sorted.length; i++)
            if (sorted[i] < 0 || i > 0 && sorted[i] == sorted[i - 1])
                throw new IllegalArgumentException("Invalid or duplicate runtime filter");
        ensure(size + 1);
        int index = size++;
        line[index] = lineIndex;
        phase[index] = stage;
        source[index] = from;
        target[index] = to;
        sourcePort[index] = fromPort;
        targetPort[index] = toPort;
        filters[index] = sorted;
        ratio[index] = fraction;
        remainder[index] = carried;
        return index;
    }

    public boolean accepts(int index, int resource) {
        return filters[index].length == 0 || Arrays.binarySearch(filters[index], resource) >= 0;
    }

    public long allowance(int index, long available) {
        if (available <= 0 || ratio[index] <= 0) return 0;
        if (ratio[index] >= 1) return available;
        double quota = available * ratio[index] + remainder[index];
        double whole = Math.floor(quota);
        double next = quota - whole;
        if (remainder[index] != next) version++;
        remainder[index] = next;
        return Math.max(0, Math.min(available, (long) whole));
    }

    public void clear() { Arrays.fill(filters, 0, size, null); size = 0; version = 0; }

    private void ensure(int required) {
        if (required <= source.length) return;
        int count = Math.max(required, source.length * 2);
        source = Arrays.copyOf(source, count);
        target = Arrays.copyOf(target, count);
        sourcePort = Arrays.copyOf(sourcePort, count);
        targetPort = Arrays.copyOf(targetPort, count);
        filters = Arrays.copyOf(filters, count);
        line = Arrays.copyOf(line, count);
        phase = Arrays.copyOf(phase, count);
        ratio = Arrays.copyOf(ratio, count);
        remainder = Arrays.copyOf(remainder, count);
    }
}
