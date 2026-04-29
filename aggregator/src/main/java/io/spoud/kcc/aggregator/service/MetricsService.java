package io.spoud.kcc.aggregator.service;

import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.graphql.data.MetricHistoryTO;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import io.spoud.kcc.aggregator.olap.MetricEO;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
@RequiredArgsConstructor
public class MetricsService {
    private final AggregatedMetricsRepository aggregatedMetricsRepository;
    private final MetricNameRepository metricNameRepository;

    public List<MetricHistoryTO> getHistory(Set<String> metricNames, Set<String> groupByContextKeys, Instant from, Instant to) {
        if (groupByContextKeys == null || groupByContextKeys.isEmpty()) {
            return aggregatedMetricsRepository.getHistory(from, to, metricNames).stream()
                    .collect(Collectors.groupingBy(MetricEO::name))
                    .entrySet().stream().map(entry -> {
                        List<Instant> times = new ArrayList<>(entry.getValue().size());
                        List<Double> values = new ArrayList<>(entry.getValue().size());
                        entry.getValue().forEach(metricEO -> {
                            times.add(metricEO.start());
                            values.add(metricEO.value());
                        });
                        return MetricHistoryTO.builder()
                                .name(entry.getKey())
                                .context(Map.of())
                                .times(times)
                                .values(values)
                                .build();
                    }).toList();
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
