package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.*;
import java.util.List;

@RegisterForReflection
public record CostOverviewRequest(
        Instant from,
        Instant to,
        Integer totalCents,
        Integer kafkaStorageCents,
        Integer kafkaNetworkReadCents,
        Integer kafkaNetworkWriteCents,
        List<String> contextKeysToGroupBy
) {
    public Instant to() {
        Instant openEndedTo = OffsetDateTime.of(99999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC).toInstant();
        return to != null ? to : openEndedTo;
    }
}
