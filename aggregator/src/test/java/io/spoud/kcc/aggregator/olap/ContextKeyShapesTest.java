package io.spoud.kcc.aggregator.olap;

import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.aggregator.stream.MetricReducer;
import io.spoud.kcc.aggregator.stream.TestConfigProperties;
import io.spoud.kcc.data.AggregatedDataWindowed;
import io.spoud.kcc.data.EntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context keys are written by users into context-data rules, so nothing constrains their
 * characters. `app-id` and `cost-unit` are real ones that broke the demo environment; a dot or a
 * bracket is the subtler case, because an unquoted JSON path reads those structurally and returns
 * null rather than failing.
 */
class ContextKeyShapesTest {

    private static final OlapConfigProperties CONFIG = FakeOlapConfig.builder().build();
    private AggregatedMetricsRepository repo;

    @BeforeEach
    void setUp() {
        var infra = new OlapInfra(CONFIG);
        infra.init();
        repo = new AggregatedMetricsRepository(CONFIG, TestConfigProperties.builder().build(), infra,
                new MetricNameRepository(new MetricReducer(TestConfigProperties.builder().build()), infra));
    }

    private void insert(String contextKey, String value) {
        repo.insertRow(AggregatedDataWindowed.newBuilder()
                .setStartTime(Instant.now())
                .setEndTime(Instant.now().plus(Duration.ofHours(1)))
                .setInitialMetricName("metric")
                .setValue(1.0)
                .setTags(Map.of())
                .setContext(Map.of(contextKey, value))
                .setName("entity")
                .setEntityType(EntityType.TOPIC)
                .build());
        repo.flushToDb();
    }

    @Test
    @DisplayName("Values are readable whatever the key is called")
    void readsEveryKeyShape() {
        // the two that broke demo, plus the shapes an unquoted JSON path would misread
        for (String key : new String[] {
                "plain", "app-id", "cost-unit", "cost unit", "cost.unit", "a[0]", "Ünïcode", "a/b" }) {
            insert(key, "the-value");

            Set<String> values = repo.getAllContextValues(key);

            assertThat(values).as("key %s", key).contains("the-value");
        }
    }

    @Test
    @DisplayName("A key that does not exist reads as absent, not as another key's value")
    void doesNotConfuseNestedPathsWithKeys() {
        insert("cost.unit", "literal-key");

        // 'cost' is not a key at all — an unquoted path would have walked into cost.unit
        assertThat(repo.getAllContextValues("cost")).doesNotContain("literal-key");
        assertThat(repo.getAllContextValues("cost.unit")).contains("literal-key");
    }
}
