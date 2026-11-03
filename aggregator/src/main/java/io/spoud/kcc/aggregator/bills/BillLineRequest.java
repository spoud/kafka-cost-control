package io.spoud.kcc.aggregator.bills;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.graphql.Description;

import java.util.List;

/**
 * A line as entered. Its own input type so {@code metrics} and {@code context} can be left out:
 * SmallRye makes a map input required, and only a CONTEXT line has a context.
 */
@RegisterForReflection
public record BillLineRequest(
        String description,
        @Description("In dollars; negative for a credit. May be empty on a METRICS line: its metrics are then estimated from the pricing rules")
        Double amount,
        BillLineEntity.Allocation allocation,
        @Description("For METRICS: the metrics the line is shared by, e.g. [\"kafka_log_log_size\"]")
        List<String> metrics,
        @Description("For CONTEXT: the context the amount belongs to, e.g. [{key: \"tenant\", value: \"data-platform\"}]")
        List<ContextPair> context) {

    public record ContextPair(String key, String value) {
    }
}
