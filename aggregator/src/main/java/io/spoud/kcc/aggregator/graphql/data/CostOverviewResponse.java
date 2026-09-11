package io.spoud.kcc.aggregator.graphql.data;

import java.util.List;

public record CostOverviewResponse(List<MetricToDistributionMap> metricToDistributionMapList) {
    public record MetricToDistributionMap(String metric, List<NameToPrice> nameToPriceList) {
        // contextValues holds the raw, per-context-key values in the same order as the request's
        // contextKeysToGroupBy, so a caller can build a real drill-down hierarchy (e.g. customer -> app)
        // instead of only having the pre-joined "key=value, key2=value2" label in `name`.
        public record NameToPrice(String name, Double price, List<String> contextValues) {
        }
    }
}
