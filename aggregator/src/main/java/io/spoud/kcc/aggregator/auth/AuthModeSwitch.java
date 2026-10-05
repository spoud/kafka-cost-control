package io.spoud.kcc.aggregator.auth;

import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.PropertiesConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.List;
import java.util.Map;

/**
 * Derives the Quarkus security settings from {@code cc.auth.mode}, so the mode is the one switch.
 * In {@code oidc} mode:
 * <ul>
 *     <li>the OIDC sign-in is on - in the other modes it is off, or an installation without an
 *     OIDC server URL would fail to start;</li>
 *     <li>OIDC ranks above basic auth (2000; OIDC's default is 1001), so an anonymous browser is
 *     sent to the identity provider rather than shown a password prompt. A request that brings
 *     basic credentials still signs in with them. ({@code quarkus.http.auth.basic-priority}
 *     looked like the setting for this but changed nothing.)</li>
 * </ul>
 * The ordinal is below application.yaml and the environment, so an explicit setting still wins.
 */
public class AuthModeSwitch implements ConfigSourceFactory {

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        ConfigValue mode = context.getValue("cc.auth.mode");
        boolean oidc = mode != null && mode.getValue() != null
                && AuthConfigProperties.Mode.OIDC.name().equalsIgnoreCase(mode.getValue().trim());
        Map<String, String> settings = oidc
                ? Map.of("quarkus.oidc.tenant-enabled", "true", "quarkus.oidc.priority", "3000")
                : Map.of("quarkus.oidc.tenant-enabled", "false");
        return List.of(new PropertiesConfigSource(settings, "cc.auth.mode", 200));
    }
}
