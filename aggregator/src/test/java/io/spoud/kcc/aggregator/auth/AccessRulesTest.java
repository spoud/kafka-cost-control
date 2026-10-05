package io.spoud.kcc.aggregator.auth;

import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AccessRulesTest {

    @Test
    void anonymousIsNeverAllowed() {
        assertThat(AccessRules.isAllowed(QuarkusSecurityIdentity.builder().setAnonymous(true).build(), config(List.of(), List.of())))
                .isFalse();
    }

    @Test
    void theAdminSignedInWithBasicAuthIsAllowed() {
        var admin = QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal("admin")).build();
        assertThat(AccessRules.isAllowed(admin, config(List.of("example.com"), List.of()))).isTrue();
        assertThat(AccessRules.displayName(admin)).isEqualTo("admin");
    }

    @Test
    void withoutRestrictionsEveryProviderUserIsAllowed() {
        assertThat(AccessRules.isAllowed(user(Map.of("email", "someone@anywhere.org")), config(List.of(), List.of())))
                .isTrue();
    }

    @Test
    void onlyAllowedDomainsAndEmailsGetIn() {
        var config = config(List.of(" Example.com "), List.of("Guest@partner.org"));

        assertThat(AccessRules.isAllowed(user(Map.of("email", "ana@example.com", "email_verified", true)), config)).isTrue();
        assertThat(AccessRules.isAllowed(user(Map.of("email", "ANA@EXAMPLE.COM")), config)).isTrue();
        assertThat(AccessRules.isAllowed(user(Map.of("email", "guest@partner.org")), config)).isTrue();

        assertThat(AccessRules.isAllowed(user(Map.of("email", "other@partner.org")), config)).isFalse();
        // a look-alike domain is not the domain
        assertThat(AccessRules.isAllowed(user(Map.of("email", "ana@notexample.com")), config)).isFalse();
        assertThat(AccessRules.isAllowed(user(Map.of("email", "ana@example.com.evil.org")), config)).isFalse();
        assertThat(AccessRules.isAllowed(user(Map.of("sub", "1234")), config)).isFalse();
    }

    @Test
    void anUnverifiedEmailDoesNotCount() {
        var config = config(List.of("example.com"), List.of());
        assertThat(AccessRules.isAllowed(user(Map.of("email", "ana@example.com", "email_verified", false)), config)).isFalse();
        assertThat(AccessRules.isAllowed(user(Map.of("email", "ana@example.com", "email_verified", "false")), config)).isFalse();
    }

    @Test
    void showsTheEmailOfAProviderUser() {
        assertThat(AccessRules.displayName(user(Map.of("email", "ana@example.com")))).isEqualTo("ana@example.com");
        assertThat(AccessRules.displayName(QuarkusSecurityIdentity.builder().setAnonymous(true).build())).isNull();
    }

    private static SecurityIdentity user(Map<String, Object> claims) {
        return QuarkusSecurityIdentity.builder().setPrincipal(new Token(claims)).build();
    }

    private static AuthConfigProperties config(List<String> domains, List<String> emails) {
        return new AuthConfigProperties() {
            @Override
            public Mode mode() {
                return Mode.OIDC;
            }

            @Override
            public Optional<List<String>> allowedDomains() {
                return domains.isEmpty() ? Optional.empty() : Optional.of(domains);
            }

            @Override
            public Optional<List<String>> allowedEmails() {
                return emails.isEmpty() ? Optional.empty() : Optional.of(emails);
            }
        };
    }

    private record Token(Map<String, Object> claims) implements JsonWebToken {
        @Override
        public String getName() {
            return String.valueOf(claims.getOrDefault("sub", "user"));
        }

        @Override
        public Set<String> getClaimNames() {
            return claims.keySet();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getClaim(String claimName) {
            return (T) claims.get(claimName);
        }
    }
}
