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
                       Instance<LlmClient> llmClients) {
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
    /**
     * Report the active configuration once at startup, so a wrong provider or model is visible
     * immediately rather than on the first question a user asks.
     */
    void logConfiguration(@Observes StartupEvent event) {
        if (!aiConfig.enabled()) {
            Log.info("AI assistant is disabled (cc.ai.enabled=false)");
            return;
        }
        Log.infof("AI assistant enabled: provider=%s model=%s%s",
                aiConfig.provider(),
                aiConfig.model(),
                aiConfig.privateMode() ? " (private mode: results are not sent to the model)" : "");
    }

    /**
     * Whether a question asked right now would be answered.
     * <p>
     * {@link #ask} consults this rather than repeating the checks, so the status the UI uses to
     * hide the feature cannot drift from the conditions actually enforced when answering.
     * <p>
     * All three conditions matter and produce different fixes: the module can be switched off, the
     * database it queries can be switched off, or no LLM provider extension can be configured.
     */
    public AssistantStatus status() {
        if (!aiConfig.enabled()) {
            return AssistantStatus.unavailable(
                    "The AI assistant is disabled. Set cc.ai.enabled=true to use it.");
        }
        // Every OLAP method returns empty when the module is off, so without this check the
        // assistant would confidently answer "I found no data" instead of "there is no database".
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
        // The client's transcript lives in the browser and outlives ours, which is only in memory.
        // If it is showing turns we have no record of, say so rather than quietly answering
        // without the context the user can see on screen.
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

            // Private mode: the first data-returning call ends the turn. Its rows go to the user,
            // never into the conversation, so the model never sees the contents of the database.
            if (aiConfig.privateMode()) {
                for (LlmMessage.ToolCall call : assistant.toolCalls()) {
                    if (SchemaDescriber.isTerminalTool(call.name())) {
                        ToolRegistry.TerminalResult result = toolRegistry.invokeTerminal(call);
                        // Close every tool call this turn made, without revealing what came back.
                        // The assistant turn is already in history carrying its tool calls; leaving
                        // them unanswered replays a tool_use with no tool_result on the next
                        // question, which strict providers reject outright - and because the
                        // rejection lands in the catch below, which only strips the trailing user
                        // turn, the dangling call would poison every later question in the session.
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
                // The model is asked to introduce the table, but if it went straight to the query
                // the user still needs something above the results.
                ? "Here are the results."
                : assistant.text();
        return ChatAnswer.table(preamble, sql, result.columns(), result.rows(), result.truncated());
    }

    private LlmClient selectClient() {
        for (LlmClient candidate : llmClients) {
            return candidate;
        }
        // Which model and vendor is used is LangChain4j's concern, decided by the
        // quarkus-langchain4j-* artifact on the classpath and quarkus.langchain4j.* config.
        throw new IllegalStateException(
                "No LLM client is available. Check that a quarkus-langchain4j-* provider extension "
                        + "is on the classpath and configured under quarkus.langchain4j.*");
    }

    /**
     * Keep the most recent exchanges, where an exchange is a user question plus everything the
     * assistant did to answer it.
     * <p>
     * The window always starts on a user turn: a tool-result turn cannot be first, and an
     * assistant turn that made tool calls has to keep the results that answer them, or the
     * provider sees a dangling call.
     */
    private void trimHistory(List<LlmMessage> history) {
        int maxExchanges = aiConfig.maxHistoryExchanges();

        // Walk back through the user turns and cut at the start of the oldest one we keep.
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
