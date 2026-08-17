package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * One question to the assistant.
 * <p>
 * The two fields are wrapped in a record rather than passed as separate method parameters so the
 * resource can be exposed as both GraphQL and JAX-RS: JAX-RS treats every unannotated parameter as
 * a body parameter and rejects a method that has more than one.
 *
 * @param sessionId client-generated conversation id; the assistant keys its history on this
 * @param message   the question to answer
 */
@RegisterForReflection
public record ChatRequest(
        @NonNull @Description("Client-generated conversation id; history is kept per session.")
        String sessionId,
        @NonNull @Description("The question to answer.")
        String message) {
}
