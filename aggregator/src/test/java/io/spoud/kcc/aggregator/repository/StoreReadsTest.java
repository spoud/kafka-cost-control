package io.spoud.kcc.aggregator.repository;

import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreReadsTest {

    @Test
    void retriesUntilTheStoreAnswers() {
        var calls = new AtomicInteger();

        var result = StoreReads.retrying("store", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new InvalidStateStoreException("restoring");
            }
            return "rules";
        });

        assertThat(result).isEqualTo("rules");
        assertThat(calls).hasValue(3);
    }

    @Test
    void givesUpAfterABoundedTime() {
        var calls = new AtomicInteger();

        assertThatThrownBy(() -> StoreReads.retrying("store", () -> {
            calls.incrementAndGet();
            throw new InvalidStateStoreException("still restoring");
        })).isInstanceOf(InvalidStateStoreException.class).hasMessage("still restoring");
        assertThat(calls).hasValue(StoreReads.ATTEMPTS);
    }
}
