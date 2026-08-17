package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.graphql.NonNull;

import java.util.List;

/**
 * The assistant's reply to one question.
 *
 * @param text         the answer in prose, or an explanation of why there is none
 * @param generatedSql every query the assistant ran, in order, including rejected and failed
 *                     attempts (prefixed with a SQL comment saying why). Surfaced in the UI so a
 *                     user can check the reasoning behind a number rather than trusting it blindly
 * @param columns      column headers of the result table, when one is returned
 * @param rows         result rows. Populated in private mode, where the query output goes to the
 *                     user instead of back to the model — there the table <em>is</em> the answer,
 *                     and {@code text} only introduces it
 * @param truncated    true when the result hit the row cap and is not the whole story
 * @param complete     false when the assistant ran out of steps before reaching an answer
 * @param error        set when the question could not be processed at all
 */
@RegisterForReflection
public record ChatAnswer(
        @NonNull String text,
        @NonNull List<@NonNull String> generatedSql,
        @NonNull List<@NonNull String> columns,
        @NonNull List<@NonNull List<String>> rows,
        boolean truncated,
        boolean complete,
        String error) {

    public static ChatAnswer success(String text, List<String> sql) {
        return new ChatAnswer(text, safe(sql), List.of(), List.of(), false, true, null);
    }

    /** Answer whose payload is a table rather than prose — the private-mode shape. */
    public static ChatAnswer table(String text, List<String> sql,
                                   List<String> columns, List<List<String>> rows, boolean truncated) {
        return new ChatAnswer(text, safe(sql), safe(columns),
                rows == null ? List.of() : rows, truncated, true, null);
    }

    public static ChatAnswer partial(String text, List<String> sql) {
        return new ChatAnswer(text, safe(sql), List.of(), List.of(), false, false, null);
    }

    public static ChatAnswer error(String message) {
        return new ChatAnswer("", List.of(), List.of(), List.of(), false, false, message);
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }
}
