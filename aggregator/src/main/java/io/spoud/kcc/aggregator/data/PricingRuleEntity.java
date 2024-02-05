package io.spoud.kcc.aggregator.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.spoud.kcc.data.PricingRule;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;

@RegisterForReflection
public record PricingRuleEntity(
    @NonNull Instant creationTime,
    @NonNull String metricName,
    @NonNull double baseCost,
    @NonNull double costFactor) {
  public static PricingRuleEntity fromAvro(PricingRule pricingRule) {
    if (pricingRule == null) {
      return null;
    }
    return new PricingRuleEntity(
        pricingRule.getCreationTime(),
        pricingRule.getMetricName(),
        pricingRule.getBaseCost(),
        pricingRule.getCostFactor());
  }
}
