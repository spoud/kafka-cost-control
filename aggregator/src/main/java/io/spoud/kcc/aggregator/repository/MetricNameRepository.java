package io.spoud.kcc.aggregator.repository;

import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.stream.MetricReducer;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Store all the metric names saw in the streams. */
@ApplicationScoped
@RequiredArgsConstructor
public class MetricNameRepository {

  private final Map<String, Instant> metricNames = new ConcurrentHashMap<>(100);
  private final MetricReducer metricReducer;

  public void addMetricName(String metricName, Instant metricTime) {
    metricNames.put(metricName, metricTime);
  }

  public List<MetricNameEntity> getMetricNames() {
    return metricNames.entrySet().stream()
        .map(entry -> new MetricNameEntity(entry.getKey(), entry.getValue(), metricReducer.getAggregationType(entry.getKey()).name()))
        .sorted(Comparator.comparing(MetricNameEntity::metricName))
        .collect(Collectors.toList());
  }
}
