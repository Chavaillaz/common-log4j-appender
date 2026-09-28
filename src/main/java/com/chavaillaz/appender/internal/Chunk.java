package com.chavaillaz.appender.internal;

import java.util.Collections;
import java.util.List;

/**
 * Elements taken out of the pending batch of a {@link Batcher} together, with the failure streak each of
 * them has so far: a request handed to the transport, or what a failed one puts back. Compared by identity,
 * two requests being free to carry equal elements.
 *
 * @param <T> The type of element
 */
final class Chunk<T> {

    private final List<T> elements;
    private final List<FailureRun> runs;

    /**
     * Creates a chunk of the given elements.
     *
     * @param elements The elements, in order, kept as an unmodifiable view: what the transport is handed is
     *                 what gets retried or reported if it fails
     * @param runs     The failure streaks of these elements, run by run (see {@link FailureRun})
     */
    Chunk(List<T> elements, List<FailureRun> runs) {
        this.elements = Collections.unmodifiableList(elements);
        this.runs = runs;
    }

    /**
     * The elements of this chunk.
     *
     * @return The elements, in order, as an unmodifiable list
     */
    List<T> elements() {
        return elements;
    }

    /**
     * The failure streaks of the elements of this chunk.
     *
     * @return The failure streaks, run by run
     */
    List<FailureRun> runs() {
        return runs;
    }

    /**
     * The number of elements of this chunk.
     *
     * @return The number of elements
     */
    int size() {
        return elements.size();
    }

}
