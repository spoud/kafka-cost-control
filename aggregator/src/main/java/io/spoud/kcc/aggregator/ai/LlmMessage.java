package io.spoud.kcc.aggregator.ai;

import java.util.List;
import java.util.Map;

/** Provider-neutral conversation element: enough to express a tool-use loop, and no more. */
public sealed interface LlmMessage {

    /** A question or instruction from the human. */
    record User(String text) implements LlmMessage {
    }

    /**
     * A model turn. {@code raw} carries the provider's own representation so it can be replayed
     * verbatim; reconstructing it from {@code text} would corrupt the conversation.
     */
    record Assistant(String text, List<ToolCall> toolCalls, Object raw) implements LlmMessage {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /** Results for every tool call in the immediately preceding {@link Assistant} turn. */
    record ToolResults(List<ToolResult> results) implements LlmMessage {
    }

    /** A single tool invocation requested by the model. */
    record ToolCall(String id, String name, Map<String, Object> input) {
    }

    /** The outcome of one {@link ToolCall}, matched back by {@code id}. */
    record ToolResult(String id, String content, boolean isError) {
        public static ToolResult ok(String id, String content) {
            return new ToolResult(id, content, false);
        }

        public static ToolResult error(String id, String message) {
            return new ToolResult(id, message, true);
        }
    }
}
