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
     * Query results are never sent to the model: rows go straight to the user, and tools that
     * reveal values are withdrawn. Answers become tables rather than prose.
     */
    @WithName("private-mode")
    @WithDefault("false")
    boolean privateMode();

    // Model settings. Feed quarkus.langchain4j.openai.* directly.

    /** OpenAI-compatible endpoint. Selects the provider, e.g. {@code http://localhost:11434/v1}. */
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

    /** Per-request timeout. Provider defaults of a few seconds are too short for a local model. */
    @WithName("request-timeout")
    @WithDefault("PT10M")
    Duration requestTimeout();

    /** Row cap per query. Also bounds how much data re-enters the model's context. */
    @WithName("max-rows")
    @WithDefault("500")
    int maxRows();

    /** Tool round-trips per question. Bounds runaway loops and per-question cost. */
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
     * Exchanges (a question plus everything done to answer it) retained per conversation.
     * Counted in exchanges, not messages: one question can add a dozen messages via tool calls.
     */
    @WithName("max-history-exchanges")
    @WithDefault("10")
    int maxHistoryExchanges();
}
