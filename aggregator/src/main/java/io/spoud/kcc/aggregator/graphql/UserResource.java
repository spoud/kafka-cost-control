package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

@GraphQLApi
@RequiredArgsConstructor
@Authenticated
@RequestScoped
public class UserResource {

    @Inject
    SecurityIdentity securityIdentity;


    @Query("currentUser")
    public @NonNull String currentUser() {
        return securityIdentity.getPrincipal().getName();
    }
}
