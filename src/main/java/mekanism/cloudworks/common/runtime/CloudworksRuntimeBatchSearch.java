package mekanism.cloudworks.common.runtime;

import java.util.function.IntPredicate;

/**
 * Finds the largest admissible batch using a prior success only as a probe hint.
 * The predicate must be downward-closed, and every probe is evaluated against current state.
 */
final class CloudworksRuntimeBatchSearch {
    private CloudworksRuntimeBatchSearch() {}

    static int maximum(int maximum, int hint, IntPredicate admissible) {
        if (maximum < 1) return 0;
        if (hint <= 0) {
            int low = 0, high = maximum;
            while (low < high) {
                int candidate = low + (int) (((long) high - low + 1) / 2);
                if (admissible.test(candidate)) low = candidate;
                else high = candidate - 1;
            }
            return low;
        }
        int pivot = Math.max(1, Math.min(maximum, hint));
        int best;
        int low;
        int high;
        if (admissible.test(pivot)) {
            best = pivot;
            if (pivot == maximum) return best;
            int next = pivot + 1;
            if (!admissible.test(next)) return best;
            best = next;
            if (next == maximum || admissible.test(maximum)) return next == maximum ? next : maximum;
            low = next + 1;
            high = maximum - 1;
        } else {
            if (pivot == 1 || !admissible.test(1)) return 0;
            best = 1;
            low = 2;
            high = pivot - 1;
        }
        while (low <= high) {
            int candidate = low + (high - low) / 2;
            if (admissible.test(candidate)) {
                best = candidate;
                low = candidate + 1;
            } else {
                high = candidate - 1;
            }
        }
        return best;
    }

    /**
     * Uses a cheap monotone estimate for the search, then validates the exact boundary.
     * If rounding or another estimate difference shifts that boundary, the exact predicate
     * gets a full binary search so the fast path cannot change admission semantics.
     */
    static int maximumVerified(int maximum, int hint, IntPredicate estimate, IntPredicate exact) {
        int result = maximum(maximum, hint, estimate);
        if (result > 0 && !exact.test(result)
                || result < maximum && exact.test(result + 1)) {
            return maximum(maximum, 0, exact);
        }
        return result;
    }

    /**
     * Accepts a closed-form estimate only when the exact predicate confirms both
     * sides of the boundary; otherwise falls back to the established exact search.
     */
    static int maximumVerifiedCandidate(int maximum, int candidate, IntPredicate exact) {
        return maximumVerifiedCandidate(maximum, 0, candidate, exact);
    }

    static int maximumVerifiedCandidate(int maximum, int hint, int candidate, IntPredicate exact) {
        if (maximum < 1) return 0;
        if (candidate < 0 || candidate > maximum
                || candidate > 0 && !exact.test(candidate)
                || candidate < maximum && exact.test(candidate + 1)) {
            return maximum(maximum, hint, exact);
        }
        return candidate;
    }
}
