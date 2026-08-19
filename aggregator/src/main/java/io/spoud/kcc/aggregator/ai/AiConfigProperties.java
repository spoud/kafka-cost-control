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
 * Which model is used is decided entirely by {@link #baseUrl()} and {@link #model()}. Every
 * vendor speaks the OpenAI chat-completions protocol, so changing provider is a URL rather than a
 * dependency — see the table in {@code application.yaml}.
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

    // --- Model settings ------------------------------------------------------------------------
    // The whole configuration surface for choosing a model, whichever vendor is used. They feed
    // quarkus.langchain4j.openai.* directly — one client, so there is no per-vendor name mapping.

    /**
     * Base URL of an OpenAI-compatible chat-completions endpoint. This is what selects the
     * provider: {@code http://localhost:11434/v1} for a local Ollama,
     * {@code https://api.anthropic.com/v1/} for Claude, and so on.
     */
    @WithName("base-url")
    @WithDefault("http://localhost:11434/v1")
    String baseUrl();

    /** Model identifier, in whatever form the endpoint expects. Must support tool calling. */
    @WithName("model")
    @WithDefault("qwen3-coder:latest")
    String model();

    /** API key for providers that need one. Ignored by those that do not, such as Ollama. */
    @WithName("api-key")
    @WithDefault("not-configured")
    String apiKey();

    /**
     * Per-request timeout. A local model has to load into memory and then write SQL across
     * several tool round-trips, so provider defaults of a few seconds are far too short.
     */
    @WithName("request-timeout")
    @WithDefault("PT10M")
    Duration requestTimeout();

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
     * How many completed exchanges (question plus everything the assistant did to answer it) to
     * retain per conversation.
     * <p>
     * Counted in exchanges rather than raw messages on purpose: one question can add a dozen or
     * more messages once tool round-trips are counted, so a raw message cap makes the retained
     * history depend on how tool-heavy the recent questions happened to be. A user who asks three
     * questions expects three questions to be remembered, regardless of how hard each was.
     */
    @WithName("max-history-exchanges")
    @WithDefault("10")
    int maxHistoryExchanges();
}
