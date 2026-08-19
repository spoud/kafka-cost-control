package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.Authenticated;
import io.spoud.kcc.aggregator.ai.ChatService;
import io.spoud.kcc.aggregator.graphql.data.AssistantStatus;
import io.spoud.kcc.aggregator.graphql.data.ChatAnswer;
import io.spoud.kcc.aggregator.graphql.data.ChatRequest;
import jakarta.annotation.security.PermitAll;
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
@RequiredArgsConstructor
public class ChatResource {

    private final ChatService chatService;

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
        return chatService.ask(request.sessionId(), request.message(), request.priorTurnsOrZero());
    }

    @DELETE
    @Path("/{sessionId}")
    @Mutation("clearChat")
    @Description("Forget the conversation history for one session.")
    public @NonNull Boolean clearChat(
            @Name("sessionId") @PathParam("sessionId") @NonNull String sessionId) {
        chatService.clear(sessionId);
        return true;
    }
}
