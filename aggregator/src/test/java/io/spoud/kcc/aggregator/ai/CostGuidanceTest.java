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
 * The model queries the costs view: `cost` is the bill's share where a bill applies, else the
 * pricing rule's (estimated). It must be told which is which, and that bills are entered, not split
 * from an amount typed into the chat.
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
    @DisplayName("The prompt describes the costs view: billed cost, estimates and the rate card")
    void promptExplainsTheCostsView() {
        String prompt = describer().buildSystemPrompt();

        assertThat(prompt).contains("CREATE VIEW costs");
        assertThat(prompt).contains("`SUM(cost)`");
        assertThat(prompt).contains("estimated");
        assertThat(prompt).contains("rate_cost");
        assertThat(prompt).contains("enter it on the Bills page");
        assertThat(prompt).doesNotContain("cost_overview").doesNotContain("FROM aggregated_data");
    }

    @Test
    @DisplayName("The prompt describes how the app works today, not as it once did")
    void promptMatchesHowTheAppWorks() {
        String prompt = describer().buildSystemPrompt();

        assertThat(prompt).contains("# The data: one view, `costs`").doesNotContain("The one table");
        // a topic metric split among principals is replaced, so entity types don't overlap
        assertThat(prompt).contains("Never add up `value` across different metrics")
                .doesNotContain("Do not sum across `entity_type`");
        // UNKNOWN means cluster-wide; rows without context are the unassigned ones
        assertThat(prompt).contains("cluster-wide metric").contains("matched no context rule")
                .doesNotContain("matched no topic or principal rule");
        // spread-by-usage lines sit on topic and principal rows, named after them
        assertThat(prompt).contains("with `TOPIC`/`PRINCIPAL`, a line spread by usage");
        assertThat(prompt).contains("after a reprocess");
    }

    @Test
    @DisplayName("There is no tool to split an amount typed into the chat any more")
    void noInvoiceSplitTool() {
        assertThat(describer().tools()).extracting(LlmTool::name).doesNotContain("cost_overview");
    }
}
