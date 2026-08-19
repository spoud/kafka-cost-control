package io.spoud.kcc.aggregator.ai;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.spoud.kcc.aggregator.graphql.data.AssistantStatus;
import io.spoud.kcc.aggregator.graphql.data.ChatAnswer;
import io.spoud.kcc.aggregator.olap.OlapConfigProperties;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one question to completion: prompt the model, run the tools it asks for, feed results back,
 * repeat until it answers or hits {@code cc.ai.max-tool-iterations}.
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
                       Instance<LlmClient> llmClients) {
        this.aiConfig = aiConfig;
        this.olapConfig = olapConfig;
        this.schemaDescriber = schemaDescriber;
        this.toolRegistry = toolRegistry;
        this.llmClients = llmClients;
        // Bounded LRU.
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
    /** Report the active configuration at startup rather than on the first question. */
    void logConfiguration(@Observes StartupEvent event) {
        if (!aiConfig.enabled()) {
            Log.info("AI assistant is disabled (cc.ai.enabled=false)");
            return;
        }
        Log.infof("AI assistant enabled: base-url=%s model=%s%s",
                aiConfig.baseUrl(),
                aiConfig.model(),
                aiConfig.privateMode() ? " (private mode: results are not sent to the model)" : "");
    }

    /**
     * Whether a question asked right now would be answered. {@link #ask} consults this, so the
     * status the UI hides on cannot drift from what is enforced when answering.
     */
    public AssistantStatus status() {
        if (!aiConfig.enabled()) {
            return AssistantStatus.unavailable(
                    "The AI assistant is disabled. Set cc.ai.enabled=true to use it.");
        }
        // OLAP returns empty when off, which would read as "no data" rather than "no database".
        if (!olapConfig.enabled()) {
            return AssistantStatus.unavailable(
                    "The analytics database (OLAP) is disabled, so there is no data to query. "
                            + "Set cc.olap.enabled=true.");
        }
        if (!llmClients.iterator().hasNext()) {
            return AssistantStatus.unavailable(
                    "No language model is configured. Add a quarkus-langchain4j-* provider "
                            + "extension and configure it under quarkus.langchain4j.*");
        }
        return AssistantStatus.ready();
    }

    public ChatAnswer ask(String sessionId, String question) {
        return ask(sessionId, question, 0);
    }

    /**
     * @param priorTurns how many earlier questions the caller is displaying for this session; used
     *                   only to detect that the caller remembers more than the server does
     */
    public ChatAnswer ask(String sessionId, String question, int priorTurns) {
        if (question == null || question.isBlank()) {
            return ChatAnswer.error("Please enter a question.");
        }

        AssistantStatus status = status();
        if (!status.available()) {
            return ChatAnswer.error(status.reason());
        }

        LlmClient llm;
        try {
            llm = selectClient();
        } catch (IllegalStateException e) {
            return ChatAnswer.error(e.getMessage());
        }

        List<LlmMessage> history = conversations.computeIfAbsent(sessionId, k -> new ArrayList<>());
        // The browser transcript outlives ours, which is in memory only.
        boolean contextLost = priorTurns > 0 && history.isEmpty();
        String systemPrompt = schemaDescriber.buildSystemPrompt();
        List<LlmTool> tools = schemaDescriber.tools();

        synchronized (history) {
            history.add(new LlmMessage.User(question));
            toolRegistry.beginQuestion();
            try {
                ChatAnswer answer = runToolLoop(llm, systemPrompt, history, tools);
                return contextLost ? answer.withContextLost() : answer;
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

            // Private mode: the first data-returning call ends the turn; its rows go to the user.
            if (aiConfig.privateMode()) {
                for (LlmMessage.ToolCall call : assistant.toolCalls()) {
                    if (SchemaDescriber.isTerminalTool(call.name())) {
                        ToolRegistry.TerminalResult result = toolRegistry.invokeTerminal(call);
                        // Close the tool calls without revealing results: an unanswered tool_use
                        // is replayed on the next question and strict providers reject it.
                        history.add(new LlmMessage.ToolResults(assistant.toolCalls().stream()
                                .map(c -> LlmMessage.ToolResult.ok(c.id(),
                                        "Results were returned directly to the user. Private mode is "
                                                + "enabled, so you cannot see them."))
                                .toList()));
                        return answerFromTable(assistant, result);
                    }
                }
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

    private ChatAnswer answerFromTable(LlmMessage.Assistant assistant, ToolRegistry.TerminalResult result) {
        List<String> sql = toolRegistry.executedSql();
        if (result.error() != null) {
            return ChatAnswer.error(result.error());
        }
        String preamble = assistant.text() == null || assistant.text().isBlank()
                ? "Here are the results."
                : assistant.text();
        return ChatAnswer.table(preamble, sql, result.columns(), result.rows(), result.truncated());
    }

    private LlmClient selectClient() {
        for (LlmClient candidate : llmClients) {
            return candidate;
        }
        throw new IllegalStateException(
                "No LLM client is available. Check that a quarkus-langchain4j-* provider extension "
                        + "is on the classpath and configured under quarkus.langchain4j.*");
    }

    /**
     * Keep the most recent exchanges. The window always starts on a user turn: a tool-result turn
     * cannot be first, and an assistant turn must keep the results answering its calls.
     */
    private void trimHistory(List<LlmMessage> history) {
        int maxExchanges = aiConfig.maxHistoryExchanges();

        int exchanges = 0;
        int from = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof LlmMessage.User) {
                exchanges++;
                from = i;
                if (exchanges == maxExchanges) {
                    break;
                }
            }
        }
        if (from <= 0) {
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
