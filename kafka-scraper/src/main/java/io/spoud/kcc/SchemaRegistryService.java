package io.spoud.kcc;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.Set;

@Path("/schemas")
@RegisterRestClient(configKey = "schema-registry-api")
@RegisterClientHeaders(ConfluentAuthHeader.class)
public interface SchemaRegistryService {

    @GET
    Uni<Set<Schema>> getAll();
}
