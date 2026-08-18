package io.spoud.kcc.aggregator.ai;

import io.spoud.kcc.aggregator.graphql.data.ChatAnswer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conversation history is trimmed by exchange, not by message count.
 * <p>
 * The distinction matters because a single question can add a dozen messages once tool
 * round-trips are counted, so a raw message cap made the amount of remembered conversation depend
 * on how tool-heavy the recent questions happened to be — two hard questions could evict the
 * history a user expected to still be there.
 */
class ConversationHistoryTest {

    /** One exchange: a question, an assistant turn with a tool call, and the tool result. */
    private static void addToolHeavyExchange(List<LlmMessage> history, String question, int toolRounds) {
        history.add(new LlmMessage.User(question));
        for (int i = 0; i < toolRounds; i++) {
            history.add(new LlmMessage.Assistant("",
                    List.of(new LlmMessage.ToolCall("id" + i, "run_sql", Map.of())), null));
            history.add(new LlmMessage.ToolResults(
                    List.of(LlmMessage.ToolResult.ok("id" + i, "rows"))));
        }
        history.add(new LlmMessage.Assistant("answer to " + question, List.of(), null));
    }

    private static List<LlmMessage> trim(List<LlmMessage> history, int maxExchanges) throws Exception {
        ChatService service = new ChatService(
                configWithExchanges(maxExchanges), null, null, null, null);
        Method m = ChatService.class.getDeclaredMethod("trimHistory", List.class);
        m.setAccessible(true);
        m.invoke(service, history);
        return history;
    }

    @Test
    void keepsTheRequestedNumberOfExchangesRegardlessOfHowToolHeavyTheyWere() throws Exception {
        var history = new ArrayList<LlmMessage>();
        // Each of these adds 8 messages — a raw 40-message cap would have kept only the last few.
        for (int i = 1; i <= 6; i++) {
            addToolHeavyExchange(history, "question " + i, 3);
        }

        trim(history, 3);

        var questions = history.stream()
                .filter(LlmMessage.User.class::isInstance)
                .map(m -> ((LlmMessage.User) m).text())
                .toList();
        assertThat(questions).containsExactly("question 4", "question 5", "question 6");
    }

    @Test
    void alwaysStartsTheRetainedWindowOnAQuestion() throws Exception {
        // A tool-result turn cannot be the first message, and an assistant turn that made tool
        // calls must keep the results answering them, or the provider sees a dangling call.
        var history = new ArrayList<LlmMessage>();
        for (int i = 1; i <= 4; i++) {
            addToolHeavyExchange(history, "question " + i, 2);
        }

        trim(history, 2);

        assertThat(history.get(0)).isInstanceOf(LlmMessage.User.class);
    }

    @Test
    void leavesShortConversationsAlone() throws Exception {
        var history = new ArrayList<LlmMessage>();
        addToolHeavyExchange(history, "only question", 5);
        int before = history.size();

        trim(history, 10);

        assertThat(history).hasSize(before);
    }

    @Test
    void flagsAnAnswerWhoseContextTheServerNoLongerHas() {
        // The browser keeps its transcript across a restart; the server does not keep its history.
        ChatAnswer flagged = ChatAnswer.success("an answer", List.of()).withContextLost();

        assertThat(flagged.contextLost()).isTrue();
        assertThat(flagged.text()).isEqualTo("an answer");
        assertThat(ChatAnswer.success("an answer", List.of()).contextLost()).isFalse();
    }

    private static AiConfigProperties configWithExchanges(int exchanges) {
        return new AiConfigProperties() {
            public boolean enabled() { return true; }
            public boolean privateMode() { return false; }
            public int maxRows() { return 100; }
            public int maxToolIterations() { return 6; }
            public java.time.Duration queryTimeout() { return java.time.Duration.ofSeconds(30); }
            public int maxSessions() { return 10; }
            public int maxHistoryExchanges() { return exchanges; }
        };
    }
}
