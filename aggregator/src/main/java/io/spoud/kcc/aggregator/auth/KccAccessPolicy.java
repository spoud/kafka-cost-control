package io.spoud.kcc.aggregator.auth;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import lombok.RequiredArgsConstructor;

/**
 * The one gate in front of every endpoint ({@code quarkus.http.auth.permission.app}): nothing in
 * {@code none} mode, an allowed signed-in user otherwise. Resources carry no
 * {@code @Authenticated} of their own, since that would keep parts closed in {@code none} mode.
 * An anonymous request is challenged (the browser goes to the sign-in), a signed-in user who
 * isn't allowed gets 403 - except on {@code /auth/login}, where the provider sends every user
 * back after signing in: from there the UI explains that the account has no access.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class KccAccessPolicy implements HttpSecurityPolicy {

    static final String LOGIN_PATH = "/auth/login";

    private final AuthConfigProperties config;

    @Override
    public Uni<CheckResult> checkPermission(RoutingContext request, Uni<SecurityIdentity> identity,
                                            AuthorizationRequestContext requestContext) {
        if (config.mode() == AuthConfigProperties.Mode.NONE) {
            return CheckResult.permit();
        }
        boolean login = LOGIN_PATH.equals(request.normalizedPath());
        return identity.map(id -> AccessRules.isAllowed(id, config) || (login && !id.isAnonymous())
                ? CheckResult.PERMIT : CheckResult.DENY);
    }

    /**
     * Says at startup who may use the application. In {@code none} mode that is everyone who can
     * reach it, changes and reprocessing included, which is only right behind a proxy that
     * authenticates every request - so it is a warning, not a note.
     */
    void logMode(@Observes StartupEvent event) {
        if (config.mode() == AuthConfigProperties.Mode.NONE) {
            Log.warn(NONE_MODE_WARNING);
        } else {
            Log.infof("Sign-in required for every request (cc.auth.mode=%s)", config.mode().name().toLowerCase());
        }
    }

    static final String NONE_MODE_WARNING = "Sign-in is off (cc.auth.mode=none): anyone who can reach this "
            + "instance can read all data, change pricing and context rules and start a reprocess, which "
            + "deletes stored data. Use it only behind a proxy that authenticates every request.";

    @Override
    public String name() {
        return "kcc";
    }
}
