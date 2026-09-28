package io.spoud.kcc.aggregator.stream;

import io.smallrye.config.source.yaml.YamlConfigSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// Test resources shadow the main application.yaml, so the shipped defaults are read from source.
class ShippedAggregationDefaultsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "confluent_kafka_server_retained_bytes",
            "confluent_kafka_server_partition_count",
            "kafka_topic_partition_count",
            "kafka_topic_schema_count"
    })
    void level_gauges_are_aggregated_with_max(String metricName) throws IOException {
        var shipped = new YamlConfigSource(Path.of("src/main/resources/application.yaml").toUri().toURL());

        assertThat(shipped.getValue("cc.metrics.aggregations." + metricName)).isEqualTo("max");
    }
}
