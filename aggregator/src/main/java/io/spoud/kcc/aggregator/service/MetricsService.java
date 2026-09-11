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
            // No breakdown asked for: the same query without the context grouping, i.e. one
            // bucketed total per metric.
            return List.copyOf(aggregatedMetricsRepository.getHistoryGrouped(from, to, metricNames, null));
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
