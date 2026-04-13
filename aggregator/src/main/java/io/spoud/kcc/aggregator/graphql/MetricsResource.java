package io.spoud.kcc.aggregator.graphql;

import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.graphql.data.MetricHistoryTO;
import io.spoud.kcc.aggregator.service.MetricsService;
import jakarta.annotation.security.PermitAll;
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
    private final MetricsService metricsService;

    @Query("history")
    public @NonNull List<@NonNull MetricHistoryTO> history(
            @NonNull Set<String> metricNames,
            @NonNull Set<String> groupByContextKeys,
            @NonNull Instant from,
            Instant to) {
        return metricsService.getHistory(metricNames, groupByContextKeys, from, to);
    }

    @PermitAll
    @Query("metricNames")
    public @NonNull List<@NonNull MetricNameEntity> metricNames() {
        return metricsService.getMetricNames();
    }

    @PermitAll
    @Query("metricContextKeys")
    public @NonNull List<@NonNull String> contextKeys() {
        return metricsService.getContextKeys();
    }

}
