package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

/** Costs from the monthly bills, with the rate card for whatever no bill covers. No end: until now. */
@RegisterForReflection
public record BilledCostRequest(
        Instant from,
        Instant to,
        List<String> contextKeysToGroupBy
) {
    public List<String> contextKeysToGroupBy() {
        return contextKeysToGroupBy != null ? contextKeysToGroupBy : List.of();
    }
}
