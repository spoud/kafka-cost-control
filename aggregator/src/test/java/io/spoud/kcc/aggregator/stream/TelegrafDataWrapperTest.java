package io.spoud.kcc.aggregator.stream;

import io.spoud.kcc.aggregator.data.RawTelegrafData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TelegrafDataWrapperTest {

    @Test
    void get_gauge_value() {
        var rawData = new RawTelegrafData(
                Instant.now(),
                "bytesin_total",
                Map.of("gauge", 15.0, "ten_min_average", 10.0),
                Map.of("env", "dev", "topic", "test"));
        var wrapper = new TelegrafDataWrapper(rawData);

        assertThat(wrapper.getValue()).isEqualTo(15.0);
    }

    @Test
    void get_counter_value() {
        var rawData = new RawTelegrafData(
                Instant.now(),
                "bytesin_total",
                Map.of("counter", 15.0, "rate_per_sec", 0.1),
                Map.of("env", "dev", "topic", "test"));
        var wrapper = new TelegrafDataWrapper(rawData);

        assertThat(wrapper.getValue()).isEqualTo(15.0);
    }

    @Test
    void gauge_before_counter() {
        var rawData = new RawTelegrafData(
                Instant.now(),
                "bytesin_total",
                Map.of("gauge", 15.0, "counter", 10.0),
                Map.of("env", "dev", "topic", "test"));
        var wrapper = new TelegrafDataWrapper(rawData);

        assertThat(wrapper.getValue()).isEqualTo(15.0);
    }

    @Test
    void fallback_to_first_alphabetic_key() {
        var rawData = new RawTelegrafData(
                Instant.now(),
                "bytesin_total",
                Map.of("last_minute_mean", 15.0, "last_minute_variance", 1.0),
                Map.of("env", "dev", "topic", "test"));
        var wrapper = new TelegrafDataWrapper(rawData);

        assertThat(wrapper.getValue()).isEqualTo(15.0);
    }

    @Test
    void fallback_to_zero() {
        var rawData = new RawTelegrafData(
                Instant.now(),
                "bytesin_total",
                Map.of(),
                Map.of("env", "dev", "topic", "test"));
        var wrapper = new TelegrafDataWrapper(rawData);

        assertThat(wrapper.getValue()).isEqualTo(0.0);
    }
}
