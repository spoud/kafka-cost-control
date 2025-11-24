package io.spoud.kcc.aggregator.graphql.data;

import java.util.List;

public record TableResponse(List<TableEntry> entries) {
    public record TableEntry(
            String initialMetricName,
            List<String> context,
            double total,
            double percentage
    ) {
    }
}
