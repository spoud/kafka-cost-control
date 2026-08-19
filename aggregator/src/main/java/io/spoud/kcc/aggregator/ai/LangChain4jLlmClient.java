package io.spoud.kcc.aggregator.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.quarkus.arc.Unremovable;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LlmClient} backed by LangChain4j.
 * <p>
 * A single client reaches every vendor: Ollama, OpenAI, Anthropic, Gemini, OpenRouter, Groq, vLLM
 * and LM Studio all speak the OpenAI chat-completions protocol, so changing provider is a base URL
 * rather than a dependency. See {@code cc.ai.base-url} and the table in {@code application.yaml}.
 * <p>
 * The {@link LlmClient} port is kept in front of it so {@link ChatService} stays free of LangChain4j
 * types — useful if a provider ever needs to be driven by its native SDK instead.
 */
// @Unremovable is required, not decorative: ChatService looks this bean up programmatically
// through Instance<LlmClient>, which ArC's unused-bean detection does not count as an injection
// point. Without it the bean is dropped at build time and the assistant reports that no LLM
// client is available — at runtime only.
@Unremovable
@ApplicationScoped
public class LangChain4jLlmClient implements LlmClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LangChain4jLlmClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public LlmMessage.Assistant chat(String systemPrompt, List<LlmMessage> history, List<LlmTool> tools) {
        var request = ChatRequest.builder()
                .messages(toChatMessages(systemPrompt, history))
                .toolSpecifications(tools.stream().map(this::toToolSpecification).toList())
                .build();

        AiMessage answer = chatModel.chat(request).aiMessage();

        var toolCalls = new ArrayList<LlmMessage.ToolCall>();
        if (answer.hasToolExecutionRequests()) {
            for (ToolExecutionRequest req : answer.toolExecutionRequests()) {
                toolCalls.add(new LlmMessage.ToolCall(req.id(), req.name(), parseArguments(req)));
            }
        }
        // Replay the provider's own message verbatim: tool-call ids must survive intact.
        return new LlmMessage.Assistant(answer.text() == null ? "" : answer.text(), toolCalls, answer);
    }

    private List<ChatMessage> toChatMessages(String systemPrompt, List<LlmMessage> history) {
        var messages = new ArrayList<ChatMessage>(history.size() + 1);
        messages.add(SystemMessage.from(systemPrompt));

        for (LlmMessage message : history) {
            switch (message) {
                case LlmMessage.User user -> messages.add(UserMessage.from(user.text()));

                case LlmMessage.Assistant assistant -> {
                    if (assistant.raw() instanceof AiMessage raw) {
                        messages.add(raw);
                    } else if (assistant.text() != null && !assistant.text().isBlank()) {
                        messages.add(AiMessage.from(assistant.text()));
                    }
                }

                case LlmMessage.ToolResults toolResults -> {
                    for (LlmMessage.ToolResult result : toolResults.results()) {
                        // No is-error flag on a tool result; prefix so a failure is distinguishable
                        // from a legitimate empty answer.
                        String content = result.isError() ? "ERROR: " + result.content() : result.content();
                        messages.add(ToolExecutionResultMessage.from(result.id(), null, content));
                    }
                }
            }
        }
        return messages;
    }

    private ToolSpecification toToolSpecification(LlmTool tool) {
        var parameters = JsonObjectSchema.builder();
        tool.properties().forEach((name, schema) -> parameters.addProperty(name, toSchemaElement(schema)));
        if (!tool.required().isEmpty()) {
            parameters.required(tool.required());
        }
        return ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(parameters.build())
                .build();
    }

    /** Provider-neutral JSON-Schema fragment to LangChain4j's typed equivalent. */
    private JsonSchemaElement toSchemaElement(Map<String, Object> schema) {
        String type = String.valueOf(schema.getOrDefault("type", "string"));
        String description = schema.get("description") == null ? null : String.valueOf(schema.get("description"));

        return switch (type) {
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "array" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> items = schema.get("items") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : Map.of("type", "string");
                yield JsonArraySchema.builder()
                        .description(description)
                        .items(toSchemaElement(items))
                        .build();
            }
            default -> JsonStringSchema.builder().description(description).build();
        };
    }

    /** Arguments arrive as a JSON string; malformed ones are the model's error, not a crash. */
    private Map<String, Object> parseArguments(ToolExecutionRequest request) {
        String arguments = request.arguments();
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(arguments, MAP_TYPE);
            return parsed == null ? Map.of() : new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            Log.warnf("Could not parse tool arguments for '%s': %s", request.name(), arguments);
            return Map.of();
        }
    }
}
