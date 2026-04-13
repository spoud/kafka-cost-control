package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

@Path("/user/current")
@Produces(MediaType.APPLICATION_JSON)
@GraphQLApi
@RequiredArgsConstructor
@Authenticated
@RequestScoped
public class UserResource {

    @Inject
    SecurityIdentity securityIdentity;

    @GET
    @Query("currentUser")
    public @NonNull String currentUser() {
        return securityIdentity.getPrincipal().getName();
    }
}
