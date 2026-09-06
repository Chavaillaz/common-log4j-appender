package com.chavaillaz.appender;

import java.util.ArrayList;
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
     * The result of {@link #trim(List, int)}.
     *
     * @param kept      The given elements, with the oldest ones in excess of the cap removed (the same list
     *                  instance as given if nothing was discarded)
     * @param discarded The elements discarded to honor the cap, or {@code null} if none were
     * @param <T>       The type of element
     */
    public record Trimmed<T>(List<T> kept, @Nullable List<T> discarded) {

    }

    /**
     * Trims the given list (oldest first) down to at most {@code maxSize} elements, discarding the oldest
     * ones in excess so the most recently added elements are kept.
     *
     * @param elements The elements to trim, oldest first
     * @param maxSize  The maximum to keep, clamped to {@code [0, elements.size()]} so an unexpected value
     *                 (zero, negative, or larger than the list) degrades gracefully instead of making
     *                 {@code subList()} throw
     * @param <T>      The type of element
     * @return The trimmed list and whatever was discarded, if anything
     */
    public static <T> Trimmed<T> trim(List<T> elements, int maxSize) {
        // Clamped before the subtraction rather than after it: with maxSize far enough below zero (which a
        // caller is explicitly allowed to pass, meaning "keep nothing"), size - maxSize overflows int and
        // comes back negative, which the Math.max() below then reads as "nothing to discard" -- keeping
        // every element instead of discarding every one of them, the exact opposite of what was asked.
        int cap = Math.max(0, maxSize);
        int overflow = Math.min(elements.size(), Math.max(0, elements.size() - cap));
        if (overflow == 0) {
            return new Trimmed<>(elements, null);
        }
        List<T> discarded = List.copyOf(elements.subList(0, overflow));
        List<T> kept = new ArrayList<>(elements.subList(overflow, elements.size()));
        return new Trimmed<>(kept, discarded);
    }

}
