package mekanism.cloudworks.common.runtime;

import java.util.Arrays;

/**
 * Hot machine-node storage for one player's Cloudworks Runtime.
 *
 * <p>Only primitive arrays are used by the tick path. Persistent UUIDs, item
 * stacks and NBT stay in the cold projection layer.</p>
 */
public final class CloudworksRuntimeMachineStore {
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
    public static final byte WRONG_DIMENSION = 14;
    public static final byte INSUFFICIENT_INPUT = 15;
    public static final byte NO_MATCHING_RECIPE = 16;
    public static final byte MISSING_INPUT = 17;
    public static final byte NO_MATCHING_FUEL = 18;
    public static final byte INSUFFICIENT_FUEL = 19;
    public static final byte WAITING_TEMPLATE = 20;
    public static final byte DECAYING = 21;
    public static final byte DECAY_BLOCKED = 22;

    private int size;
    private long version;
    private long[] versions = new long[16];
    private int[] machineType = new int[16];
    private int[] line = new int[16], input = new int[16], output = new int[16], configuration = new int[16];
    private long[] machineCount = new long[16];
    private boolean[] active = new boolean[16];
    private int[] progress = new int[16];
    private int[] duration = new int[16];
    private byte[] state = new byte[16];

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
        active[index] = enabled && count > 0;
        progress[index] = 0;
        duration[index] = totalTicks;
        state[index] = active[index] ? READY : OFF;
        line[index] = input[index] = output[index] = -1;
        configuration[index] = -1;
        return index;
    }

    public int machineType(int index) { return machineType[check(index)]; }
    public long machineCount(int index) { return machineCount[check(index)]; }
    public boolean active(int index) { return active[check(index)]; }
    public int progress(int index) { return progress[check(index)]; }
    public int duration(int index) { return duration[check(index)]; }
    public byte state(int index) { return state[check(index)]; }
    public int line(int index) { return line[check(index)]; }
    public int input(int index) { return input[check(index)]; }
    public int output(int index) { return output[check(index)]; }
    public int configuration(int index) { return configuration[check(index)]; }
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
        if (state[checked] != nextState) versions[checked] = ++version;
        state[checked] = nextState;
    }

    public void clear() {
        size = 0;
        version = 0;
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
        active = Arrays.copyOf(active, next);
        progress = Arrays.copyOf(progress, next);
        duration = Arrays.copyOf(duration, next);
        state = Arrays.copyOf(state, next);
    }
}
