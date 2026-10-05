package io.spoud.kcc.aggregator.auth;

import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Whether a signed-in user may use the application, and the name to show for them. */
final class AccessRules {

    private AccessRules() {
    }

    static boolean isAllowed(SecurityIdentity identity, AuthConfigProperties config) {
        if (identity == null || identity.isAnonymous()) {
            return false;
        }
        if (!(identity.getPrincipal() instanceof JsonWebToken token)) {
            return true; // the admin user, signed in with basic auth
        }
        List<String> domains = lowerCase(config.allowedDomains());
        List<String> emails = lowerCase(config.allowedEmails());
        if (domains.isEmpty() && emails.isEmpty()) {
            return true;
        }
        String email = email(token);
        // a provider that sends no email_verified (e.g. Microsoft Entra ID) vouches for the address itself
        if (email == null || isFalse(token.getClaim("email_verified"))) {
            return false;
        }
        email = email.toLowerCase(Locale.ROOT);
        return emails.contains(email) || domains.contains(email.substring(email.lastIndexOf('@') + 1));
    }

    static String displayName(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return null;
        }
        if (identity.getPrincipal() instanceof JsonWebToken token && email(token) != null) {
            return email(token);
        }
        return identity.getPrincipal().getName();
    }

    private static String email(JsonWebToken token) {
        Object email = token.getClaim("email");
        return email == null || email.toString().isBlank() ? null : email.toString().trim();
    }

    private static boolean isFalse(Object claim) {
        return claim != null && "false".equalsIgnoreCase(claim.toString());
    }

    private static List<String> lowerCase(Optional<List<String>> values) {
        return values.orElse(List.of()).stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
