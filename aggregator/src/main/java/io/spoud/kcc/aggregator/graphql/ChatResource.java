package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.Authenticated;
import io.spoud.kcc.aggregator.ai.ChatService;
import io.spoud.kcc.aggregator.graphql.data.ChatAnswer;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * Natural-language querying of the OLAP data.
 * <p>
 * Dual-annotated as GraphQL and REST, matching {@link MetricsResource}.
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

    @POST
    @Mutation("chat")
    @Description("Ask a natural-language question about the aggregated metrics data.")
    public @NonNull ChatAnswer chat(
            @Name("sessionId") @Description("Client-generated conversation id; history is kept per session.")
            @NonNull String sessionId,
            @Name("message") @Description("The question to answer.")
            @NonNull String message) {
        return chatService.ask(sessionId, message);
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
