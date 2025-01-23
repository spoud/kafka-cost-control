package io.spoud.kcc.aggregator.graphql;

import io.spoud.kcc.aggregator.graphql.data.MetricHistoryTO;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import io.spoud.kcc.aggregator.olap.MetricEO;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@GraphQLApi
@RequiredArgsConstructor
public class MetricsResource {
    private final AggregatedMetricsRepository aggregatedMetricsRepository;

    @Query("history")
    public @NonNull List<@NonNull MetricHistoryTO> history(
            @NonNull Set<String> names,
            @NonNull Instant from,
            Instant to) {

        return aggregatedMetricsRepository.getHistory(from, to, names).stream().collect(Collectors.groupingBy(MetricEO::name))
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
}
