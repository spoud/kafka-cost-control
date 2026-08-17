package io.spoud.kcc.aggregator.ai;

import java.util.List;
import java.util.Map;

/**
 * Provider-neutral tool declaration.
 *
 * @param name        tool name the model will call
 * @param description what the tool does and, importantly, <em>when</em> to reach for it — the
 *                    trigger condition matters as much as the capability
 * @param properties  JSON-Schema property definitions, keyed by parameter name
 * @param required    which of those parameters are mandatory
 */
public record LlmTool(
        String name,
        String description,
        Map<String, Map<String, Object>> properties,
        List<String> required) {

    public static LlmTool noArgs(String name, String description) {
        return new LlmTool(name, description, Map.of(), List.of());
    }

    public static Map<String, Object> stringParam(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> integerParam(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> stringArrayParam(String description) {
        return Map.of(
                "type", "array",
                "description", description,
                "items", Map.of("type", "string"));
    }
}
