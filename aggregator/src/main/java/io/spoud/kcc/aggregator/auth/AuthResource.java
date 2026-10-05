package io.spoud.kcc.aggregator.auth;

import io.quarkus.oidc.OidcSession;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.net.URI;
import java.util.Locale;

/**
 * Sign-in for the UI. {@code /auth/me} is open, so the UI can find out which sign-in to offer
 * before anything else. {@code /auth/login} is behind {@link KccAccessPolicy}: an anonymous
 * browser is sent to the identity provider, comes back on {@code /auth/callback} (handled by
 * Quarkus OIDC) and from there is redirected here, then on to the page it came from.
 */
@Path("/auth")
public class AuthResource {

    @Inject
    AuthConfigProperties config;

    @Inject
    SecurityIdentity identity;

    @Inject
    Instance<OidcSession> oidcSession;

    public record AuthStatus(String mode, boolean authenticated, boolean allowed, String user) {
    }

    @GET
    @Path("me")
    @Produces(MediaType.APPLICATION_JSON)
    public AuthStatus me() {
        boolean open = config.mode() == AuthConfigProperties.Mode.NONE;
        return new AuthStatus(config.mode().name().toLowerCase(Locale.ROOT), !identity.isAnonymous(),
                open || AccessRules.isAllowed(identity, config), AccessRules.displayName(identity));
    }

    @GET
    @Path("login")
    public Response login(@QueryParam("redirect") String redirect) {
        return Response.seeOther(URI.create(safeRedirect(redirect))).build();
    }

    /** Ends the OIDC session (the cookie); the identity provider's own session stays. */
    @GET
    @Path("logout")
    public Uni<Response> logout(@QueryParam("redirect") String redirect) {
        Response back = Response.seeOther(URI.create(safeRedirect(redirect))).build();
        if (config.mode() == AuthConfigProperties.Mode.OIDC && identity.getPrincipal() instanceof JsonWebToken) {
            return oidcSession.get().logout().replaceWith(back);
        }
        return Uni.createFrom().item(back);
    }

    /** Only a path on this host, so the sign-in can't be used to send users elsewhere. */
    static String safeRedirect(String redirect) {
        if (redirect == null || !redirect.startsWith("/") || redirect.startsWith("//")
                || redirect.contains("\\") || redirect.chars().anyMatch(Character::isISOControl)) {
            return "/";
        }
        try {
            URI uri = URI.create(redirect);
            return uri.getScheme() == null && uri.getRawAuthority() == null ? redirect : "/";
        } catch (IllegalArgumentException e) {
            return "/";
        }
    }
}
