package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * Whether the assistant can actually answer anything in this deployment.
 * <p>
 * Exists so the UI can hide the feature rather than offering a chat box that only reveals it is
 * switched off after the user has typed a question.
 *
 * @param available true when a question would be answered
 * @param reason    why it is unavailable, phrased for whoever has to fix the configuration.
 *                  Null when available.
 */
@RegisterForReflection
public record AssistantStatus(boolean available, String reason) {

    public static AssistantStatus ready() {
        return new AssistantStatus(true, null);
    }

    public static AssistantStatus unavailable(@NonNull String reason) {
        return new AssistantStatus(false, reason);
    }
}
