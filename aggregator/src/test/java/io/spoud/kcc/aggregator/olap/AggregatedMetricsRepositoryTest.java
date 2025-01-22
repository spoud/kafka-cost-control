package io.spoud.kcc.aggregator.olap;

import static org.assertj.core.api.Assertions.assertThat;
import io.spoud.kcc.data.AggregatedDataWindowed;
import io.spoud.kcc.data.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Random;

class AggregatedMetricsRepositoryTest {

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

    private static final OlapConfigProperties testOlapConfig = new OlapConfigProperties() {
        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public String databaseUrl() {
            return "jdbc:duckdb:";
        }

        @Override
        public String databaseTable() {
            return "aggregated_data";
        }

        @Override
        public String databaseSchema() {
            return "main";
        }

        @Override
        public int databaseFlushIntervalSeconds() {
            return 10;
        }

        @Override
        public int databaseMaxBufferedRows() {
            return 10;
        }
    };

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
