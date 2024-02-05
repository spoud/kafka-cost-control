package io.spoud.kcc.aggregator.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.spoud.kcc.data.EntityType;
import org.eclipse.microprofile.graphql.NonNull;

// TODO shitty name, find something else
@RegisterForReflection
public record Metric(@NonNull EntityType type, @NonNull String objectName) {}
