package com.chavaillaz.appender;

import java.util.List;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The "keep the most recently added elements, discard the oldest past a cap" policy applied at both
 * backpressure points of the delivery pipeline: {@link com.chavaillaz.appender.log4j.AbstractBatchLogDelivery}'s
 * pending batch, which calls {@link #trim(List, int)} directly since a bulk merge (retried documents put
 * back after a failure) can push it over the cap by more than one element at a time, and
 * {@link com.chavaillaz.appender.log4j.AbstractLogDeliveryAppender}'s pending event queue, which only ever
 * needs to evict a single oldest element to make room for the one new one it just received, and does so
 * directly through its queue's own O(1) {@code poll()}/{@code offer()} rather than through this class, to
 * avoid materializing a potentially large queue on every single eviction under sustained overload.
 */
@UtilityClass
public class BoundedTrim {

    /**
     * Trims the given list (oldest first) down to at most {@code maxSize} elements, discarding the oldest
     * ones in excess so the most recently added elements are kept.
     * <p>
     * Trims in place rather than handing back a new list, which is what makes it affordable on the path it
     * sits on: once the cap is actually reached, this runs for every batch of incoming elements against a
     * list already holding {@code maxSize} of them (ten thousand by default). Rebuilding it each time
     * allocates and copies the whole thing per call, precisely under the sustained overload the cap exists
     * for -- so the mechanism bounding memory would be the one filling it with garbage. Removing the excess
     * from the front is one array copy within the list already there, and allocates nothing beyond what is
     * actually discarded.
     *
     * @param elements The elements to trim, oldest first, modified in place and so necessarily mutable
     * @param maxSize  The maximum to keep, clamped to {@code [0, elements.size()]} so an unexpected value
     *                 (zero, negative, or larger than the list) degrades gracefully instead of making
     *                 {@code subList()} throw
     * @param <T>      The type of element
     * @return The elements discarded, as an unmodifiable list, or {@code null} if none were
     */
    public static <T> @Nullable List<T> trim(List<T> elements, int maxSize) {
        // Clamped before the subtraction rather than after it: with maxSize far enough below zero (which a
        // caller is explicitly allowed to pass, meaning "keep nothing"), size - maxSize overflows int and
        // comes back negative, which the Math.max() below then reads as "nothing to discard" -- keeping
        // every element instead of discarding every one of them, the exact opposite of what was asked.
        int cap = Math.max(0, maxSize);
        int overflow = Math.min(elements.size(), Math.max(0, elements.size() - cap));
        if (overflow == 0) {
            return null;
        }
        List<T> excess = elements.subList(0, overflow);
        // Copied before clearing, not after: a sublist is a view of the range it covers, emptied along with
        // it. The copy is what travels to the listener anyway, whose contract is an unmodifiable list.
        List<T> discarded = List.copyOf(excess);
        excess.clear();
        return discarded;
    }

}
