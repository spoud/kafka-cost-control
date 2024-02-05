package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.graphql.NonNull;

@RegisterForReflection
public record ContextDataDeleteRequest(@NonNull String id) {
}
