package io.spoud.kcc.aggregator.repository;

import io.quarkus.logging.Log;
import org.apache.kafka.streams.errors.InvalidStateStoreException;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * A Kafka Streams store can be briefly unavailable even after it was obtained, e.g. while a
 * restart restores it or a rebalance moves it. Reads behind the API retry for a few seconds
 * instead of failing the request with "System error".
 */
final class StoreReads {
    static final int ATTEMPTS = 20;
    static final Duration PAUSE = Duration.ofMillis(250);

    private StoreReads() {
    }

    static <T> T retrying(String what, Supplier<T> read) {
        InvalidStateStoreException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return read.get();
            } catch (InvalidStateStoreException e) {
                last = e;
                Log.debugf("%s not readable yet (attempt %d): %s", what, attempt, e.getMessage());
                try {
                    Thread.sleep(PAUSE);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }
}
