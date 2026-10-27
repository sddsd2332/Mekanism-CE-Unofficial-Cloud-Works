package mekanism.cloudworks.common.runtime;

import java.util.Arrays;
import java.util.function.IntToLongFunction;

/** Immutable paged versions. Only pages containing changed stamps are copied. */
final class CloudworksRuntimeVersionSnapshot {
    private static final int SHIFT = 8;
    private static final int PAGE = 1 << SHIFT;
    private final long[][] pages;
    private final int size;

    private CloudworksRuntimeVersionSnapshot(int size, long[][] pages) {
        this.size = size; this.pages = pages;
    }

    static CloudworksRuntimeVersionSnapshot capture(int size, IntToLongFunction reader,
                                                    CloudworksRuntimeVersionSnapshot previous) {
        long[][] pages = new long[(size + PAGE - 1) >>> SHIFT][];
        for (int page = 0; page < pages.length; page++) {
            int start = page << SHIFT, count = Math.min(PAGE, size - start);
            long[] old = previous != null && page < previous.pages.length
                    && previous.pages[page].length == count ? previous.pages[page] : null;
            long[] next = old;
            for (int offset = 0; offset < count; offset++) {
                long value = reader.applyAsLong(start + offset);
                if (old == null || value != old[offset]) {
                    if (next == old) next = old == null ? new long[count] : Arrays.copyOf(old, count);
                    next[offset] = value;
                }
            }
            pages[page] = next;
        }
        return new CloudworksRuntimeVersionSnapshot(size, pages);
    }

    long get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return pages[index >>> SHIFT][index & (PAGE - 1)];
    }

    int sharedPages(CloudworksRuntimeVersionSnapshot other) {
        int shared = 0;
        for (int page = 0; page < Math.min(pages.length, other.pages.length); page++)
            if (pages[page] == other.pages[page]) shared++;
        return shared;
    }
}
