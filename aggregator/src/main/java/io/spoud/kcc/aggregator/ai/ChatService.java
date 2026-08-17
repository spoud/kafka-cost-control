package io.spoud.kcc.aggregator.ai;

import io.quarkus.arc.All;
import io.quarkus.logging.Log;
import io.spoud.kcc.aggregator.graphql.data.ChatAnswer;
import io.spoud.kcc.aggregator.olap.OlapConfigProperties;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one question to completion: prompt the model, execute whatever tools it asks for, feed the
 * results back, repeat until it produces an answer.
 * <p>
 * The loop is bounded by {@code cc.ai.max-tool-iterations}. Hitting that bound is treated as a
 * real outcome and reported, rather than silently returning whatever partial text exists — a
 * truncated answer that looks complete is worse than an explicit "I ran out of steps".
 */
@ApplicationScoped
public class ChatService {

    private final AiConfigProperties aiConfig;
    private final OlapConfigProperties olapConfig;
    private final SchemaDescriber schemaDescriber;
    private final ToolRegistry toolRegistry;
    private final Instance<LlmClient> llmClients;
    private final Map<String, List<LlmMessage>> conversations;

    public ChatService(AiConfigProperties aiConfig,
                       OlapConfigProperties olapConfig,
                       SchemaDescriber schemaDescriber,
                       ToolRegistry toolRegistry,
                       @All Instance<LlmClient> llmClients) {
        this.aiConfig = aiConfig;
        this.olapConfig = olapConfig;
        this.schemaDescriber = schemaDescriber;
        this.toolRegistry = toolRegistry;
        this.llmClients = llmClients;
        // Bounded LRU: conversations are cheap to lose and expensive to leak.
        this.conversations = Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, List<LlmMessage>> eldest) {
                        return size() > ChatService.this.aiConfig.maxSessions();
                    }
                });
    }

    /**
     * Answer one question.
     *
     * @param sessionId client-generated conversation id; history is keyed on it
     * @param question  the user's question
     */
    public ChatAnswer ask(String sessionId, String question) {
        if (question == null || question.isBlank()) {
            return ChatAnswer.error("Please enter a question.");
        }

        // Distinguish the two "no answer" cases explicitly — every OLAP method returns empty for
        // both, and "I found no data" when the module is simply switched off is a bad answer.
        if (!aiConfig.enabled()) {
            return ChatAnswer.error("The AI assistant is disabled. Set cc.ai.enabled=true to use it.");
        }
        if (!olapConfig.enabled()) {
            return ChatAnswer.error(
                    "The analytics database (OLAP) is disabled, so there is no data to query. "
                            + "Set cc.olap.enabled=true.");
        }

        LlmClient llm;
        try {
            llm = selectClient();
        } catch (IllegalStateException e) {
            return ChatAnswer.error(e.getMessage());
        }

        List<LlmMessage> history = conversations.computeIfAbsent(sessionId, k -> new ArrayList<>());
        String systemPrompt = schemaDescriber.buildSystemPrompt();
        List<LlmTool> tools = schemaDescriber.tools();

        synchronized (history) {
            history.add(new LlmMessage.User(question));
            toolRegistry.beginQuestion();
            try {
                return runToolLoop(llm, systemPrompt, history, tools);
            } catch (Exception e) {
                Log.errorf(e, "Chat request failed for session %s", sessionId);
                // Drop the failed turn so the next question starts from a consistent history.
                trimTrailingUserTurn(history);
                return ChatAnswer.error("The assistant failed to answer: " + describe(e));
            } finally {
                trimHistory(history);
                toolRegistry.endQuestion();
            }
        }
    }

    private ChatAnswer runToolLoop(LlmClient llm, String systemPrompt, List<LlmMessage> history, List<LlmTool> tools) {
        for (int iteration = 0; iteration < aiConfig.maxToolIterations(); iteration++) {
            LlmMessage.Assistant assistant = llm.chat(systemPrompt, history, tools);
            history.add(assistant);

            if (!assistant.hasToolCalls()) {
                return ChatAnswer.success(assistant.text(), toolRegistry.executedSql());
            }

            var results = new ArrayList<LlmMessage.ToolResult>(assistant.toolCalls().size());
            for (LlmMessage.ToolCall call : assistant.toolCalls()) {
                Log.debugf("Assistant invoking tool '%s'", call.name());
                results.add(toolRegistry.invoke(call));
            }
            history.add(new LlmMessage.ToolResults(results));
        }

        return ChatAnswer.partial(
                "I could not finish this question within the allowed number of steps. "
                        + "Try narrowing it — a shorter time range or fewer dimensions usually helps.",
                toolRegistry.executedSql());
    }

    private LlmClient selectClient() {
        String provider = aiConfig.provider();
        for (LlmClient candidate : llmClients) {
            if (candidate.providerName().equalsIgnoreCase(provider)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No LLM client is available for provider '" + provider + "'. Check cc.ai.provider.");
    }

    /** Keep the most recent turns, always starting the retained window on a user turn. */
    private void trimHistory(List<LlmMessage> history) {
        int max = aiConfig.maxHistoryMessages();
        if (history.size() <= max) {
            return;
        }
        int from = history.size() - max;
        // A tool-result turn must never be the first message, and an assistant turn with tool
        // calls must keep its results — so rewind to the nearest user turn.
        while (from < history.size() && !(history.get(from) instanceof LlmMessage.User)) {
            from++;
        }
        if (from >= history.size()) {
            history.clear();
            return;
        }
        var retained = new ArrayList<>(history.subList(from, history.size()));
        history.clear();
        history.addAll(retained);
    }

    private void trimTrailingUserTurn(List<LlmMessage> history) {
        while (!history.isEmpty() && !(history.get(history.size() - 1) instanceof LlmMessage.User)) {
            history.remove(history.size() - 1);
        }
        if (!history.isEmpty()) {
            history.remove(history.size() - 1);
        }
    }

    /** Reset one conversation. */
    public void clear(String sessionId) {
        conversations.remove(sessionId);
    }

    private String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
