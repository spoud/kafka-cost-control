package io.spoud.kcc.aggregator.graphql;

import io.spoud.kcc.aggregator.graphql.data.CostOverviewRequest;
import io.spoud.kcc.aggregator.graphql.data.CostOverviewResponse;
import io.spoud.kcc.aggregator.graphql.data.PricingRuleCostRequest;
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

    @Query("calculateTable")
    public @NonNull TableResponse calculateTable(CostOverviewRequest request) {
        return aggregatedMetricsRepository.calculateTable(request);
    }

    /**
     * End price (e.g. from confluent) to a distribution (according to consumption percentage)
     */
    @Query("costOverview")
    public @NonNull CostOverviewResponse calculateCosts(CostOverviewRequest request) {
        return aggregatedMetricsRepository.calculateCosts(request);
    }

    /**
     * Costs computed by the pricing rules (bottom-up), distributed by context. Prices are in
     * cents, like costOverview.
     */
    @Query("pricingRuleCosts")
    public @NonNull CostOverviewResponse pricingRuleCosts(PricingRuleCostRequest request) {
        return aggregatedMetricsRepository.calculatePricingRuleCosts(request);
    }
}
