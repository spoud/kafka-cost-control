package io.spoud.kcc.aggregator.ai;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.time.Duration;
import java.util.Optional;

/**
 * Configuration for the optional AI assistant module, which answers natural-language questions
 * about the data in the OLAP database.
 * <p>
 * The module requires {@code cc.olap.enabled=true} as well, since every question is ultimately
 * answered from the DuckDB table.
 */
@ConfigMapping(prefix = "cc.ai")
public interface AiConfigProperties {
    @WithName("enabled")
    @WithDefault("false")
    boolean enabled();

    /**
     * Which LLM provider to use. Selects the {@link LlmClient} implementation.
     * Currently only "anthropic" is implemented.
     */
    @WithName("provider")
    @WithDefault("anthropic")
    String provider();

    /**
     * API key for Anthropic. Without it the module stays disabled even if {@code enabled=true},
     * so that a misconfigured deployment fails loudly at startup rather than on the first question.
     */
    @WithName("anthropic.api-key")
    Optional<String> anthropicApiKey();

    /**
     * Model id, e.g. "claude-opus-5" or "claude-sonnet-5".
     */
    @WithName("anthropic.model")
    @WithDefault("claude-opus-5")
    String anthropicModel();

    /**
     * Reasoning effort: low | medium | high | xhigh | max. Higher means better SQL on hard
     * questions but more tokens. This is the main cost/quality dial.
     */
    @WithName("anthropic.effort")
    @WithDefault("high")
    String anthropicEffort();

    /**
     * Upper bound on tokens produced per model call. Note this covers thinking *and* the visible
     * response, so it needs headroom above what the answer alone would need.
     */
    @WithName("anthropic.max-tokens")
    @WithDefault("8000")
    long anthropicMaxTokens();

    /**
     * Optional override of the Anthropic API base URL (for a proxy or gateway).
     */
    @WithName("anthropic.base-url")
    Optional<String> anthropicBaseUrl();

    /**
     * Maximum number of rows any single model-authored query may return. Also caps how much
     * data gets fed back into the model's context.
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
     */
    @WithName("query-timeout")
    @WithDefault("PT10S")
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
