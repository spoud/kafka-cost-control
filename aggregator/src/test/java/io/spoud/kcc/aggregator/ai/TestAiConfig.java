package io.spoud.kcc.aggregator.ai;

import java.time.Duration;

/**
 * One fake for {@link AiConfigProperties}, with every value defaulted and overridable.
 * <p>
 * Three tests previously hand-implemented the interface, so every property added to it broke all
 * three at once — which is how the module came to sit un-compilable. Adding a property now means
 * adding it here, and nowhere else.
 */
public class TestAiConfig implements AiConfigProperties {

    public boolean enabled = true;
    public boolean privateMode = false;
    public String baseUrl = "http://localhost:11434/v1";
    public String model = "test-model";
    public String apiKey = "test";
    public Duration requestTimeout = Duration.ofSeconds(60);
    public Duration queryTimeout = Duration.ofSeconds(30);
    public int maxRows = 100;
    public int maxToolIterations = 6;
    public int maxSessions = 10;
    public int maxHistoryExchanges = 10;
    public int maxTokensPerQuestion = 0; // no ceiling unless a test asks for one

    @Override public boolean enabled() { return enabled; }
    @Override public boolean privateMode() { return privateMode; }
    @Override public String baseUrl() { return baseUrl; }
    @Override public String model() { return model; }
    @Override public String apiKey() { return apiKey; }
    @Override public Duration requestTimeout() { return requestTimeout; }
    @Override public Duration queryTimeout() { return queryTimeout; }
    @Override public int maxRows() { return maxRows; }
    @Override public int maxToolIterations() { return maxToolIterations; }
    @Override public int maxSessions() { return maxSessions; }
    @Override public int maxHistoryExchanges() { return maxHistoryExchanges; }
    @Override public int maxTokensPerQuestion() { return maxTokensPerQuestion; }
}
