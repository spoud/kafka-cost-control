package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@RegisterForReflection
public record PricingRuleCostRequest(
        Instant from,
        Instant to,
        List<String> contextKeysToGroupBy
) {
    public Instant to() {
        Instant openEndedTo = OffsetDateTime.of(99999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC).toInstant();
        return to != null ? to : openEndedTo;
    }

    public List<String> contextKeysToGroupBy() {
        return contextKeysToGroupBy != null ? contextKeysToGroupBy : List.of();
    }
}
