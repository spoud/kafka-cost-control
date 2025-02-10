package io.spoud.kcc.aggregator.graphql;

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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    public @NonNull List<@NonNull MetricHistoryTO> history(
            @NonNull Set<String> metricNames,
            @NonNull Set<String> groupByContextKeys,
            @NonNull Instant from,
            Instant to) {

        if(1==1){
            Instant now = Instant.now().truncatedTo(ChronoUnit.DAYS);
            List<Instant> times = List.of(now.minus(Duration.ofDays(4)), now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)), now);
            return List.of(
                    MetricHistoryTO.builder()
                            .name("metric1")
                            .context(Map.of("appName", "app1"))
                            .times(times)
                            .values(List.of(1.0, 2.0, -1.0, 3.0, 2.0))
                            .build(),
                    MetricHistoryTO.builder()
                            .name("metric2")
                            .context(Map.of("appName", "app1"))
                            .times(times)
                            .values(List.of(2.0, 5.0, 4.0, 3.0, 4.0))
                            .build()
            );
        }

        return aggregatedMetricsRepository.getHistory(from, to, metricNames).stream().collect(Collectors.groupingBy(MetricEO::name))
                .entrySet().stream().map(entry -> {
                    List<Instant> times = new ArrayList<>(entry.getValue().size());
                    List<Double> values = new ArrayList<>(entry.getValue().size());
                    entry.getValue().forEach(metricEO -> {
                        times.add(metricEO.start());
                        values.add(metricEO.value());
                    });
                    // TODO group by context
                    return MetricHistoryTO.builder()
                            .name(entry.getKey())
                            .times(times)
                            .values(values)
                            .build();
                }).toList();
    }

    @PermitAll
    @Query("metricNames")
    public @NonNull List<@NonNull MetricNameEntity> metricNames() {
        return metricNameRepository.getMetricNames();
    }

    @PermitAll
    @Query("metricContextKeys")
    public @NonNull List<@NonNull String> contextKeys() {
        return aggregatedMetricsRepository.getAllContextKeys().stream().sorted().toList();
    }

}
