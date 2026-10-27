package mekanism.cloudworks.common.runtime;

import java.util.Arrays;

/**
 * Hot machine-node storage for one player's Cloudworks Runtime.
 *
 * <p>Only primitive arrays are used by the tick path. Persistent UUIDs, item
 * stacks and NBT stay in the cold projection layer.</p>
 */
public final class CloudworksRuntimeMachineStore {
    private static final double[] NO_REMAINDERS = new double[0];
    public static final byte OFF = 0;
    public static final byte READY = 1;
    public static final byte RUNNING = 2;
    public static final byte WAITING_INPUT = 3;
    public static final byte WAITING_OUTPUT = 4;
    public static final byte WAITING_POWER = 5;
    public static final byte GENERATING = 6;
    public static final byte WAITING_FUEL = 7;
    public static final byte UNSUPPORTED_RECIPE = 8;
    public static final byte WAITING_SUNLIGHT = 9;
    public static final byte WAITING_WIND = 10;
    public static final byte WAITING_HEAT = 11;
    public static final byte NO_GENERATION = 12;
    public static final byte NO_RECIPE = 13;
    public static final byte INSUFFICIENT_INPUT = 14;
    public static final byte NO_MATCHING_RECIPE = 15;
    public static final byte MISSING_INPUT = 16;
    public static final byte NO_MATCHING_FUEL = 17;
    public static final byte INSUFFICIENT_FUEL = 18;
    public static final byte WAITING_TEMPLATE = 19;
    public static final byte DECAYING = 20;
    public static final byte DECAY_BLOCKED = 21;
    public static final byte WAITING_TIME = 22;
    public static final byte WAITING_ENVIRONMENT = 23;

    private int size;
    private CloudworksRuntimeOperationStore operations;
    void bindOperations(CloudworksRuntimeOperationStore store) { operations = store; }
    private long version;
    private long[] versions = new long[16];
    private int[] machineType = new int[16];
    private int[] line = new int[16], input = new int[16], output = new int[16], configuration = new int[16];
    private long[] machineCount = new long[16];
    private long[] outputSequence = new long[16];
    private boolean[] active = new boolean[16];
    private int[] progress = new int[16];
    private int[] duration = new int[16];
    private byte[] state = new byte[16];
    private double[][] generationRemainders = new double[16][];
    private double[] processCredit = new double[16];
    public double processCredit(int node) { return processCredit[check(node)]; }
    public void processCredit(int node, double value) {
        if (!Double.isFinite(value) || value < 0 || value >= Long.MAX_VALUE) throw new IllegalArgumentException("Invalid runtime processing credit");
        if (processCredit[check(node)] != value) { processCredit[node] = value; versions[node] = ++version; }
    }

    public int size() {
        return size;
    }
    public long version() { return version; }
    public long version(int index) { return versions[check(index)]; }

    public int add(int type, long count, boolean enabled, int totalTicks) {
        if (count < 0 || totalTicks <= 0)
            throw new IllegalArgumentException("Invalid runtime machine");
        ensure(size + 1);
        int index = size++;
        versions[index] = ++version;
        machineType[index] = type;
        machineCount[index] = count;
        outputSequence[index] = 0;
        processCredit[index] = 0D;
        active[index] = enabled && count > 0;
        progress[index] = 0;
        duration[index] = totalTicks;
        state[index] = active[index] ? READY : OFF;
        line[index] = input[index] = output[index] = -1;
        configuration[index] = -1;
        generationRemainders[index] = NO_REMAINDERS;
        return index;
    }

    public int machineType(int index) { return machineType[check(index)]; }
    public long machineCount(int index) { return machineCount[check(index)]; }
    public long outputSequence(int index) { return outputSequence[check(index)]; }
    public void restoreOutputSequence(int index, long sequence) {
        if (sequence < 0) throw new IllegalArgumentException("Invalid output sequence");
        outputSequence[check(index)] = sequence;
    }
    public void advanceOutputSequence(int index) {
        int checked = check(index);
        outputSequence[checked] = Math.addExact(outputSequence[checked], 1);
        versions[checked] = ++version;
    }
    void claimOutputSequence(int index, long next) {
        int checked = check(index);
        if (next > outputSequence[checked]) { outputSequence[checked] = next; versions[checked] = ++version; }
    }
    public boolean active(int index) { return active[check(index)]; }
    public int progress(int index) {
        int i = check(index);
        CloudworksRuntimeBatch batch = operations == null ? null : operations.current(i);
        return batch == null ? progress[i] : batch.progress;
    }
    public int duration(int index) {
        int i = check(index);
        CloudworksRuntimeBatch batch = operations == null ? null : operations.current(i);
        return batch == null ? duration[i] : batch.duration;
    }
    public byte state(int index) { return state[check(index)]; }
    public int line(int index) { return line[check(index)]; }
    public int input(int index) { return input[check(index)]; }
    public int output(int index) { return output[check(index)]; }
    public int configuration(int index) { return configuration[check(index)]; }
    void configureGenerationRemainders(int index, double[] values) {
        generationRemainders[check(index)] = values.length == 0 ? NO_REMAINDERS : values.clone();
    }
    double generationRemainder(int index, int output) { return generationRemainders[check(index)][output]; }
    void generationRemainder(int index, int output, double value) {
        if (!Double.isFinite(value) || value < 0 || value >= 1) throw new IllegalArgumentException("Invalid generation remainder");
        if (generationRemainders[check(index)][output] != value) {
            generationRemainders[index][output] = value;
            versions[index] = ++version;
        }
    }
    public void bindConfiguration(int index, int inventory) {
        if (inventory < 0) throw new IllegalArgumentException("Invalid configuration inventory");
        configuration[check(index)] = inventory;
    }

    public void bind(int index, int lineIndex, int inputInventory, int outputInventory) {
        int checked = check(index);
        if (lineIndex < 0 || inputInventory < 0 || outputInventory < 0)
            throw new IllegalArgumentException("Invalid runtime machine binding");
        line[checked] = lineIndex;
        input[checked] = inputInventory;
        output[checked] = outputInventory;
    }

    public void setProgress(int index, int value, int totalTicks) {
        if (value < 0 || totalTicks <= 0 || value >= totalTicks)
            throw new IllegalArgumentException("Invalid runtime machine progress");
        int checked = check(index);
        CloudworksRuntimeBatch batch = operations == null ? null : operations.current(checked);
        if (batch != null) {
            if (batch.progress != value || batch.duration != totalTicks) {
                batch.progress = value; batch.duration = totalTicks;
                operations.changed(checked); versions[checked] = ++version;
            }
            return;
        }
        if (progress[checked] != value || duration[checked] != totalTicks) versions[checked] = ++version;
        progress[checked] = value;
        duration[checked] = totalTicks;
    }

    public void setActive(int index, boolean enabled) {
        int checked = check(index);
        if (active[checked] != enabled || !enabled && state[checked] != OFF) versions[checked] = ++version;
        active[checked] = enabled;
        if (!enabled) state[checked] = OFF;
    }

    public void setState(int index, byte nextState) {
        int checked = check(index);
        CloudworksRuntimeBatch batch = operations == null ? null : operations.current(checked);
        if (batch != null && batch.state != nextState) {
            batch.state = nextState;
            operations.changed(checked);
        }
        setAggregateState(checked, nextState);
    }

    /** Update the node's display state without rewriting its primary batch state. */
    void setAggregateState(int index, byte nextState) {
        int checked = check(index);
        if (state[checked] != nextState) versions[checked] = ++version;
        state[checked] = nextState;
    }

    public void clear() {
        size = 0;
        version = 0;
        operations = null;
    }

    private int check(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("runtime machine index");
        return index;
    }

    private void ensure(int required) {
        if (required <= machineType.length) return;
        int next = machineType.length;
        while (next < required) next = Math.max(next + 1, next * 2);
        machineType = Arrays.copyOf(machineType, next);
        versions = Arrays.copyOf(versions, next);
        line = Arrays.copyOf(line, next);
        input = Arrays.copyOf(input, next);
        output = Arrays.copyOf(output, next);
        configuration = Arrays.copyOf(configuration, next);
        machineCount = Arrays.copyOf(machineCount, next);
        outputSequence = Arrays.copyOf(outputSequence, next);
        active = Arrays.copyOf(active, next);
        progress = Arrays.copyOf(progress, next);
        duration = Arrays.copyOf(duration, next);
        state = Arrays.copyOf(state, next);
        generationRemainders = Arrays.copyOf(generationRemainders, next);
        processCredit = Arrays.copyOf(processCredit, next);
    }
}
