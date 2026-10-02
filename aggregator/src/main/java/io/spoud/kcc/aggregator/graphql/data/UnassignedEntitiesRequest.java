package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.graphql.Description;

import java.time.Instant;

@RegisterForReflection
public record UnassignedEntitiesRequest(
        @Description("ISO-8601; windows starting at or after it")
        Instant from,
        @Description("ISO-8601; windows ending at or before it, none = up to now")
        Instant to,
        @Description("The context key that counts as assigned, default tenant")
        String contextKey) {

    public String contextKeyOrDefault() {
        return contextKey == null || contextKey.isBlank() ? "tenant" : contextKey;
    }
}
