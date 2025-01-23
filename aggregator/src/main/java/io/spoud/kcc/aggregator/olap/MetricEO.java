package io.spoud.kcc.aggregator.olap;

import java.time.Instant;
import java.util.Map;

public record MetricEO(
        String id,
        Instant start,
        Instant end,
        String initialMetricName,
        String entityType,
        String name,
        Map<String, String> tags,
        Map<String, String> context,
        Double value,
        String target) {
}


