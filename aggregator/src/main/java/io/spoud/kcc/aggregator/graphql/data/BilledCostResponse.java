package io.spoud.kcc.aggregator.graphql.data;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;
import java.util.List;

/** In cents. */
public record BilledCostResponse(
        @NonNull List<@NonNull MetricCosts> metrics,
        @Description("Each month the range touches, and whether a bill covers it") @NonNull List<@NonNull MonthBilling> months) {

    /** {@code metric} is the metric a bill line follows, or {@code other} for the bill's other lines. */
    public record MetricCosts(@NonNull String metric, @NonNull List<@NonNull Share> shares) {
    }

    public record Share(
            @NonNull String name,
            @Description("The values of the grouping keys, in their order; <other> where none, <shared> for what is assigned to no one")
            @NonNull List<@NonNull String> contextValues,
            @Description("Total in cents, the estimated part included") double price,
            @Description("The part in cents that comes from the rate card because no bill covers it") double estimatedPrice) {
    }

    public record MonthBilling(
            @NonNull String month,
            @Description("Whether a bill exists for the month") boolean billed,
            @Description("The part of the range in this month") @NonNull Instant from,
            @NonNull Instant to,
            @Description("Where the bill's amounts stop; after it, the rate card applies") Instant billedUntil) {
    }
}
