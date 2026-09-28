package io.spoud.kcc.aggregator.ai;

import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The table carries pricing-rule costs, while splitting a real bill still has to go through
 * cost_overview. The model must be told both, and which network line is which direction.
 */
class CostGuidanceTest {

    private static SchemaDescriber describer() {
        var repository = Mockito.mock(AggregatedMetricsRepository.class);
        Mockito.when(repository.getAllMetrics()).thenReturn(Set.of("confluent_kafka_server_request_bytes"));
        Mockito.when(repository.getAllContextKeys()).thenReturn(Set.of("team"));
        var metricNames = Mockito.mock(MetricNameRepository.class);
        Mockito.when(metricNames.getMetricNames()).thenReturn(List.of(
                new MetricNameEntity("confluent_kafka_server_request_bytes", Instant.EPOCH, "SUM")));
        return new SchemaDescriber(repository, new TestAiConfig(), metricNames);
    }

    @Test
    @DisplayName("The prompt describes the cost column and still routes bills to cost_overview")
    void promptExplainsBothKindsOfCost() {
        String prompt = describer().buildSystemPrompt();

        assertThat(prompt).doesNotContain("There is no cost column");
        assertThat(prompt).contains("cost                DOUBLE");
        assertThat(prompt).contains("`SUM(cost)`");
        assertThat(prompt).contains("must go through the `cost_overview` tool");
    }

    @Test
    @DisplayName("cost_overview names read as egress and write as ingress")
    void networkDirectionsMatchConfluentBilling() {
        var costOverview = describer().tools().stream()
                .filter(tool -> tool.name().equals("cost_overview"))
                .findFirst().orElseThrow();

        assertThat(costOverview.properties().get("networkReadCents").get("description").toString())
                .contains("egress");
        assertThat(costOverview.properties().get("networkWriteCents").get("description").toString())
                .contains("ingress");
    }
}
