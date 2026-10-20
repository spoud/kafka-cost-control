package io.spoud.kcc.aggregator.bills;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * What one of a bill's METRICS lines works out to per unit of the usage KCC measured in the hours
 * the bill covers; a line with several metrics gives each the same rate. It is what the costs view
 * shares the line by, so the rate reproduces the bill there, and it already holds what a list price
 * misses: free allowances, replicas, metrics that count a little low. Saved as a pricing rule, it
 * estimates the hours after the bill.
 */
@RegisterForReflection
@Description("A bill line per unit of the usage measured in the hours the bill covers")
public record BillRate(
        @NonNull String metricName,
        @Description("The line the metric is on, e.g. Network") @NonNull String line,
        @Description("The line's billed amount, in dollars") double amount,
        @Description("Sum of the hourly values of the line's metrics in the hours the bill covers, within its cluster")
        double usage,
        @Description("amount / usage: the cost per unit of the metric's raw value; null when no usage was measured")
        Double costFactor) {

    public static BillRate of(String metricName, String line, double amount, double usage) {
        return new BillRate(metricName, line, amount, usage, usage > 0 ? amount / usage : null);
    }
}
