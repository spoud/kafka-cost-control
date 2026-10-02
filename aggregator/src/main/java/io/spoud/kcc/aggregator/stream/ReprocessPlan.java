package io.spoud.kcc.aggregator.stream;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/**
 * The pure decisions behind a reprocess, kept apart from the Kafka and DuckDB calls so they can be
 * tested: where the rebuild starts, and which topics are Kafka Streams' own.
 */
final class ReprocessPlan {

    private ReprocessPlan() {
    }

    /** The window boundary at or before {@code time}; windows are aligned to the epoch. */
    static Instant alignDown(Instant time, Duration window) {
        long size = window.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(time.toEpochMilli(), size) * size);
    }

    /** The window boundary at or after {@code time}. */
    static Instant alignUp(Instant time, Duration window) {
        Instant down = alignDown(time, window);
        return down.equals(time) ? down : down.plus(window);
    }

    /**
     * Where the rebuild starts: the requested time aligned down to a window boundary (or the epoch
     * for "everything"), but never before the first complete window the raw topics still hold.
     * Stored windows older than that are kept as they are, since they can't be rebuilt.
     *
     * @param requested       the requested start, or null for everything the raw topics hold
     * @param earliestRecords the timestamp of the first record still in each raw partition
     */
    static Instant effectiveStart(Instant requested, Duration window, Collection<Instant> earliestRecords) {
        Instant start = requested == null ? Instant.EPOCH : alignDown(requested, window);
        for (Instant earliest : earliestRecords) {
            Instant firstComplete = alignUp(earliest, window);
            if (firstComplete.isAfter(start)) {
                start = firstComplete;
            }
        }
        return start;
    }

    /**
     * Kafka Streams' internal topics for this application, which hold its window state and stream
     * time; the reset tool deletes the same ones.
     */
    static boolean isInternalTopic(String applicationId, String topic) {
        return topic.startsWith(applicationId + "-")
                && (topic.endsWith("-repartition") || topic.endsWith("-changelog"));
    }

    static Collection<String> internalTopics(String applicationId, Collection<String> topics) {
        return topics.stream().filter(topic -> isInternalTopic(applicationId, topic)).sorted().toList();
    }
}
