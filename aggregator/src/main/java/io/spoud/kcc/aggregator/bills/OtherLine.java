package io.spoud.kcc.aggregator.bills;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.spoud.kcc.data.BillOtherLine;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

import java.util.Map;

/** A bill amount that isn't split by a metric's usage, e.g. a connector or support. */
@RegisterForReflection
public record OtherLine(
        @NonNull String description,
        @Description("In dollars; negative for a credit") double amount,
        @NonNull Allocation allocation,
        @Description("For CONTEXT: the context the amount belongs to, e.g. tenant=data-platform")
        Map<@NonNull String, @NonNull String> context) {

    public enum Allocation {
        /** All of it to the given context. */
        CONTEXT,
        /** Spread in proportion to the month's usage-based costs, like overhead. */
        USAGE,
        /** Shown as shared, not assigned to anyone. */
        SHARED
    }

    static OtherLine fromAvro(BillOtherLine line) {
        return new OtherLine(line.getDescription(), line.getAmount(), Allocation.valueOf(line.getAllocation()),
                line.getContext());
    }

    BillOtherLine toAvro() {
        return BillOtherLine.newBuilder()
                .setDescription(description)
                .setAmount(amount)
                .setAllocation(allocation.name())
                .setContext(context)
                .build();
    }
}
