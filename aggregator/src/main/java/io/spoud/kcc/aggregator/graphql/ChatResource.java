package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.spoud.kcc.aggregator.ai.ChatService;
import io.spoud.kcc.aggregator.graphql.data.AssistantStatus;
import io.spoud.kcc.aggregator.graphql.data.ChatAnswer;
import io.spoud.kcc.aggregator.graphql.data.ChatRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * Natural-language querying of the OLAP data. Dual-annotated as GraphQL and REST, matching
 * {@link MetricsResource}; the question is a single {@link ChatRequest} because JAX-RS permits
 * only one body parameter.
 * <p>
 * {@code @Authenticated} must stay: this project has no {@code deny-unannotated}, so an endpoint
 * without it is fully public.
 */
@Path("/api/v1/chat")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@GraphQLApi
@Authenticated
// @RequestScoped, matching UserResource: this reads the per-request SecurityIdentity, and an
// explicit scope is better than relying on whatever default the JAX-RS and GraphQL layers agree on.
@RequestScoped
@RequiredArgsConstructor
public class ChatResource {

    private final ChatService chatService;
    private final SecurityIdentity securityIdentity;

    /**
     * Conversation histories are keyed on this, never on the session id alone. The id is generated
     * by the client, so without the principal any caller could pass someone else's id and read, or
     * clear, their conversation.
     */
    static String conversationKey(String principalName, String sessionId) {
        return (principalName == null ? "" : principalName) + '\u0000' + sessionId;
    }

    private String conversationKey(String sessionId) {
        var principal = securityIdentity.getPrincipal();
        return conversationKey(principal == null ? null : principal.getName(), sessionId);
    }

    /**
     * Lets the UI hide the assistant when this deployment cannot answer. {@code @PermitAll}: it
     * exposes only whether a feature is on, and the nav decides before knowing anything else.
     */
    @GET
    @Path("/status")
    @PermitAll
    @Query("assistantStatus")
    @Description("Whether the AI assistant is configured and able to answer questions.")
    public @NonNull AssistantStatus assistantStatus() {
        return chatService.status();
    }

    @POST
    @Mutation("chat")
    @Description("Ask a natural-language question about the aggregated metrics data.")
    public @NonNull ChatAnswer chat(ChatRequest request) {
        // The GraphQL argument is nullable and the REST body may be empty.
        if (request == null) {
            return ChatAnswer.error("No question was supplied.");
        }
        return chatService.ask(
                conversationKey(request.sessionId()), request.message(), request.priorTurnsOrZero());
    }

    @DELETE
    @Path("/{sessionId}")
    @Mutation("clearChat")
    @Description("Forget the conversation history for one session.")
    public @NonNull Boolean clearChat(
            @Name("sessionId") @PathParam("sessionId") @NonNull String sessionId) {
        chatService.clear(conversationKey(sessionId));
        return true;
    }
}
