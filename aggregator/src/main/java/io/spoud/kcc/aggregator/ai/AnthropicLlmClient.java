package io.spoud.kcc.aggregator.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.logging.Log;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link LlmClient} backed by the official Anthropic Java SDK.
 * <p>
 * Two things here are load-bearing and easy to get wrong:
 * <ul>
 *   <li><b>Thinking is left at its default (on).</b> {@code temperature}/{@code top_p}/{@code top_k}
 *       are rejected outright by current models, and — more subtly — with thinking <em>disabled</em>
 *       the model can write a tool call into visible text instead of emitting a real tool_use block,
 *       so the call silently never runs. Cost is controlled with {@code effort} instead.</li>
 *   <li><b>Assistant turns are replayed verbatim</b> via {@link Message#toParam()} rather than
 *       reconstructed from text. Thinking blocks must be echoed back unchanged; rebuilding them
 *       would corrupt the conversation.</li>
 * </ul>
 * The system prompt is marked for prompt caching. It is identical on every turn of a conversation
 * and comfortably exceeds the cache minimum, so this is close to free and removes most of the
 * per-turn input cost.
 */
@ApplicationScoped
@LookupIfProperty(name = "cc.ai.provider", stringValue = "anthropic")
public class AnthropicLlmClient implements LlmClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AiConfigProperties config;
    private volatile AnthropicClient client;

    public AnthropicLlmClient(AiConfigProperties config) {
        this.config = config;
    }

    @Override
    public String providerName() {
        return "anthropic";
    }

    @PreDestroy
    void close() {
        AnthropicClient c = client;
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                Log.debug("Failed to close Anthropic client", e);
            }
        }
    }

    private AnthropicClient client() {
        AnthropicClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    String apiKey = config.anthropicApiKey()
                            .filter(k -> !k.isBlank())
                            .orElseThrow(() -> new IllegalStateException(
                                    "cc.ai.anthropic.api-key is not set. Set it (or ANTHROPIC_API_KEY) to use the assistant."));
                    var builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
                    config.anthropicBaseUrl().filter(u -> !u.isBlank()).ifPresent(builder::baseUrl);
                    local = builder.build();
                    client = local;
                }
            }
        }
        return local;
    }

    @Override
    public LlmMessage.Assistant chat(String systemPrompt, List<LlmMessage> history, List<LlmTool> tools) {
        var params = MessageCreateParams.builder()
                .model(config.anthropicModel())
                .maxTokens(config.anthropicMaxTokens())
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.of(config.anthropicEffort().toLowerCase(Locale.ROOT)))
                        .build())
                // Cache the system prompt: it is byte-identical across turns of a conversation.
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(systemPrompt)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()));

        for (LlmTool tool : tools) {
            params.addTool(toAnthropicTool(tool));
        }
        for (MessageParam message : toAnthropicMessages(history)) {
            params.addMessage(message);
        }

        Message response = client().messages().create(params.build());
        return toAssistantMessage(response);
    }

    private Tool toAnthropicTool(LlmTool tool) {
        var properties = Tool.InputSchema.Properties.builder();
        tool.properties().forEach((name, schema) -> properties.putAdditionalProperty(name, JsonValue.from(schema)));

        return Tool.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(properties.build())
                        .required(tool.required())
                        .build())
                .build();
    }

    private List<MessageParam> toAnthropicMessages(List<LlmMessage> history) {
        var out = new ArrayList<MessageParam>(history.size());
        for (LlmMessage message : history) {
            switch (message) {
                case LlmMessage.User user -> out.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(user.text())
                        .build());

                case LlmMessage.Assistant assistant -> {
                    // Replay the provider's own representation so thinking blocks survive intact.
                    if (assistant.raw() instanceof MessageParam raw) {
                        out.add(raw);
                    } else if (assistant.text() != null && !assistant.text().isBlank()) {
                        out.add(MessageParam.builder()
                                .role(MessageParam.Role.ASSISTANT)
                                .content(assistant.text())
                                .build());
                    }
                }

                case LlmMessage.ToolResults toolResults -> {
                    var blocks = toolResults.results().stream()
                            .map(r -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                                    .toolUseId(r.id())
                                    .content(r.content())
                                    .isError(r.isError())
                                    .build()))
                            .toList();
                    if (!blocks.isEmpty()) {
                        out.add(MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .contentOfBlockParams(blocks)
                                .build());
                    }
                }
            }
        }
        return out;
    }

    private LlmMessage.Assistant toAssistantMessage(Message response) {
        var text = new StringBuilder();
        var toolCalls = new ArrayList<LlmMessage.ToolCall>();

        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t -> {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(t.text());
            });
            block.toolUse().ifPresent(use -> toolCalls.add(
                    new LlmMessage.ToolCall(use.id(), use.name(), readInput(use._input()))));
        }

        return new LlmMessage.Assistant(text.toString(), toolCalls, response.toParam());
    }

    private Map<String, Object> readInput(JsonValue input) {
        try {
            Map<String, Object> converted = input.convert(MAP_TYPE);
            return converted == null ? Map.of() : new LinkedHashMap<>(converted);
        } catch (Exception e) {
            Log.warn("Failed to read tool input as an object", e);
            return Map.of();
        }
    }
}
