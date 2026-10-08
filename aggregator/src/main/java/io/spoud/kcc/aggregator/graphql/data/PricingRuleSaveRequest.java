package io.spoud.kcc.aggregator.graphql.data;

import io.spoud.kcc.aggregator.stream.serialization.AvroTrust;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.spoud.kcc.aggregator.data.PriceUnit;
import io.spoud.kcc.data.PricingRule;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;

/**
 * A rule is saved either with a {@code price} per {@code priceUnit} (optionally times a
 * {@code multiplier}, e.g. 3 replicas), from which the cost factor is derived, or with a bare
 * {@code costFactor} per unit of the metric's raw value, as before units existed.
 */
@RegisterForReflection
public record PricingRuleSaveRequest(
        @NonNull String metricName,
        @NonNull double baseCost,
        @Description("Cost per unit of the metric's raw value. Ignored when a price is given.")
        Double costFactor,
        @Description("Price per priceUnit; the cost factor is derived from it")
        Double price,
        PriceUnit priceUnit,
        @Description("Factor on top of the price, e.g. 3 for storage billed per replica. Default 1.")
        Double multiplier,
        @Description("What the multiplier stands for, e.g. replicas")
        String multiplierLabel,
        @Description("Empty: correct the current price, everywhere it applies. A time: a new price from then on; the current price keeps the hours before it.")
        Instant validFrom) {

    static {
        AvroTrust.ensure();
    }

    public PricingRule toAvro() {
        var rule = PricingRule.newBuilder()
                .setCreationTime(Instant.now())
                .setMetricName(metricName())
                .setBaseCost(baseCost());
        if (price() != null) {
            if (priceUnit() == null) {
                throw new BadRequestException("A price needs a priceUnit (GB, GB_HOUR or UNIT).");
            }
            if (price() < 0) {
                throw new BadRequestException("The price must be 0 or more.");
            }
            double factor = multiplier() == null ? 1 : multiplier();
            if (factor <= 0) {
                throw new BadRequestException("The multiplier must be greater than 0.");
            }
            return rule.setCostFactor(priceUnit().costFactor(price(), factor))
                    .setPrice(price())
                    .setPriceUnit(priceUnit().name())
                    .setMultiplier(multiplier())
                    .setMultiplierLabel(multiplier() == null ? null : blankToNull(multiplierLabel()))
                    .build();
        }
        if (multiplier() != null || priceUnit() != null) {
            throw new BadRequestException("A priceUnit or multiplier needs a price.");
        }
        if (costFactor() == null) {
            throw new BadRequestException("Give a price and priceUnit, or a costFactor.");
        }
        return rule.setCostFactor(costFactor()).build();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
