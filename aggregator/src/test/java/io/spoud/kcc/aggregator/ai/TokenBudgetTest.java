package io.spoud.kcc.aggregator.ai;

import io.spoud.kcc.aggregator.graphql.data.ChatAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * max-tool-iterations bounds how many times the model is called and query-timeout bounds each
 * query, but neither bounds what one question costs: a single large result fed back to the model
 * can dwarf a dozen small round trips. Against a metered provider that is the only unbounded
 * quantity, so it has a ceiling of its own.
 */
class TokenBudgetTest {

    /** Never stops asking for tools, so only a limit can end the loop. */
    private static LlmClient alwaysCallsTools(int tokensPerCall, AtomicInteger calls) {
        return (systemPrompt, history, tools) -> {
            calls.incrementAndGet();
            return new LlmMessage.Assistant(
                    "",
                    List.of(new LlmMessage.ToolCall("id", "list_metrics", Map.of())),
                    null,
                    tokensPerCall);
        };
    }

    private static ToolRegistry stubRegistry(AiConfigProperties config) {
        return new ToolRegistry(null, null, config) {
            @Override
            public LlmMessage.ToolResult invoke(LlmMessage.ToolCall call) {
                return LlmMessage.ToolResult.ok(call.id(), "rows");
            }

            @Override
            public List<String> executedSql() {
                return List.of();
            }
        };
    }

    private static ChatAnswer runLoop(TestAiConfig config, LlmClient llm) throws Exception {
        ChatService service = new ChatService(config, null, null, stubRegistry(config), null);
        Method m = ChatService.class.getDeclaredMethod(
                "runToolLoop", LlmClient.class, String.class, List.class, List.class);
        m.setAccessible(true);
        return (ChatAnswer) m.invoke(service, llm, "system", new ArrayList<LlmMessage>(), List.of());
    }

    @Test
    @DisplayName("A question stops once it has spent its token ceiling")
    void stopsAtTheCeiling() throws Exception {
        var config = new TestAiConfig();
        config.maxToolIterations = 50;
        config.maxTokensPerQuestion = 1000;
        var calls = new AtomicInteger();

        ChatAnswer answer = runLoop(config, alwaysCallsTools(400, calls));

        // 400 a call, so the third crosses 1000 - well before the 50 iterations allowed
        assertThat(calls.get()).isEqualTo(3);
        assertThat(answer.text()).contains("cost limit");
    }

    @Test
    @DisplayName("Iterations still bound the loop when no ceiling is set")
    void zeroMeansNoCeiling() throws Exception {
        var config = new TestAiConfig();
        config.maxToolIterations = 4;
        config.maxTokensPerQuestion = 0;
        var calls = new AtomicInteger();

        runLoop(config, alwaysCallsTools(100_000, calls));

        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("A provider reporting no usage cannot look free")
    void unknownUsageDoesNotBypassTheCeiling() throws Exception {
        var config = new TestAiConfig();
        config.maxToolIterations = 3;
        config.maxTokensPerQuestion = 1000;
        var calls = new AtomicInteger();

        // tokens = 0: the ceiling never trips, so max-tool-iterations has to hold the line
        runLoop(config, alwaysCallsTools(0, calls));

        assertThat(calls.get()).isEqualTo(3);
    }
}
