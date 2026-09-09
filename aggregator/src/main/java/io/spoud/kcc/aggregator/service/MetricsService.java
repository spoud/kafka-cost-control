package io.spoud.kcc.aggregator.service;

import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.graphql.data.MetricHistoryTO;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.*;

@ApplicationScoped
@RequiredArgsConstructor
public class MetricsService {
    private final AggregatedMetricsRepository aggregatedMetricsRepository;
    private final MetricNameRepository metricNameRepository;

    public List<MetricHistoryTO> getHistory(Set<String> metricNames, Set<String> groupByContextKeys, Instant from, Instant to) {
        if (groupByContextKeys == null || groupByContextKeys.isEmpty()) {
            // No breakdown asked for, so answer with the total per metric over time. This used to
            // read every raw row and return a series per entity - 958 series and 158k points for a
            // single week of a real installation, ~5 MB - which no chart could render and which a
            // Reporting panel requested merely by existing before it was configured.
            return List.copyOf(aggregatedMetricsRepository.getHistoryTotals(from, to, metricNames));
        } else {
            String groupByContextKey = groupByContextKeys.stream().findFirst().get(); // only support one atm
            return aggregatedMetricsRepository.getHistoryGrouped(from, to, metricNames, groupByContextKey).stream().toList();
        }
    }

    public List<MetricNameEntity> getMetricNames() {
        return metricNameRepository.getMetricNames();
    }

    public List<String> getContextKeys() {
        return aggregatedMetricsRepository.getAllContextKeys().stream().sorted().toList();
    }
}
