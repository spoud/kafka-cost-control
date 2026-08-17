package io.spoud.kcc.aggregator.ai;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Private mode's guarantee is that no value from the database reaches the model — only the schema
 * shape. Two mechanisms enforce it, and both are pinned here: the value-listing tool is withdrawn
 * rather than merely discouraged, and the query tool is terminal so its rows go to the user.
 */
@QuarkusTest
@TestProfile(PrivateModeTest.PrivateModeProfile.class)
class PrivateModeTest {

    public static class PrivateModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cc.ai.private-mode", "true");
        }
    }

    @Inject
    SchemaDescriber schemaDescriber;

    @Test
    void withdrawsTheValueListingTool() {
        // list_context_values returns application, team and topic names — exactly the business
        // data private mode exists to keep in-house. Not offering the tool is a stronger
        // guarantee than telling the model not to call it.
        List<String> toolNames = schemaDescriber.tools().stream().map(LlmTool::name).toList();

        assertThat(toolNames).doesNotContain("list_context_values");
        assertThat(toolNames).contains("list_metrics", "list_context_keys", "run_sql", "cost_overview");
    }

    @Test
    void marksDataReturningToolsAsTerminal() {
        assertThat(SchemaDescriber.isTerminalTool("run_sql")).isTrue();
        assertThat(SchemaDescriber.isTerminalTool("cost_overview")).isTrue();
        // Schema-shape tools are safe to feed back into the conversation.
        assertThat(SchemaDescriber.isTerminalTool("list_metrics")).isFalse();
        assertThat(SchemaDescriber.isTerminalTool("list_context_keys")).isFalse();
    }

    @Test
    void tellsTheModelItCannotSeeValuesOrResults() {
        String prompt = schemaDescriber.buildSystemPrompt();

        assertThat(prompt).contains("private mode");
        // The model gets one shot and cannot verify a guessed filter value, so the prompt must
        // steer it towards grouping rather than filtering.
        assertThat(prompt).contains("GROUP BY").contains("one query");
    }
}
