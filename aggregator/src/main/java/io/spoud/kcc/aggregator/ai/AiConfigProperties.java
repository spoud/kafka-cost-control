package io.spoud.kcc.aggregator.ai;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.time.Duration;

/**
 * Configuration for the optional AI assistant module, which answers natural-language questions
 * about the data in the OLAP database.
 * <p>
 * The module requires {@code cc.olap.enabled=true} as well, since every question is ultimately
 * answered from the DuckDB table.
 * <p>
 * <b>Model and provider selection is not here</b> — it belongs to LangChain4j under
 * {@code quarkus.langchain4j.*}. Which provider is available is decided by the
 * {@code quarkus-langchain4j-*} artifact on the classpath. For example:
 * <pre>
 * quarkus.langchain4j.anthropic.api-key=...
 * quarkus.langchain4j.anthropic.chat-model.model-name=claude-opus-4-5
 * </pre>
 */
@ConfigMapping(prefix = "cc.ai")
public interface AiConfigProperties {
    @WithName("enabled")
    @WithDefault("false")
    boolean enabled();

    /**
     * Private mode: query results are never sent to the model.
     * <p>
     * The model still sees the schema shape (metric names and context keys) and writes the SQL,
     * but the rows it produces go straight to the user instead of back into the conversation.
     * Tools that would reveal actual values are withdrawn. The trade-off is that the assistant
     * cannot summarise, interpret, or self-correct from what the query returned — the answer is a
     * table rather than a sentence.
     *
     * @see SchemaDescriber#buildSystemPrompt()
     */
    @WithName("private-mode")
    @WithDefault("false")
    boolean privateMode();

    /**
     * Maximum number of rows any single model-authored query may return. Also caps how much
     * data gets fed back into the model's context (outside private mode).
     */
    @WithName("max-rows")
    @WithDefault("500")
    int maxRows();

    /**
     * Maximum number of tool round-trips per user question. Bounds both runaway loops and the
     * cost of a single question.
     */
    @WithName("max-tool-iterations")
    @WithDefault("8")
    int maxToolIterations();

    /**
     * Wall-clock limit for a single model-authored query.
     * <p>
     * This bounds a runaway query against the shared DuckDB instance, so it should stay finite —
     * but it has to be realistic for an analytical scan over a production-sized table. 10s was
     * comfortable against synthetic data and far too tight against a few hundred MB.
     */
    @WithName("query-timeout")
    @WithDefault("PT30S")
    Duration queryTimeout();

    /**
     * How many conversations to keep in memory. Oldest are evicted first.
     */
    @WithName("max-sessions")
    @WithDefault("100")
    int maxSessions();

    /**
     * How many messages to retain per conversation before dropping the oldest turns.
     */
    @WithName("max-history-messages")
    @WithDefault("40")
    int maxHistoryMessages();
}
