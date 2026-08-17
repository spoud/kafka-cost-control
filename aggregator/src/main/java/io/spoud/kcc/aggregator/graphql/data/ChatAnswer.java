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
 * @param complete     false when the assistant ran out of steps before reaching an answer
 * @param error        set when the question could not be processed at all
 */
@RegisterForReflection
public record ChatAnswer(
        @NonNull String text,
        @NonNull List<@NonNull String> generatedSql,
        boolean complete,
        String error) {

    public static ChatAnswer success(String text, List<String> sql) {
        return new ChatAnswer(text, sql == null ? List.of() : sql, true, null);
    }

    public static ChatAnswer partial(String text, List<String> sql) {
        return new ChatAnswer(text, sql == null ? List.of() : sql, false, null);
    }

    public static ChatAnswer error(String message) {
        return new ChatAnswer("", List.of(), false, message);
    }
}
