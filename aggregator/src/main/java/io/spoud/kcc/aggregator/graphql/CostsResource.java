package io.spoud.kcc.aggregator.graphql;

import io.spoud.kcc.aggregator.graphql.data.CostOverviewRequest;
import io.spoud.kcc.aggregator.graphql.data.CostOverviewResponse;
import io.spoud.kcc.aggregator.graphql.data.TableResponse;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

@GraphQLApi
@RequiredArgsConstructor
public class CostsResource {

    private final AggregatedMetricsRepository aggregatedMetricsRepository;

    @Query("calculateTable")
    public @NonNull TableResponse calculateTable(CostOverviewRequest request) {
        return aggregatedMetricsRepository.calculateTable(request);
    }

    /**
     * End price (e.g. from confluent) to a distribution (according to consumption percentage).
     *
     * @deprecated enter the bill on the Bills page and read {@code billedCosts}, which shares it the
     * same way and also covers the other lines and the hours no bill covers yet.
     */
    @Deprecated
    @Query("costOverview")
    @Description("Deprecated: enter the bill on the Bills page and query billedCosts")
    public @NonNull CostOverviewResponse calculateCosts(CostOverviewRequest request) {
        return aggregatedMetricsRepository.calculateCosts(request);
    }
}
