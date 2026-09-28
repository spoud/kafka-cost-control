package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
public record PricingRuleCostRequest(
        Instant from,
        Instant to,
        List<String> contextKeysToGroupBy
) {
    public List<String> contextKeysToGroupBy() {
        return contextKeysToGroupBy != null ? contextKeysToGroupBy : List.of();
    }
}
