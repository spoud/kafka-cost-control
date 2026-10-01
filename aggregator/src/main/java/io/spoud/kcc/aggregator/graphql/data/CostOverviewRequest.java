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
        Integer kafkaPartitionsCents,
        List<String> contextKeysToGroupBy
) {
}
