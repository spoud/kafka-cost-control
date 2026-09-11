package io.spoud.kcc.aggregator.ai;

import io.spoud.kcc.aggregator.data.ContextDataEntity;
import io.spoud.kcc.aggregator.data.PricingRuleEntity;
import io.spoud.kcc.aggregator.repository.ContextDataStreamRepository;
import io.spoud.kcc.aggregator.repository.PricingRulesStreamRepository;
import io.spoud.kcc.data.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The aggregated table stores the outcome of the context rules, never the rules themselves, so
 * without these tools the assistant could only say what context an entity has — not why.
 */
class RuleToolsTest {

    private static final ContextDataEntity RULE = new ContextDataEntity(
            "r1", Instant.parse("2026-01-01T00:00:00Z"), null, null,
            EntityType.TOPIC, "^orders\\..*", Map.of("tenant", "acme"));

    private static final PricingRuleEntity PRICE = new PricingRuleEntity(
            Instant.parse("2026-01-01T00:00:00Z"), "confluent_kafka_server_request_bytes", 1.5, 0.25);

    private static ToolRegistry registry(TestAiConfig config) {
        var context = Mockito.mock(ContextDataStreamRepository.class);
        var pricing = Mockito.mock(PricingRulesStreamRepository.class);
        Mockito.when(context.getContextObjects()).thenReturn(List.of(RULE));
        Mockito.when(pricing.getPricingRules()).thenReturn(List.of(PRICE));
        return new ToolRegistry(null, null, config, context, pricing);
    }

    private static String invoke(ToolRegistry registry, String tool) {
        return registry.invoke(new LlmMessage.ToolCall("id", tool, Map.of())).content();
    }

    @Test
    @DisplayName("Context rules carry the regex and what it assigns")
    void listsContextRules() {
        String content = invoke(registry(new TestAiConfig()), "list_context_rules");

        assertThat(content).contains("^orders\\..*").contains("tenant=acme").contains("TOPIC");
    }

    @Test
    @DisplayName("A regex is stored data, so it arrives fenced")
    void fencesContextRules() {
        // regexes are user-authored; one could be written to read like an instruction
        String content = invoke(registry(new TestAiConfig()), "list_context_rules");

        assertThat(content).contains("--- BEGIN DATA ---").contains("not instructions");
    }

    @Test
    @DisplayName("Pricing rules carry the formula behind a cost")
    void listsPricingRules() {
        String content = invoke(registry(new TestAiConfig()), "list_pricing_rules");

        assertThat(content).contains("confluent_kafka_server_request_bytes").contains("1.5").contains("0.25");
    }

    @Test
    @DisplayName("Private mode refuses both, since rules carry tenant names")
    void privateModeRefusesBoth() {
        var config = new TestAiConfig();
        config.privateMode = true;
        var registry = registry(config);

        for (String tool : List.of("list_context_rules", "list_pricing_rules")) {
            var result = registry.invoke(new LlmMessage.ToolCall("id", tool, Map.of()));
            assertThat(result.isError()).as(tool).isTrue();
            assertThat(result.content()).as(tool).contains("private mode");
        }
    }

    @Test
    @DisplayName("No rules configured reads as absence, not failure")
    void reportsAbsenceClearly() {
        var context = Mockito.mock(ContextDataStreamRepository.class);
        var pricing = Mockito.mock(PricingRulesStreamRepository.class);
        Mockito.when(context.getContextObjects()).thenReturn(List.of());
        Mockito.when(pricing.getPricingRules()).thenReturn(List.of());
        var registry = new ToolRegistry(null, null, new TestAiConfig(), context, pricing);

        assertThat(invoke(registry, "list_context_rules")).contains("No context-data rules");
        // the model must not answer a cost question by inventing a rate
        assertThat(invoke(registry, "list_pricing_rules")).contains("costs cannot be calculated");
    }
}
