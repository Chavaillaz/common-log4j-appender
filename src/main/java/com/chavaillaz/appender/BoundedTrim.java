package com.chavaillaz.appender;

import java.util.List;

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
     * Trims the given list (oldest first) down to at most {@code maxSize} elements, discarding the oldest
     * ones in excess.
     * <p>
     * Trims in place, with one array copy within the list, rather than building a new list: once the cap is
     * reached, this runs for every addition against a list already holding {@code maxSize} elements, which is
     * precisely the sustained overload the cap exists for.
     *
     * @param elements The elements to trim, oldest first, modified in place and so necessarily mutable
     * @param maxSize  The maximum to keep, clamped to {@code [0, elements.size()]}, so that zero, a negative
     *                 value or one larger than the list is tolerated
     * @param <T>      The type of element
     * @return The elements discarded, as an unmodifiable list, or {@code null} if none were
     */
    public static <T> @Nullable List<T> trim(List<T> elements, int maxSize) {
        // Clamped before the subtraction: a cap far enough below zero overflows size - maxSize into a negative
        // value, which would keep every element instead of discarding them all
        int cap = Math.max(0, maxSize);
        int overflow = Math.min(elements.size(), Math.max(0, elements.size() - cap));
        if (overflow == 0) {
            return null;
        }
        List<T> excess = elements.subList(0, overflow);
        // Copied before clearing, a sublist being a view of the range it covers
        List<T> discarded = List.copyOf(excess);
        excess.clear();
        return discarded;
    }

}
