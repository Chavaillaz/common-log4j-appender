package com.chavaillaz.appender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The "keep the most recent elements, discard the oldest past a cap" policy bounding the pending batch of a
 * {@link Batcher}, which a merge can push over its cap by more than one element at a time. The queue of
 * {@link com.chavaillaz.appender.log4j.AbstractLogDeliveryAppender} follows the same policy one event at a
 * time, through its own constant-time {@code poll()} and {@code offer()}.
 */
@UtilityClass
public class BoundedTrim {

    /**
     * Trims the given queue (oldest at its head) down to at most {@code maxSize} elements, discarding the
     * oldest ones in excess.
     * <p>
     * Polls them from the head, in constant time per element discarded for a queue such as an
     * {@link java.util.ArrayDeque}: once the cap is reached, this runs for every addition against a queue
     * already holding {@code maxSize} elements, which is precisely the sustained overload the cap exists for.
     *
     * @param elements The elements to trim, oldest first, modified in place and so necessarily mutable
     * @param maxSize  The maximum to keep, clamped to {@code [0, elements.size()]}, so that zero, a negative
     *                 value or one larger than the queue is tolerated
     * @param <T>      The type of element
     * @return The elements discarded, oldest first, as an unmodifiable list, or {@code null} if none were
     */
    public static <T> @Nullable List<T> trim(Queue<T> elements, int maxSize) {
        // Clamped before the subtraction: a cap far enough below zero overflows size - maxSize into a negative
        // value, which would keep every element instead of discarding them all
        int overflow = elements.size() - Math.max(0, maxSize);
        if (overflow <= 0) {
            return null;
        }
        List<T> discarded = new ArrayList<>(overflow);
        for (int i = 0; i < overflow; i++) {
            discarded.add(elements.poll());
        }
        return Collections.unmodifiableList(discarded);
    }

}
