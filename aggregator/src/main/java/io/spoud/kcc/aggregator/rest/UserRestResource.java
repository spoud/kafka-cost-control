package io.spoud.kcc.aggregator.rest;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;

@Path("/user")
@Produces(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor
@Authenticated
@RequestScoped
public class UserRestResource {

    @Inject
    SecurityIdentity securityIdentity;

    @GET
    @Path("/current")
    public String currentUser() {
        return securityIdentity.getPrincipal().getName();
    }
}
