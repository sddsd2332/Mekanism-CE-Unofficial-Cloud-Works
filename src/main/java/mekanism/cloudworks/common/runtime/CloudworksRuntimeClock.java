package mekanism.cloudworks.common.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.NoSuchElementException;

/** Main-thread clock history; workers replay an immutable snapshot of admitted ticks. */
final class CloudworksRuntimeClock {
    private final Deque<Span> spans = new ArrayDeque<>();
    private long size;

    void record(int clock) {
        if (clock < 1 || clock > 24000) throw new IllegalArgumentException("Invalid cloud clock");
        Span last = spans.peekLast();
        if (last != null) {
            int step = last.ticks == 1 ? Math.floorMod(clock - last.clock, 24000) : last.step;
            if (step <= 1 && clock == advance(last.clock, step, last.ticks)) {
                spans.removeLast();
                spans.addLast(new Span(last.clock, step, last.ticks + 1));
                size++;
                return;
            }
        }
        spans.addLast(new Span(clock, 1, 1));
        size++;
    }

    Cursor snapshot() {
        return new Cursor(spans.toArray(new Span[0]));
    }

    long size() { return size; }
    int segments() { return spans.size(); }

    void consume(long ticks) {
        if (ticks < 0 || ticks > size) throw new IllegalArgumentException("Invalid clock acknowledgement");
        size -= ticks;
        while (ticks > 0) {
            Span first = spans.removeFirst();
            if (ticks < first.ticks) {
                spans.addFirst(new Span(advance(first.clock, first.step, ticks), first.step, first.ticks - ticks));
                return;
            }
            ticks -= first.ticks;
        }
    }

    private static int advance(int clock, int step, long ticks) {
        return (int) ((clock - 1L + ticks % 24000 * step) % 24000) + 1;
    }

    static final class Cursor {
        private final Span[] spans;
        private int index;
        private long offset;

        private Cursor(Span[] spans) {
            this.spans = spans;
        }

        int next() {
            if (index == spans.length) throw new NoSuchElementException("Cloud clock snapshot exhausted");
            Span span = spans[index];
            int clock = advance(span.clock, span.step, offset);
            if (++offset == span.ticks) {
                index++;
                offset = 0;
            }
            return clock;
        }
    }

    private static final class Span {
        final int clock, step;
        final long ticks;

        Span(int clock, int step, long ticks) {
            this.clock = clock;
            this.step = step;
            this.ticks = ticks;
        }
    }
}
