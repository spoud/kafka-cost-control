package io.spoud.kcc.aggregator.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.spoud.kcc.data.EntityType;
import org.eclipse.microprofile.graphql.NonNull;

import java.util.Map;

@RegisterForReflection
public record ContextTestResponse(
        @NonNull EntityType entityType,
        @NonNull Map<@NonNull String, @NonNull String> context) {

}
