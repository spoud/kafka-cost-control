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
 * @param sessionId  client-generated conversation id; the assistant keys its history on this
 * @param message    the question to answer
 * @param priorTurns how many earlier questions the client is displaying for this session. Lets the
 *                   server detect that the client is showing a transcript it no longer remembers -
 *                   its history is in memory only, so a restart or a hot reload drops it while the
 *                   browser's copy survives. Optional; omit or 0 for a fresh conversation.
 */
@RegisterForReflection
public record ChatRequest(
        @NonNull @Description("Client-generated conversation id; history is kept per session.")
        String sessionId,
        @NonNull @Description("The question to answer.")
        String message,
        @Description("How many earlier questions the client is showing for this session.")
        Integer priorTurns) {

    public int priorTurnsOrZero() {
        return priorTurns == null ? 0 : priorTurns;
    }
}
