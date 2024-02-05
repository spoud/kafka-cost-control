package io.spoud.kcc.aggregator.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.spoud.kcc.data.ContextData;
import io.spoud.kcc.data.EntityType;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;
import java.util.Map;

@RegisterForReflection
public record ContextDataEntity(
    String id,
    @NonNull Instant creationTime,
    Instant validFrom,
    Instant validUntil,
    @NonNull EntityType entityType,
    @NonNull String regex,
    @NonNull Map<@NonNull String, @NonNull String> context) {
  public static ContextDataEntity fromAvro(String id, ContextData contextData) {
    if (contextData == null) {
      return null;
    }
    return new ContextDataEntity(
        id,
        contextData.getCreationTime(),
        contextData.getValidFrom(),
        contextData.getValidUntil(),
        contextData.getEntityType(),
        contextData.getRegex(),
        contextData.getContext());
  }
}
