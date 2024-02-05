package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.spoud.kcc.data.PricingRule;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;

@RegisterForReflection
public record PricingRuleSaveRequest(@NonNull String metricName, @NonNull double baseCost, @NonNull double costFactor) {
  public PricingRule toAvro() {
    return new PricingRule(Instant.now(), metricName(), baseCost(), costFactor());
  }
}
