package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.spoud.kcc.data.ContextData;
import io.spoud.kcc.data.EntityType;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;
import java.util.Map;

@RegisterForReflection
public record ContextDataSaveRequest(
        String id, Instant validFrom, Instant validUntil, @NonNull EntityType entityType, @NonNull String regex, @NonNull Map<@NonNull String, @NonNull String> context) {
  public ContextData toAvro() {
    return new ContextData(Instant.now(), validFrom, validUntil, entityType, regex, context);
  }
}
