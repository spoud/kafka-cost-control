package io.spoud.kcc.aggregator.auth;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Optional;

/**
 * Who may use Kafka Cost Control. It is all or nothing: in {@code basic} and {@code oidc} mode
 * every page, query and mutation needs a signed-in user, in {@code none} mode nothing does.
 * The health and metrics endpoints stay open in every mode.
 */
@ConfigMapping(prefix = "cc.auth")
public interface AuthConfigProperties {

    @WithName("mode")
    @WithDefault("basic")
    Mode mode();

    /**
     * OIDC only: e-mail domains whose users may sign in, e.g. {@code example.com}. Without any
     * allowed domain or e-mail, every user the identity provider signs in is allowed - fine for a
     * company's own provider, not for one anyone can have an account at (Google, Microsoft).
     */
    @WithName("allowed-domains")
    Optional<List<String>> allowedDomains();

    /** OIDC only: single e-mail addresses that may sign in, on top of the allowed domains. */
    @WithName("allowed-emails")
    Optional<List<String>> allowedEmails();

    enum Mode {
        /** No sign-in at all, e.g. when a proxy in front already authenticates every request. */
        NONE,
        /** Sign in with the admin user and {@code cc.admin-password}. */
        BASIC,
        /**
         * Sign in through an OpenID Connect provider (configured with {@code quarkus.oidc.*}).
         * The admin user still works with basic auth, for scripts.
         */
        OIDC
    }
}
