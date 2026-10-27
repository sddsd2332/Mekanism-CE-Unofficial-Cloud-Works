package mekanism.cloudworks.common.runtime;

import java.util.Arrays;
import java.util.function.IntFunction;

/** Immutable pages of immutable values; never retains its predecessor or the mutable reader. */
final class CloudworksRuntimeObjectSnapshot<T> {
    private static final int SHIFT = 8, PAGE = 1 << SHIFT;
    private final Object[][] pages;
    private final int size;

    private CloudworksRuntimeObjectSnapshot(int size, Object[][] pages) {
        this.size = size;
        this.pages = pages;
    }

    static <T> CloudworksRuntimeObjectSnapshot<T> capture(int size, IntFunction<T> reader,
            CloudworksRuntimeObjectSnapshot<T> previous) {
        Object[][] pages = new Object[(size + PAGE - 1) >>> SHIFT][];
        for (int page = 0; page < pages.length; page++) {
            int start = page << SHIFT, count = Math.min(PAGE, size - start);
            Object[] old = previous != null && page < previous.pages.length
                    && previous.pages[page].length == count ? previous.pages[page] : null;
            Object[] next = old;
            for (int offset = 0; offset < count; offset++) {
                T value = reader.apply(start + offset);
                if (old == null || value != old[offset]) {
                    if (next == old) next = old == null ? new Object[count] : Arrays.copyOf(old, count);
                    next[offset] = value;
                }
            }
            pages[page] = next;
        }
        return new CloudworksRuntimeObjectSnapshot<>(size, pages);
    }

    @SuppressWarnings("unchecked")
    T get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return (T) pages[index >>> SHIFT][index & (PAGE - 1)];
    }

    int size() { return size; }
}
