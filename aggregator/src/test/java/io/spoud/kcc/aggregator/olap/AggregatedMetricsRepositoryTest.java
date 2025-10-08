package io.spoud.kcc.aggregator.olap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.spoud.kcc.aggregator.graphql.data.MetricHistoryTO;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.aggregator.stream.MetricReducer;
import io.spoud.kcc.aggregator.stream.TestConfigProperties;
import io.spoud.kcc.data.AggregatedDataWindowed;
import io.spoud.kcc.data.EntityType;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class AggregatedMetricsRepositoryTest {

    AggregatedMetricsRepository repo;
    OlapInfra olapInfra;

    @BeforeEach
    void setUp() {
        MetricNameRepository metricNameRepository = new MetricNameRepository(new MetricReducer(TestConfigProperties.builder().build()));
        olapInfra = new OlapInfra(testOlapConfig, metricNameRepository);
        olapInfra.init();
        repo = new AggregatedMetricsRepository(testOlapConfig, olapInfra, metricNameRepository);
    }

    @DisplayName("DB memory limit respects given constraint")
    @Test
    void dbMemoryLimit() {
        var conf1 = FakeOlapConfig
                .builder()
                .databaseMemoryLimitPercent(50)
                .totalMemoryLimitMb(Optional.of(256))
                .build();
        assertThat(conf1.databaseMemoryLimitMib()).isEqualTo(Optional.of(128));

        var conf2 = FakeOlapConfig
                .builder()
                .databaseMemoryLimitPercent(50)
                .totalMemoryLimitMb(Optional.empty())
                .build();
        assertThat(conf2.databaseMemoryLimitMib()).isEmpty();
    }

    @DisplayName("Load Seed Data from CSV File")
    @Test
    void loadSeedData() {
        var exportPath = AggregatedMetricsRepositoryTest.class.getClassLoader().getResource("data/olap-export.csv")
                .getPath();
        FakeOlapConfig fakeOlapConfig = FakeOlapConfig
                .builder()
                .databaseSeedDataPath(exportPath)
                .build();
        OlapInfra localOlapInfa = new OlapInfra(fakeOlapConfig, new MetricNameRepository(new MetricReducer(TestConfigProperties.builder().build())));
        var repo = new AggregatedMetricsRepository(fakeOlapConfig,
                localOlapInfa,
                null);
        localOlapInfa.init();

        // make sure we already have some data without inserting anything
        assertThat(repo.getAllMetrics()).isNotEmpty();
        assertThat(repo.getHistory(
                Instant.parse("2025-02-12T16:00:00.00Z"),
                Instant.parse("2025-02-12T18:00:00.00Z"),
                Set.of("kafka_log_log_size")
        ))
                .isNotEmpty();
    }

    @DisplayName("Inserted row is not immediately flushed to DB")
    @Test
    void insertRow() {
        repo.insertRow(randomDatapoint().setInitialMetricName("my-awesome-metric").build());

        assertThat(repo.getAllMetrics()).isEmpty();

        // now flush manually
        repo.flushToDb();

        assertThat(repo.getAllMetrics()).isNotEmpty();
    }

    @DisplayName("Rows for different time windows do not overwrite each other")
    @Test
    void insertRowDifferentTimeWindows() {
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

    @DisplayName("Get history grouped by a particular context key")
    @Test
    void groupHistoryByContextKeyAndHour() {
        var start = Instant.now().truncatedTo(ChronoUnit.HOURS);
        var end = start.plus(Duration.ofHours(1));
        var start2 = end;
        var end2 = start2.plus(Duration.ofHours(1));

        var kccCtx1 = Map.of("app", "kcc", "region", "eu-west");
        var kafkaCtx1 = Map.of("app", "kafka", "region", "eu-west");

        repo.insertRow(randomDatapoint().setStartTime(start).setEndTime(end).setInitialMetricName("my-awesome-metric").setValue(1).setContext(kccCtx1).build());
        repo.insertRow(randomDatapoint().setStartTime(start2).setEndTime(end2).setInitialMetricName("my-awesome-metric").setValue(1).setContext(kccCtx1).build());
        repo.insertRow(randomDatapoint().setStartTime(start).setEndTime(end).setInitialMetricName("my-awesome-metric").setValue(1).setContext(kafkaCtx1).build());
        repo.insertRow(randomDatapoint().setStartTime(start2).setEndTime(end2).setInitialMetricName("my-awesome-metric").setValue(1).setContext(kafkaCtx1).build());

        // some new contexts (same app, different region)
        var kccCtx2 = Map.of("app", "kcc", "region", "us-east");
        var kafkaCtx2 = Map.of("app", "kafka", "region", "us-east");

        // insert again, but this time with a different context for the same app (this is to test that values for the same app in the same time window get summed up)
        repo.insertRow(randomDatapoint().setStartTime(start2).setEndTime(end2).setInitialMetricName("my-awesome-metric").setValue(1).setContext(kccCtx2).build());
        repo.insertRow(randomDatapoint().setStartTime(start2).setEndTime(end2).setInitialMetricName("my-awesome-metric").setValue(1).setContext(kafkaCtx2).build());

        repo.flushToDb();

        var history = repo.getHistoryGrouped(start, end2, Set.of("my-awesome-metric"), "app");
        assertThat(history).hasSize(2);
        assertThat(history.stream().map(MetricHistoryTO::getName)).containsExactlyInAnyOrder("kcc", "kafka");
        // each history entry should have two distinct timestamps
        assertThat(history.stream()
                .filter(e -> e.getName().equals("kafka"))
                .map(MetricHistoryTO::getTimes)
                .flatMap(Collection::stream)
                .map(t -> t.truncatedTo(ChronoUnit.SECONDS))
        ).containsExactlyInAnyOrder(start.truncatedTo(ChronoUnit.SECONDS), start2.truncatedTo(ChronoUnit.SECONDS));
        assertThat(history.stream()
                .filter(e -> e.getName().equals("kcc"))
                .map(MetricHistoryTO::getTimes)
                .flatMap(Collection::stream)
                .map(t -> t.truncatedTo(ChronoUnit.SECONDS))
        ).containsExactlyInAnyOrder(start.truncatedTo(ChronoUnit.SECONDS), start2.truncatedTo(ChronoUnit.SECONDS));
        // each history entry should have two values
        assertThat(history.stream()
                .filter(e -> e.getName().equals("kafka"))
                .map(MetricHistoryTO::getValues)
                .flatMap(Collection::stream)
        ).containsExactly(1., 2.);
        assertThat(history.stream()
                .filter(e -> e.getName().equals("kcc"))
                .map(MetricHistoryTO::getValues)
                .flatMap(Collection::stream)
        ).containsExactly(1., 2.);

        // now group by region (we expect a value of 2 for eu-west for both timestamps)
        var historyByRegion = repo.getHistoryGrouped(start, end2, Set.of("my-awesome-metric"), "region");
        assertThat(historyByRegion).hasSize(2);
        assertThat(historyByRegion.stream().map(MetricHistoryTO::getName)).containsExactlyInAnyOrder("eu-west", "us-east");
        assertThat(historyByRegion.stream()
                .filter(e -> e.getName().equals("eu-west"))
                .map(MetricHistoryTO::getTimes)
                .flatMap(Collection::stream)
                .map(t -> t.truncatedTo(ChronoUnit.SECONDS))
        ).containsExactlyInAnyOrder(start.truncatedTo(ChronoUnit.SECONDS), start2.truncatedTo(ChronoUnit.SECONDS));
        assertThat(historyByRegion.stream()
                .filter(e -> e.getName().equals("us-east"))
                .map(MetricHistoryTO::getTimes)
                .flatMap(Collection::stream)
                .map(t -> t.truncatedTo(ChronoUnit.SECONDS))
        ).containsExactlyInAnyOrder(start2.truncatedTo(ChronoUnit.SECONDS)); // us-east only has data for the second timestamp
        assertThat(historyByRegion.stream()
                .filter(e -> e.getName().equals("eu-west"))
                .map(MetricHistoryTO::getValues)
                .flatMap(Collection::stream)
        ).containsExactly(2., 2.); // eu-west has a value of 2 for both timestamps
        assertThat(historyByRegion.stream()
                .filter(e -> e.getName().equals("us-east"))
                .map(MetricHistoryTO::getValues)
                .flatMap(Collection::stream)
        ).containsExactly(2.); // us-east only has data for the second timestamp
    }

    @DisplayName("Group history by context key but not by hour")
    @Test
    void groupHistoryByContextKey() {
        var start = Instant.now().truncatedTo(ChronoUnit.HOURS);
        var end = start.plus(Duration.ofHours(1));
        // distant future
        var start2 = end.plus(Duration.ofDays(30));
        var end2 = start2.plus(Duration.ofHours(1));

        var kccCtx1 = Map.of("app", "kcc", "region", "eu-west");
        var kafkaCtx1 = Map.of("app", "kafka", "region", "eu-west");
        repo.insertRow(randomDatapoint().setStartTime(start).setEndTime(end).setInitialMetricName("my-awesome-metric").setValue(1).setContext(kccCtx1).build());
        repo.insertRow(randomDatapoint().setStartTime(start2).setEndTime(end2).setInitialMetricName("my-awesome-metric").setValue(1).setContext(kccCtx1).build());
        repo.insertRow(randomDatapoint().setStartTime(start).setEndTime(end).setInitialMetricName("my-awesome-metric").setValue(1).setContext(kafkaCtx1).build());
        repo.insertRow(randomDatapoint().setStartTime(start2).setEndTime(end2).setInitialMetricName("my-awesome-metric").setValue(2).setContext(kafkaCtx1).build());

        repo.flushToDb();
        var history = repo.getHistoryGrouped(start, end2, Set.of("my-awesome-metric"), "app", false);

        // we now expect one row per app, with the values summed up
        assertThat(history).hasSize(2);
        assertThat(history.stream().map(MetricHistoryTO::getName)).containsExactlyInAnyOrder("kcc", "kafka");
        assertThat(history.stream()
                .filter(e -> e.getName().equals("kafka"))
                .map(MetricHistoryTO::getTimes)
                .flatMap(Collection::stream)
                .map(t -> t.truncatedTo(ChronoUnit.SECONDS))
        ).containsExactly(start.truncatedTo(ChronoUnit.SECONDS)); // the time bucket begins at the start of the queried interval
        assertThat(history.stream()
                .filter(e -> e.getName().equals("kcc"))
                .map(MetricHistoryTO::getTimes)
                .flatMap(Collection::stream)
                .map(t -> t.truncatedTo(ChronoUnit.SECONDS))
        ).containsExactly(start.truncatedTo(ChronoUnit.SECONDS)); // the time bucket begins at the start of the queried interval
        assertThat(history.stream()
                .filter(e -> e.getName().equals("kafka"))
                .map(MetricHistoryTO::getValues)
                .flatMap(Collection::stream)
        ).containsExactly(3.); // both values summed up
        assertThat(history.stream()
                .filter(e -> e.getName().equals("kcc"))
                .map(MetricHistoryTO::getValues)
                .flatMap(Collection::stream)
        ).containsExactly(2.); // both values summed up

        // now group by region (we expect just one row for eu-west with a value of 1+1+1+2)
        var historyByRegion = repo.getHistoryGrouped(start, end2, Set.of("my-awesome-metric"), "region", false);
        assertThat(historyByRegion).hasSize(1);
        assertThat(historyByRegion.stream().map(MetricHistoryTO::getName)).containsExactlyInAnyOrder("eu-west");
        assertThat(historyByRegion.stream()
                .filter(e -> e.getName().equals("eu-west"))
                .map(MetricHistoryTO::getTimes)
                .flatMap(Collection::stream)
                .map(t -> t.truncatedTo(ChronoUnit.SECONDS))
        ).containsExactly(start.truncatedTo(ChronoUnit.SECONDS)); // the time bucket begins at the start of the queried interval
        assertThat(historyByRegion.stream()
                .filter(e -> e.getName().equals("eu-west"))
                .map(MetricHistoryTO::getValues)
                .flatMap(Collection::stream)
        ).containsExactly(5.); // all values summed up
    }

    @DisplayName("Group history by non-existent key")
    @Test
    void groupHistoryByBadContextKey() {
        var start = Instant.now().truncatedTo(ChronoUnit.HOURS);
        var end = start.plus(Duration.ofHours(1));
        // distant future
        var start2 = end.plus(Duration.ofDays(30));
        var end2 = start2.plus(Duration.ofHours(1));

        var kccCtx1 = Map.of("app", "kcc", "region", "eu-west");
        var kafkaCtx1 = Map.of("app", "kafka", "region", "eu-west");
        repo.insertRow(randomDatapoint().setStartTime(start).setEndTime(end).setInitialMetricName("my-awesome-metric").setValue(1).setContext(kccCtx1).build());
        repo.insertRow(randomDatapoint().setStartTime(start2).setEndTime(end2).setInitialMetricName("my-awesome-metric").setValue(1).setContext(kccCtx1).build());
        repo.insertRow(randomDatapoint().setStartTime(start).setEndTime(end).setInitialMetricName("my-awesome-metric").setValue(1).setContext(kafkaCtx1).build());
        repo.insertRow(randomDatapoint().setStartTime(start2).setEndTime(end2).setInitialMetricName("my-awesome-metric").setValue(2).setContext(kafkaCtx1).build());

        repo.flushToDb();

        // now group by stage (we expect just one row for "unknown" with a value of 1+1+1+2)
        var historyByRegion = repo.getHistoryGrouped(start, end2, Set.of("my-awesome-metric"), "stage", false);
        assertThat(historyByRegion).hasSize(1);
        assertThat(historyByRegion.stream().map(MetricHistoryTO::getName)).containsExactlyInAnyOrder("unknown");
        assertThat(historyByRegion.stream()
                .filter(e -> e.getName().equals("unknown"))
                .map(MetricHistoryTO::getTimes)
                .flatMap(Collection::stream)
                .map(t -> t.truncatedTo(ChronoUnit.SECONDS))
        ).containsExactly(start.truncatedTo(ChronoUnit.SECONDS)); // the time bucket begins at the start of the queried interval
        assertThat(historyByRegion.stream()
                .filter(e -> e.getName().equals("unknown"))
                .map(MetricHistoryTO::getValues)
                .flatMap(Collection::stream)
        ).containsExactly(5.); // all values summed up
    }

    @DisplayName("Get only rows that match the specified metric name")
    @Test
    void filterHistoryRowsByMetricName() {
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
        for (int i = 0; i < testOlapConfig.databaseMaxBufferedRows() - 1; i++) {
            repo.insertRow(randomDatapoint().setInitialMetricName("my-awesome-metric").build());
        }

        // wait a split-second (because the flush happens asynchronously in a separate thread)
        // then check that no data has been flushed yet
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        assertThat(repo.getAllMetrics()).isEmpty();

        // now insert one more
        repo.insertRow(randomDatapoint().setInitialMetricName("my-awesome-metric").build());

        // wait a split-second, because the flush happens asynchronously in a separate thread
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        assertThat(repo.getAllMetrics()).isNotEmpty();
    }

    @Test
    @DisplayName("Get all tag keys")
    void getAllTagKeys() {
        // insert to db and flush, make sure we get all the tags we specified
        repo.insertRow(randomDatapoint().setTags(Map.of("env", "switzerlandnorth", "stage", "test")).build());
        repo.flushToDb();
        assertThat(repo.getAllTagKeys()).contains("env", "stage");
    }

    @Test
    @DisplayName("Get all context keys")
    void getAllContextKeys() {
        // insert to db and flush, make sure we get all the context keys we specified
        repo.insertRow(randomDatapoint().setContext(Map.of("app", "kcc", "org", "spoud", "topic", "kcc-topic")).build());
        repo.flushToDb();
        assertThat(repo.getAllContextKeys()).contains("app", "org", "topic");
    }

    @Test
    @DisplayName("Get all metric names")
    void getAllMetrics() {
        // insert to db and flush, make sure we get all the metrics we specified
        repo.insertRow(randomDatapoint().setInitialMetricName("metric1").build());
        repo.insertRow(randomDatapoint().setInitialMetricName("metric2").build());
        repo.flushToDb();
        assertThat(repo.getAllMetrics()).contains("metric1", "metric2");
    }

    @Test
    @DisplayName("Get all tag values for a given key")
    void getAllTagValues() {
        // insert to db and flush, make sure we get all the tag values we specified
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
        @Builder.Default
        private final Optional<Integer> totalMemoryLimitMb = Optional.ofNullable(1024);
        @Builder.Default
        private final int databaseMemoryLimitPercent = 50;

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
        public Optional<Integer> totalMemoryLimitMb() {
            return totalMemoryLimitMb;
        }

        @Override
        public int databaseMemoryLimitPercent() {
            return databaseMemoryLimitPercent;
        }

        @Override
        public Optional<String> databaseSeedDataPath() {
            return Optional.ofNullable(databaseSeedDataPath);
        }

        @Override
        public Optional<Integer> insertSyntheticDays() {
            return Optional.empty();
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
