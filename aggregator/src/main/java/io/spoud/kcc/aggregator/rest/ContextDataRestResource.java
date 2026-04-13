package io.spoud.kcc.aggregator.rest;

import io.quarkus.security.Authenticated;
import io.spoud.kcc.aggregator.data.ContextDataEntity;
import io.spoud.kcc.aggregator.data.ContextTestResponse;
import io.spoud.kcc.aggregator.graphql.data.ContextDataDeleteRequest;
import io.spoud.kcc.aggregator.graphql.data.ContextDataSaveRequest;
import io.spoud.kcc.aggregator.repository.ContextDataRepository;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/context-data")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor
public class ContextDataRestResource {
    private final ContextDataRepository contextDataRepository;

    @GET
    @PermitAll
    public List<ContextDataEntity> contextData() {
        return contextDataRepository.getContextObjects();
    }

    @GET
    @Path("/test")
    @Authenticated
    public List<ContextTestResponse> testContextData(@QueryParam("testString") String testString) {
        return contextDataRepository.testContext(testString);
    }

    @POST
    @Authenticated
    public ContextDataEntity saveContextData(@Valid ContextDataSaveRequest request) {
        return contextDataRepository.save(request.id(), request.toAvro());
    }

    @DELETE
    @Authenticated
    public ContextDataEntity deleteContextData(ContextDataDeleteRequest request) {
        return contextDataRepository.deleteContext(request.id());
    }
}
