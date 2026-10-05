package io.spoud.kcc.aggregator.graphql;

import io.spoud.kcc.aggregator.data.ContextDataEntity;
import io.spoud.kcc.aggregator.data.ContextTestResponse;
import io.spoud.kcc.aggregator.data.UnassignedEntity;
import io.spoud.kcc.aggregator.graphql.data.UnassignedEntitiesRequest;
import io.spoud.kcc.aggregator.graphql.data.ContextDataDeleteRequest;
import io.spoud.kcc.aggregator.graphql.data.ContextDataSaveRequest;
import io.spoud.kcc.aggregator.olap.ContextDataOlapRepository;
import io.spoud.kcc.aggregator.repository.ContextDataStreamRepository;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

@Path("/api/v1/context-data")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@GraphQLApi
@RequiredArgsConstructor
public class ContextDataResource {
    private final ContextDataStreamRepository contextDataStreamRepository;
    private final ContextDataOlapRepository contextDataOlapRepository;

    @GET
    @PermitAll
    @Query("contextData")
    public @NonNull List<@NonNull ContextDataEntity> contextData() {
        return contextDataStreamRepository.getContextObjects();
    }

    @GET
    @Path("/test")
    @Query("contextTest")
    public @NonNull List<@NonNull ContextTestResponse> testContextData(@QueryParam("testString") String testString) {
        return contextDataStreamRepository.testContext(testString);
    }

    @POST
    @Mutation("saveContextData")
    public @NonNull ContextDataEntity saveContextData(@Valid ContextDataSaveRequest request) {
        return contextDataStreamRepository.save(request.id(), request.toAvro());
    }

    @DELETE
    @Mutation("deleteContextData")
    public ContextDataEntity deleteContextData(ContextDataDeleteRequest request) {
        return contextDataStreamRepository.deleteContext(request.id());
    }

    @Query("existingContextKeys")
    public @NonNull Set<@NonNull String> getAllExistingContextKeys() {
        return contextDataOlapRepository.getAllExistingContextKeys();
    }

    @Query("unassignedEntities")
    @org.eclipse.microprofile.graphql.Description(
            "Topics and principals no context rule assigns (or without a value for contextKey), most expensive "
                    + "first; no from = the last 7 days")
    public @NonNull List<@NonNull UnassignedEntity> unassignedEntities(UnassignedEntitiesRequest request) {
        var from = request == null || request.from() == null ? Instant.now().minus(7, ChronoUnit.DAYS) : request.from();
        var to = request == null ? null : request.to();
        var key = request == null ? null : request.contextKeyOrNull();
        return contextDataOlapRepository.unassignedEntities(from, to, key);
    }
}
