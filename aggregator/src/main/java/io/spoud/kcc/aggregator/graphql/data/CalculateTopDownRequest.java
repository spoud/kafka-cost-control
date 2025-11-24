package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
public record CalculateTopDownRequest(
        Instant from,
        Instant to,
        Integer totalCents,
        Integer kafkaStorageCents,
        Integer kafkaNetworkReadCents,
        Integer kafkaNetworkWriteCents,
        List<String> contextKeysToGroupBy
) {
}
