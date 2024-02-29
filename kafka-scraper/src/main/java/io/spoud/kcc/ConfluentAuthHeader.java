package io.spoud.kcc;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

import java.util.Base64;
import java.util.Collections;
import java.util.Map;

import static jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION;

@ApplicationScoped
public class ConfluentAuthHeader implements ClientHeadersFactory {

    private String basicAuthHeader = null;

    public ConfluentAuthHeader(@Identifier("default-kafka-broker") Map<String, Object> config) {
        if ("USER_INFO".equals(config.get("basic.auth.credentials.source")) && config.containsKey("basic.auth.user.info")) {
            String authHeader = config.get("basic.auth.user.info").toString();
            byte[] encodedAuth = Base64.getEncoder().encode(authHeader.getBytes());
            this.basicAuthHeader = "Basic " + new String(encodedAuth);
        }
    }

    @Override
    public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incomingHeaders, MultivaluedMap<String, String> clientOutgoingHeaders) {
        if (this.basicAuthHeader != null) {
            clientOutgoingHeaders.put(AUTHORIZATION, Collections.singletonList(this.basicAuthHeader));
        }
        return clientOutgoingHeaders;
    }
}
