package io.spoud.kcc.aggregator.bills;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.spoud.kcc.data.BillLine;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

import java.util.List;
import java.util.Map;

/** One line of a bill, in dollars, with how it is shared. */
@RegisterForReflection
public record BillLineEntity(
        @NonNull String description,
        @Description("In dollars; negative for a credit. Null only on a METRICS line, whose metrics are then estimated from the pricing rules")
        Double amount,
        @NonNull Allocation allocation,
        @Description("For METRICS: the metrics the line is shared by")
        @NonNull List<@NonNull String> metrics,
        @Description("For CONTEXT: the context the amount belongs to, e.g. tenant=data-platform")
        Map<@NonNull String, @NonNull String> context) {

    public enum Allocation {
        /** Shared by the usage of the line's metrics, e.g. storage by stored bytes. */
        METRICS,
        /** All of it to the given context. */
        CONTEXT,
        /** Spread in proportion to the bill's usage-based costs, like overhead. */
        USAGE,
        /** Shown as shared, not assigned to anyone. */
        SHARED
    }

    public static BillLineEntity byMetrics(String description, Double amount, String... metrics) {
        return new BillLineEntity(description, amount, Allocation.METRICS, List.of(metrics), null);
    }

    public static BillLineEntity other(String description, double amount, Allocation allocation, Map<String, String> context) {
        return new BillLineEntity(description, amount, allocation, List.of(), context);
    }

    static BillLineEntity fromAvro(BillLine line) {
        return new BillLineEntity(line.getDescription(), line.getAmount(), Allocation.valueOf(line.getAllocation()),
                List.copyOf(line.getMetrics()), line.getContext());
    }

    BillLine toAvro() {
        return BillLine.newBuilder()
                .setDescription(description)
                .setAmount(amount)
                .setAllocation(allocation.name())
                .setMetrics(metrics)
                .setContext(context)
                .build();
    }
}
