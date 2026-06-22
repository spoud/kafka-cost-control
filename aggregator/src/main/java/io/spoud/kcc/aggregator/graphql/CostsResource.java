package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.Authenticated;
import io.spoud.kcc.aggregator.graphql.data.CostOverviewRequest;
import io.spoud.kcc.aggregator.graphql.data.CostOverviewResponse;
import io.spoud.kcc.aggregator.graphql.data.TableResponse;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

@GraphQLApi
@RequiredArgsConstructor
public class CostsResource {

    private final AggregatedMetricsRepository aggregatedMetricsRepository;

    @Authenticated
    @Query("calculateTable")
    public @NonNull TableResponse calculateTable(CostOverviewRequest request) {
        return aggregatedMetricsRepository.calculateTable(request);
    }

    /**
     * End price (e.g. from confluent) to a distribution (according to consumption percentage)
     */
    @Authenticated
    @Query("costOverview")
    public @NonNull CostOverviewResponse calculateCosts(CostOverviewRequest request) {
        return aggregatedMetricsRepository.calculateCosts(request);
    }
}
