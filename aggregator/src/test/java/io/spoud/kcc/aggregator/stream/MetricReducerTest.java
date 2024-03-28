package io.spoud.kcc.aggregator.stream;

import io.spoud.kcc.data.AggregatedData;
import io.spoud.kcc.data.EntityType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricReducerTest {
    private static final String RETAINED_BYTES = "confluent_kafka_server_retained_bytes";
    private static final String SENT_BYTES = "confluent_kafka_server_sent_bytes";
    private static final String RECEIVED_BYTES = "confluent_kafka_server_received_bytes";
    private static final TestConfigProperties configProperties = TestConfigProperties.builder()
            .applicationId("test")
            .metricsAggregations(Map.of(RETAINED_BYTES, "max", SENT_BYTES, "sum"))
            .build();

    @Test
    void should_sum_per_default() {
        var agg1 = createAggregatedData(RECEIVED_BYTES, 10);
        var agg2 = createAggregatedData(RECEIVED_BYTES, 20);
        var reducer = new MetricReducer(configProperties);
        var result = reducer.apply(agg1, agg2);
        assertThat(result.getValue()).isEqualTo(30);
    }

    @Test
    void should_max_when_configured() {
        var agg1 = createAggregatedData(RETAINED_BYTES, 10);
        var agg2 = createAggregatedData(RETAINED_BYTES, 20);
        var reducer = new MetricReducer(configProperties);
        var result = reducer.apply(agg1, agg2);
        assertThat(result.getValue()).isEqualTo(20);
    }

    @Test
    void should_sum_when_configured() {
        var agg1 = createAggregatedData(SENT_BYTES, 10);
        var agg2 = createAggregatedData(SENT_BYTES, 20);
        var reducer = new MetricReducer(configProperties);
        var result = reducer.apply(agg1, agg2);
        assertThat(result.getValue()).isEqualTo(30);
    }

    private static AggregatedData createAggregatedData(String metricName, double value) {
        return AggregatedData.newBuilder()
                .setTimestamp(Instant.now())
                .setContext(Map.of())
                .setName("test")
                .setEntityType(EntityType.TOPIC)
                .setTags(Map.of())
                .setInitialMetricName(metricName)
                .setValue(value)
                .build();
    }
}
