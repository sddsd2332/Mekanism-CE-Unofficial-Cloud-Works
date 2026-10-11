package mekanism.cloudworks.common.runtime;

import java.util.Arrays;

/** Channel buffers and replenishment targets share the ledger but have distinct inventory ids. */
public final class CloudworksRuntimeChannelStore {
    public static final byte RECEIVE_INPUT = 0, TRANSFER_INPUT = 1, TRANSFER_OUTPUT = 2, SEND_OUTPUT = 3;
    private int size;
    private int[] line = new int[16], inventory = new int[16], manifest = new int[16];
    private byte[] mode = new byte[16];
    private boolean[] enabled = new boolean[16], connected = new boolean[16];
    private long[] capacity = new long[16];

    public int size() { return size; }
    public int line(int index) { return line[index]; }
    public int inventory(int index) { return inventory[index]; }
    public int manifest(int index) { return manifest[index]; }
    public byte mode(int index) { return mode[index]; }
    public boolean enabled(int index) { return enabled[index]; }
    public boolean connected(int index) { return connected[index]; }
    public long capacity(int index) { return capacity[index]; }
    public boolean input(int index) { return mode[index] <= TRANSFER_INPUT; }
    public boolean transfer(int index) { return mode[index] == TRANSFER_INPUT || mode[index] == TRANSFER_OUTPUT; }

    public int add(int lineIndex, byte kind, boolean active, long room, int buffer, int manifestInventory) {
        if (lineIndex < 0 || kind < RECEIVE_INPUT || kind > SEND_OUTPUT || room < 0
                || buffer < 0 || manifestInventory < 0)
            throw new IllegalArgumentException("Invalid runtime channel");
        ensure(size + 1);
        int index = size++;
        line[index] = lineIndex;
        mode[index] = kind;
        enabled[index] = active;
        connected[index] = false;
        capacity[index] = room;
        inventory[index] = buffer;
        manifest[index] = manifestInventory;
        return index;
    }

    public void setConnected(int index, boolean value) { connected[index] = value; }
    public void clear() { size = 0; }

    private void ensure(int required) {
        if (required <= line.length) return;
        int count = Math.max(required, line.length * 2);
        line = Arrays.copyOf(line, count);
        inventory = Arrays.copyOf(inventory, count);
        manifest = Arrays.copyOf(manifest, count);
        mode = Arrays.copyOf(mode, count);
        enabled = Arrays.copyOf(enabled, count);
        connected = Arrays.copyOf(connected, count);
        capacity = Arrays.copyOf(capacity, count);
    }
}
