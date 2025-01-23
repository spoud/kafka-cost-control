package io.spoud.kcc.aggregator.graphql;

import io.spoud.kcc.aggregator.graphql.data.MetricHistoryTO;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@GraphQLApi
@RequiredArgsConstructor
public class MetricsResource {
    private final AggregatedMetricsRepository aggregatedMetricsRepository;


    @Query("history")
    public @NonNull List<@NonNull MetricHistoryTO> history(
            @NonNull Set<String> names,
            @NonNull Instant from,
            Instant to) {

        aggregatedMetricsRepository.exportData()
    }
}
