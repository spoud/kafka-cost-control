package io.spoud.kcc.aggregator.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.spoud.kcc.data.PricingRule;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;
import java.util.List;

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
    @Description("When the current price started; null: it has always applied")
    Instant validFrom,
    @Description("The prices before the current one, oldest first")
    @NonNull List<@NonNull EarlierPrice> earlierPrices) {

  /** A price that applied from validFrom (null: always) until validUntil. */
  public record EarlierPrice(
      Instant validFrom,
      @NonNull Instant validUntil,
      @NonNull double baseCost,
      @NonNull double costFactor,
      Double price,
      PriceUnit priceUnit,
      Double multiplier,
      String multiplierLabel) {
  }

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
        pricingRule.getValidFrom(),
        pricingRule.getEarlierPrices().stream()
            .map(p -> new EarlierPrice(p.getValidFrom(), p.getValidUntil(), p.getBaseCost(), p.getCostFactor(),
                p.getPrice(), PriceUnit.fromStored(p.getPriceUnit()), p.getMultiplier(), p.getMultiplierLabel()))
            .toList());
  }
}
