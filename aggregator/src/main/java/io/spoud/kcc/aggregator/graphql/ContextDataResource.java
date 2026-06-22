package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.Authenticated;
import io.spoud.kcc.aggregator.data.ContextDataEntity;
import io.spoud.kcc.aggregator.data.ContextTestResponse;
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
    @Authenticated
    @Query("contextTest")
    public @NonNull List<@NonNull ContextTestResponse> testContextData(@QueryParam("testString") String testString) {
        return contextDataStreamRepository.testContext(testString);
    }

    @POST
    @Authenticated
    @Mutation("saveContextData")
    public @NonNull ContextDataEntity saveContextData(@Valid ContextDataSaveRequest request) {
        return contextDataStreamRepository.save(request.id(), request.toAvro());
    }

    @DELETE
    @Authenticated
    @Mutation("deleteContextData")
    public ContextDataEntity deleteContextData(ContextDataDeleteRequest request) {
        return contextDataStreamRepository.deleteContext(request.id());
    }

    @Authenticated
    @Query("existingContextKeys")
    public @NonNull Set<@NonNull String> getAllExistingContextKeys() {
        return contextDataOlapRepository.getAllExistingContextKeys();
    }
}
