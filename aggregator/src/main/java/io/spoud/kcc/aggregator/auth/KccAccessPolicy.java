package io.spoud.kcc.aggregator.auth;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;

/**
 * The one gate in front of every endpoint ({@code quarkus.http.auth.permission.app}): nothing in
 * {@code none} mode, an allowed signed-in user otherwise. Resources carry no
 * {@code @Authenticated} of their own, since that would keep parts closed in {@code none} mode.
 * An anonymous request is challenged (the browser goes to the sign-in), a signed-in user who
 * isn't allowed gets 403.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class KccAccessPolicy implements HttpSecurityPolicy {

    private final AuthConfigProperties config;

    @Override
    public Uni<CheckResult> checkPermission(RoutingContext request, Uni<SecurityIdentity> identity,
                                            AuthorizationRequestContext requestContext) {
        if (config.mode() == AuthConfigProperties.Mode.NONE) {
            return CheckResult.permit();
        }
        return identity.map(id -> AccessRules.isAllowed(id, config) ? CheckResult.PERMIT : CheckResult.DENY);
    }

    @Override
    public String name() {
        return "kcc";
    }
}
