package io.spoud.kcc.aggregator.bills;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * What one of a bill's usage lines works out to per unit of the usage KCC measured in the hours the
 * bill covers. It is what the costs view shares the line by, so the rate reproduces the bill there,
 * and it already holds what a list price misses: free allowances, replicas, metrics that count a
 * little low. Saved as a pricing rule, it estimates the hours after the bill.
 */
@RegisterForReflection
@Description("A bill line per unit of the usage measured in the hours the bill covers")
public record BillRate(
        @NonNull String metricName,
        @Description("The billed amount, in dollars") double amount,
        @Description("Sum of the metric's hourly values in the hours the bill covers")
        double usage,
        @Description("amount / usage: the cost per unit of the metric's raw value; null when no usage was measured")
        Double costFactor) {

    public static BillRate of(String metricName, double amount, double usage) {
        return new BillRate(metricName, amount, usage, usage > 0 ? amount / usage : null);
    }
}
