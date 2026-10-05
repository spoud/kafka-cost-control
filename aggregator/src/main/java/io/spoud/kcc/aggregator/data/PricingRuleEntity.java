package io.spoud.kcc.aggregator.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.spoud.kcc.data.PricingRule;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;

@RegisterForReflection
public record PricingRuleEntity(
    @NonNull Instant creationTime,
    @NonNull String metricName,
    @NonNull double baseCost,
    @Description("Cost per unit of the metric's raw value, derived from the price when one is set")
    @NonNull double costFactor,
    @Description("Price per priceUnit as entered; null for rules saved with a bare cost factor")
    Double price,
    PriceUnit priceUnit,
    @Description("Factor on top of the price, e.g. 3 replicas; null means 1")
    Double multiplier,
    String multiplierLabel,
    @Description("Amount per window, in priceUnit, that is free across all entities; null means none")
    Double freePerWindow) {
  public static PricingRuleEntity fromAvro(PricingRule pricingRule) {
    if (pricingRule == null) {
      return null;
    }
    return new PricingRuleEntity(
        pricingRule.getCreationTime(),
        pricingRule.getMetricName(),
        pricingRule.getBaseCost(),
        pricingRule.getCostFactor(),
        pricingRule.getPrice(),
        PriceUnit.fromStored(pricingRule.getPriceUnit()),
        pricingRule.getMultiplier(),
        pricingRule.getMultiplierLabel(),
        pricingRule.getFreePerWindow());
  }
}
