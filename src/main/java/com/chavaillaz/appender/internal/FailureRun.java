package com.chavaillaz.appender.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Consecutive elements sharing the same failure streak, the unit a {@link Batcher} tracks streaks by: a whole
 * chunk failing together shares one, where a streak per element would cost as many.
 * <p>
 * A list of runs describes the elements of a list from its front, run after run, the elements past its last
 * run having never failed.
 *
 * @param streak The number of consecutive failed attempts of each of these elements
 * @param count  The number of elements sharing it, at least one
 */
record FailureRun(int streak, int count) {

    /**
     * Appends the given number of elements with the given streak to a list of runs, extending its last run
     * when they share it.
     *
     * @param runs   The runs to append to
     * @param streak The failure streak of the elements
     * @param count  The number of elements, at least one
     */
    static void append(List<FailureRun> runs, int streak, int count) {
        int last = runs.size() - 1;
        if (last >= 0 && runs.get(last).streak() == streak) {
            runs.set(last, new FailureRun(streak, runs.get(last).count() + count));
        } else {
            runs.add(new FailureRun(streak, count));
        }
    }

    /**
     * The runs describing the elements of a list from index {@code from} to index {@code to}, cut out of the
     * runs describing it from its front, the elements past them counting a streak of zero.
     *
     * @param runs The runs describing the list from its front
     * @param from The index of the first element described, inclusive
     * @param to   The index of the last element described, exclusive
     * @return The runs describing exactly these elements
     */
    static List<FailureRun> slice(List<FailureRun> runs, int from, int to) {
        List<FailureRun> slice = new ArrayList<>();
        int start = 0;
        for (FailureRun run : runs) {
            int overlap = Math.min(start + run.count(), to) - Math.max(start, from);
            if (overlap > 0) {
                append(slice, run.streak(), overlap);
            }
            start += run.count();
            if (start >= to) {
                break;
            }
        }
        int neverFailed = to - Math.max(start, from);
        if (neverFailed > 0) {
            append(slice, 0, neverFailed);
        }
        return slice;
    }

    /**
     * Reads the failure streak of each element in turn out of the runs describing them, the elements past the
     * last run counting none, so that runs not describing every element exactly cannot lose any of them.
     */
    static final class Streaks {

        private final Iterator<FailureRun> runs;
        private int streak;
        private int left;

        /**
         * Starts reading from the first element the given runs describe.
         *
         * @param runs The runs describing the elements from their front
         */
        Streaks(List<FailureRun> runs) {
            this.runs = runs.iterator();
        }

        /**
         * The failure streak of the next element.
         *
         * @return The number of consecutive failed attempts of the next element, {@code 0} past the last run
         */
        int next() {
            while (left == 0 && runs.hasNext()) {
                FailureRun run = runs.next();
                streak = run.streak();
                left = run.count();
            }
            if (left == 0) {
                return 0;
            }
            left--;
            return streak;
        }

    }

}
