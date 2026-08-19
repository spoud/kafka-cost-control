package io.spoud.kcc.aggregator.ai;

import java.util.List;

/**
 * Port to an LLM provider. One implementation ships today ({@link AnthropicLlmClient}); the
 * interface exists so a self-hosted model can be added later without touching {@link ChatService}.
 * <p>
 * Implementations are stateless with respect to conversation: the full history is passed on
 * every call, matching how the underlying APIs actually work.
 */
public interface LlmClient {

    /**
     * Run one model turn.
     *
     * @param systemPrompt stable instructions and schema description; implementations should
     *                     prompt-cache this, since it is identical across turns
     * @param history      the conversation so far, oldest first
     * @param tools        tools the model may call this turn
     * @return the model's turn, which either answers or requests tool calls
     */
    LlmMessage.Assistant chat(String systemPrompt, List<LlmMessage> history, List<LlmTool> tools);
}
