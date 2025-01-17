package io.spoud.kcc.aggregator;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

@ConfigMapping(prefix = "cc")
public interface CostControlConfigProperties {

    @WithName("admin-password")
    @NotNull
    String adminPassword();

    @WithName("topics.raw-data")
    @NotNull
    List<String> rawTopics();

    @WithName("topics.pricing-rules")
    String topicPricingRules();

    @WithName("topics.context-data")
    String topicContextData();

    @WithName("topics.raw-data")
    List<String> topicRawData();

    @WithName("topics.aggregated")
    String topicAggregated();

    @WithName("topics.aggregated-table-friendly")
    String topicAggregatedTableFriendly();

    @WithName("metrics.aggregations")
    Map<String, String> metricsAggregations();

    /**
     * This is a map of metric names to context keys. The value of the context key will be interpreted as a comma-separated list of values.
     * The metric will be replaced with multiple metrics, one for each value in the list.
     * Each new metric will have the same context as the original metric, except for the context key which will be set to contain
     * just the respective element from the list. The value will be set to the original value divided by the number of elements in the list.
     * <p>
     * For example, if the incoming metric is {initialMetricName: "bytesin", context: {writers: "v1,v2", c2: "v3"}, value: 10} and this map contains
     * {bytesin: writers}, then the original metric will be replaced by two metrics:
     * {initialMetricName: "bytesin", context: {writers: "v1", c2: "v3"}, value: 5},
     * {initialMetricName: "bytesin", context: {writers: "v2", c2: "v3"}, value: 5}
     * <p>
     * This is handy when you have a metric that represents usage of a resource by multiple entities, and you want to split the metric
     * into individual metrics for each entity.
     * <p>
     * If a metric's context contains a single value for the context key, the metric will not be split.
     * If a metric's context does not contain the context key, the metric also will not be split.
     */
    @WithName("metrics.transformations.splitValueAmongListMembers")
    Map<String, String> splitValueAmongListMembers();

    /**
     * This is a map of metric names to context keys. Whenever a metric with the TOPIC entity type is encountered, it will be transformed
     * into a metric with the PRINCIPAL entity type. The "name" of the metric will be placed into the context under the "topic" key.
     * Then the "name" of the metric will be replaced with the value of the context key specified for this metric.
     * <p>
     * For example, if the incoming metric is
     * {initialMetricName: "bytesProduced", context: {c1: "my-user", c2: "v2"}, value: 10, name: "my-topic", entityType: "TOPIC"}
     * and this map contains {bytesProduced: c1}, then the original metric will be replaced by the following metric:
     * {initialMetricName: "bytesProduced", context: {topic: "my-topic", c2: "v2"}, value: 10, name: "my-user", entityType: "PRINCIPAL"}
     * <p>
     * This transformation is applied *after* the splitValueAmongListMembers transformation, and together they form a powerful combination,
     * that allows to split topic usage metrics among the principals using them.
     * <p>
     * If a matching metric is encountered, but the corresponding context does not have the specified key, the metric will not be transformed
     * and a warning will be logged.
     */
    @WithName("metrics.transformations.topicMetricToPrincipalMetric")
    Map<String, String> topicMetricToPrincipalMetric();
}
