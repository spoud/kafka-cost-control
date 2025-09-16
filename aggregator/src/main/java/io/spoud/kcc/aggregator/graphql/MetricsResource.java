package io.spoud.kcc.aggregator.graphql;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.graphql.data.MetricHistoryTO;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import io.spoud.kcc.aggregator.olap.MetricEO;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import jakarta.annotation.security.PermitAll;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@GraphQLApi
@RequiredArgsConstructor
public class MetricsResource {
    private final AggregatedMetricsRepository aggregatedMetricsRepository;
    private final MetricNameRepository metricNameRepository;

    @Query("history")
    @RunOnVirtualThread
    public @NonNull List<@NonNull MetricHistoryTO> history(
            @NonNull Set<String> metricNames,
            @NonNull Set<String> groupByContextKeys,
            @NonNull Instant from,
            Instant to) {
        if (groupByContextKeys.isEmpty()) {
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

    @PermitAll
    @Query("metricNames")
    @RunOnVirtualThread
    public @NonNull List<@NonNull MetricNameEntity> metricNames() {
        return metricNameRepository.getMetricNames();
    }

    @PermitAll
    @Query("metricContextKeys")
    @RunOnVirtualThread
    public @NonNull List<@NonNull String> contextKeys() {
        return aggregatedMetricsRepository.getAllContextKeys().stream().sorted().toList();
    }

}
