package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.graphql.NonNull;

import java.util.List;

/**
 * The assistant's reply to one question.
 *
 * @param generatedSql every query run, in order, including rejected and failed attempts
 * @param columns      headers of the result table, when one is returned
 * @param rows         result rows. Populated in private mode, where the table is the answer
 * @param truncated    the result hit the row cap
 * @param contextLost  the client was showing questions the assistant no longer remembers
 * @param complete     false when it ran out of steps before answering
 * @param error        set when the question could not be processed at all
 */
@RegisterForReflection
public record ChatAnswer(
        @NonNull String text,
        @NonNull List<@NonNull String> generatedSql,
        @NonNull List<@NonNull String> columns,
        @NonNull List<@NonNull List<String>> rows,
        boolean truncated,
        boolean contextLost,
        boolean complete,
        String error) {

    public static ChatAnswer success(String text, List<String> sql) {
        return new ChatAnswer(text, safe(sql), List.of(), List.of(), false, false, true, null);
    }

    /** Payload is a table rather than prose — the private-mode shape. */
    public static ChatAnswer table(String text, List<String> sql,
                                   List<String> columns, List<List<String>> rows, boolean truncated) {
        return new ChatAnswer(text, safe(sql), safe(columns),
                rows == null ? List.of() : rows, truncated, false, true, null);
    }

    public static ChatAnswer partial(String text, List<String> sql) {
        return new ChatAnswer(text, safe(sql), List.of(), List.of(), false, false, false, null);
    }

    public static ChatAnswer error(String message) {
        return new ChatAnswer("", List.of(), List.of(), List.of(), false, false, false, message);
    }

    /** Flagged as having started from an empty history the client did not expect. */
    public ChatAnswer withContextLost() {
        return new ChatAnswer(text, generatedSql, columns, rows, truncated, true, complete, error);
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }
}
