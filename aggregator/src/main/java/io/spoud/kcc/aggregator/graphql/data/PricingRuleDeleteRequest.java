package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.spoud.kcc.data.PricingRule;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;

@RegisterForReflection
public record PricingRuleDeleteRequest(@NonNull String metricName) {
}
