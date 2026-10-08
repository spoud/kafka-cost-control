package io.spoud.kcc.aggregator.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.spoud.kcc.data.EntityType;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
@Description("A topic or principal whose metrics carry no value for a context key: no rule assigns it")
public record UnassignedEntity(
        @NonNull EntityType entityType,
        @NonNull String name,
        @Description("Metrics recorded for it in the period")
        @NonNull List<@NonNull String> metrics,
        @Description("What it cost in the period, in dollars, as on Cost Overview")
        @NonNull double cost,
        @Description("End of the last window it appeared in")
        @NonNull Instant lastSeen) {
}
