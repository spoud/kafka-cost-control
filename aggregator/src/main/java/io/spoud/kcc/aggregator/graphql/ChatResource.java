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
 * Natural-language querying of the OLAP data.
 * <p>
 * Dual-annotated as GraphQL and REST, matching {@link MetricsResource}. The question is taken as a
 * single {@link ChatRequest} rather than two parameters because JAX-RS permits only one body
 * parameter per method — the same reason {@link CostsResource} takes a request object.
 * <p>
 * {@code @Authenticated} is required and must stay: this project has no
 * {@code quarkus.http.auth.permission.*} block and no {@code deny-unannotated}, so an endpoint
 * without it is fully public — as {@code /olap/export} currently is.
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
     * Lets the UI hide the assistant when this deployment cannot answer, instead of presenting a
     * chat box that only reveals it is switched off once a question has been typed.
     * <p>
     * {@code @PermitAll} because it exposes nothing but whether a feature is switched on, and the
     * navigation needs to decide before it knows anything else about the user.
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
        // The GraphQL argument is nullable and the REST body may be empty, so this is reachable.
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
