package io.spoud.kcc.aggregator.olap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.spoud.kcc.data.AggregatedDataWindowed;
import io.spoud.kcc.data.EntityType;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

class AggregatedMetricsRepositoryTest {

    @DisplayName("Load Seed Data from CSV File")
    @Test
    void loadSeedData() {
        var exportPath = AggregatedMetricsRepositoryTest.class.getClassLoader().getResource("data/olap-export.csv")
                .getPath();
        var repo = new AggregatedMetricsRepository(FakeOlapConfig
                .builder()
                .databaseSeedDataPath(exportPath)
                .build());
        repo.init();

        // make sure we already have some data without inserting anything
        assertThat(repo.getAllMetrics()).isNotEmpty();
        assertThat(repo.getHistory(
                Instant.parse("2025-02-12T16:00:00.00Z"),
                Instant.parse("2025-02-12T18:00:00.00Z"),
                Set.of("kafka_log_log_size")))
                .isNotEmpty();
    }

    @DisplayName("Inserted row is not immediately flushed to DB")
    @Test
    void insertRow() {
        var repo = new AggregatedMetricsRepository(testOlapConfig);
        repo.init();
        repo.insertRow(randomDatapoint().setInitialMetricName("my-awesome-metric").build());

        assertThat(repo.getAllMetrics()).isEmpty();

        // now flush manually
        repo.flushToDb();

        assertThat(repo.getAllMetrics()).isNotEmpty();
    }

    @DisplayName("Rows for different time windows do not overwrite each other")
    @Test
    void insertRowDifferentTimeWindows() {
        var repo = new AggregatedMetricsRepository(testOlapConfig);
        repo.init();

        var start = Instant.now();
        var end = start.plus(Duration.ofHours(1));
        var start2 = end.plusSeconds(1);
        var end2 = start2.plus(Duration.ofHours(1));

        repo.insertRow(randomDatapoint().setStartTime(start).setEndTime(end).setInitialMetricName("my-awesome-metric").build());
        repo.insertRow(randomDatapoint().setStartTime(start2).setEndTime(end2).setInitialMetricName("my-awesome-metric").build());

        repo.flushToDb();

        var history = repo.getHistory(start.minusSeconds(1), end2.plusSeconds(1), Set.of("my-awesome-metric"));
        assertThat(history).hasSize(2);
    }

    @DisplayName("Get only rows that match the specified metric name")
    @Test
    void filterHistoryRowsByMetricName() {
        var repo = new AggregatedMetricsRepository(testOlapConfig);
        repo.init();

        var start = Instant.now();
        var end = start.plus(Duration.ofHours(1));
        var start2 = end.plusSeconds(1);
        var end2 = start2.plus(Duration.ofHours(1));

        repo.insertRow(randomDatapoint().setStartTime(start).setEndTime(end).setInitialMetricName("bytesin").build());
        repo.insertRow(randomDatapoint().setStartTime(start2).setEndTime(end2).setInitialMetricName("bytesin").build());
        repo.insertRow(randomDatapoint().setStartTime(start).setEndTime(end).setInitialMetricName("bytesout").build());
        repo.insertRow(randomDatapoint().setStartTime(start2).setEndTime(end2).setInitialMetricName("bytesout").build());

        repo.flushToDb();

        var history = repo.getHistory(start.minusSeconds(1), end2.plusSeconds(1), Set.of("bytesin"));
        assertThat(history).hasSize(2);
        assertThat(history.stream().map(MetricEO::initialMetricName)).containsOnly("bytesin");
    }

    @DisplayName("Rows that only differ in value overwrite each other")
    @Test
    void upsertRow() {
        var start = Instant.now();
        var end = start.plus(Duration.ofHours(1));
        // these two rows are identical except for the value, so we want to perform an upsert
        var randomDp1 = randomDatapoint().setStartTime(start).setEndTime(end).setInitialMetricName("my-awesome-metric").setValue(1).build();
        var randomDp2 = randomDatapoint().setStartTime(start).setEndTime(end).setInitialMetricName("my-awesome-metric").setValue(2).build();

        var repo = new AggregatedMetricsRepository(testOlapConfig);
        repo.init();

        repo.insertRow(randomDp1);
        repo.insertRow(randomDp2);

        repo.flushToDb();

        var history = repo.getHistory(start.minusSeconds(1), end.plusSeconds(1), Set.of("my-awesome-metric"));
        // first datapoint should be overwritten by the second one
        assertThat(history).hasSize(1);
    }

    @Test
    @DisplayName("Flush to DB occurs automatically if buffer is full")
    void insertRowBufferFull() {
        var repo = new AggregatedMetricsRepository(testOlapConfig);
        repo.init();

        for (int i = 0; i < testOlapConfig.databaseMaxBufferedRows() - 1; i++) {
            repo.insertRow(randomDatapoint().setInitialMetricName("my-awesome-metric").build());
        }

        assertThat(repo.getAllMetrics()).isEmpty();

        // now insert one more
        repo.insertRow(randomDatapoint().setInitialMetricName("my-awesome-metric").build());

        assertThat(repo.getAllMetrics()).isNotEmpty();
    }

    @Test
    @DisplayName("Get all tag keys")
    void getAllTagKeys() {
        // insert to db and flush, make sure we get all the tags we specified
        var repo = new AggregatedMetricsRepository(testOlapConfig);
        repo.init();
        repo.insertRow(randomDatapoint().setTags(Map.of("env", "switzerlandnorth", "stage", "test")).build());
        repo.flushToDb();
        assertThat(repo.getAllTagKeys()).contains("env", "stage");
    }

    @Test
    @DisplayName("Get all context keys")
    void getAllContextKeys() {
        // insert to db and flush, make sure we get all the context keys we specified
        var repo = new AggregatedMetricsRepository(testOlapConfig);
        repo.init();
        repo.insertRow(randomDatapoint().setContext(Map.of("app", "kcc", "org", "spoud", "topic", "kcc-topic")).build());
        repo.flushToDb();
        assertThat(repo.getAllContextKeys()).contains("app", "org", "topic");
    }

    @Test
    @DisplayName("Get all metric names")
    void getAllMetrics() {
        // insert to db and flush, make sure we get all the metrics we specified
        var repo = new AggregatedMetricsRepository(testOlapConfig);
        repo.init();
        repo.insertRow(randomDatapoint().setInitialMetricName("metric1").build());
        repo.insertRow(randomDatapoint().setInitialMetricName("metric2").build());
        repo.flushToDb();
        assertThat(repo.getAllMetrics()).contains("metric1", "metric2");
    }

    @Test
    @DisplayName("Get all tag values for a given key")
    void getAllTagValues() {
        // insert to db and flush, make sure we get all the tag values we specified
        var repo = new AggregatedMetricsRepository(testOlapConfig);
        repo.init();
        repo.insertRow(randomDatapoint().setTags(Map.of("env", "switzerlandnorth", "stage", "test")).build());
        repo.insertRow(randomDatapoint().setTags(Map.of("env", "switzerlandwest", "stage", "prod")).build());
        repo.flushToDb();
        assertThat(repo.getAllTagValues("env")).containsExactlyInAnyOrder("switzerlandnorth", "switzerlandwest");
        assertThat(repo.getAllTagValues("stage")).containsExactlyInAnyOrder("test", "prod");
    }

    @Test
    @DisplayName("Get all context values for a given key")
    void getAllContextValues() {
        // insert to db and flush, make sure we get all the context values we specified
        var repo = new AggregatedMetricsRepository(testOlapConfig);
        repo.init();
        repo.insertRow(randomDatapoint().setContext(Map.of("app", "kcc", "org", "spoud", "topic", "kcc-topic")).build());
        repo.insertRow(randomDatapoint().setContext(Map.of("app", "kcc", "org", "spoud", "topic", "kcc-topic-2")).build());
        repo.flushToDb();
        assertThat(repo.getAllContextValues("app")).containsExactlyInAnyOrder("kcc");
        assertThat(repo.getAllContextValues("org")).containsExactlyInAnyOrder("spoud");
        assertThat(repo.getAllContextValues("topic")).containsExactlyInAnyOrder("kcc-topic", "kcc-topic-2");
    }

    @Test
    @DisplayName("Export metrics to jsonl file")
    void getAllMetricsJsonExport() throws IOException {
        var repo = new AggregatedMetricsRepository(testOlapConfig);
        repo.init();
        repo.insertRow(randomDatapoint().setInitialMetricName("metric1").setValue(10.0).setStartTime(Instant.now()).setEndTime(Instant.now().plusMillis(1000)).build());
        repo.insertRow(randomDatapoint().setInitialMetricName("metric2").setValue(10.0).setStartTime(Instant.now()).setEndTime(Instant.now().plusMillis(1000)).build());
        repo.flushToDb();
        var exportPath = repo.exportData(Instant.now().minus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(1)), "json");
        assertThat(exportPath).isNotNull();

        var objectMapper = new ObjectMapper();
        var metricCount = 0;
        try (var reader = Files.newBufferedReader(exportPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                var json = objectMapper.readTree(line);
                assertThat(json.has("start_time")).isTrue();
                assertThat(json.has("end_time")).isTrue();
                assertThat(json.has("initial_metric_name")).isTrue();
                assertThat(json.has("value")).isTrue();
                assertThat(json.has("tags")).isTrue();
                assertThat(json.has("context")).isTrue();
                assertThat(json.has("name")).isTrue();
                assertThat(json.get("value").asDouble()).isEqualTo(10.0);
                Log.info(json.toString());
                metricCount++;
            }
        } finally {
            Files.delete(exportPath);
        }
        assertThat(metricCount).isEqualTo(2);
    }

    @RequiredArgsConstructor
    @Builder
    private static class FakeOlapConfig implements OlapConfigProperties {
        @Builder.Default
        private final boolean enabled = true;
        @Builder.Default
        private final String databaseUrl = "jdbc:duckdb:";
        @Builder.Default
        private final int databaseFlushIntervalSeconds = 10;
        @Builder.Default
        private final int databaseMaxBufferedRows = 10;
        @Builder.Default
        private final String databaseSeedDataPath = null;

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public String databaseUrl() {
            return databaseUrl;
        }

        @Override
        public int databaseFlushIntervalSeconds() {
            return databaseFlushIntervalSeconds;
        }

        @Override
        public int databaseMaxBufferedRows() {
            return databaseMaxBufferedRows;
        }

        @Override
        public Optional<String> databaseSeedDataPath() {
            return Optional.ofNullable(databaseSeedDataPath);
        }
    }

    private static final OlapConfigProperties testOlapConfig = FakeOlapConfig.builder().build();

    private static final Random random = new Random();

    private AggregatedDataWindowed.Builder randomDatapoint() {
        return AggregatedDataWindowed.newBuilder()
                .setStartTime(Instant.now())
                .setEndTime(Instant.now().plus(Duration.ofHours(1)))
                .setInitialMetricName("metric1")
                .setValue(random.nextDouble(1000))
                .setTags(Map.of("env", "switzerlandnorth"))
                .setContext(Map.of("app", "kcc", "org", "spoud", "topic", "kcc-topic"))
                .setName("kcc-user")
                .setEntityType(EntityType.PRINCIPAL);
    }
}
