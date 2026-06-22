package io.spoud.kcc.aggregator.graphql.data;

import java.util.List;

public record CostOverviewResponse(List<MetricToDistributionMap> metricToDistributionMapList) {
    public record MetricToDistributionMap(String metric, List<NameToPrice> nameToPriceList) {
        public record NameToPrice(String name, Double price) {
        }
    }
}
