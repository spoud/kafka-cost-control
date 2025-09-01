package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record CalculateTopDownInput(
        Integer kafkaStorageCents,
        Integer kafkaNetworkReadCents,
        Integer kafkaNetworkWriteCents,
        String remainingItems
) {
}
