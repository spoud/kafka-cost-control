package io.spoud.kcc.aggregator;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
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
     * This is a map of metric names to context keys. The value of the context key will be interpreted as a comma-separated list of principals (applications, teams, users, ...).
     * The metric will be replaced with multiple metrics, one for each value (=principal) in the list.
     * Each metric will be of type PRINCIPAL and will have the respective principal as the record name.
     * The context will no longer contain the context key referencing principal names. Instead, it will hold a "topic" key with the original topic name.
     * The value of the original metric will be divided evenly among the resulting metrics.
     * <p>
     * For example, if the incoming metric is {type: TOPIC, name: "my-topic", initialMetricName: "bytesin", context: {writers: "v1,v2", c2: "v3"}, value: 10}
     * and this map contains {"bytesin": "writers"}, then the original metric will be replaced by two metrics:
     * <p>
     *     {type: PRINCIPAL, name: "v1", initialMetricName: "bytesin", context: {topic: "my-topic", c2: "v3"}, value: 5},<br/>
     *     {type: PRINCIPAL, name: "v2", initialMetricName: "bytesin", context: {topic: "my-topic", c2: "v3"}, value: 5}
     * <p>
     * This is handy when you have a metric that represents usage of a resource by multiple entities, and you want to split the metric
     * into individual metrics for each entity.
     * <p>
     * If a metric's context contains a single value for the context key, the metric will not be split.
     * If a metric's context does not contain the context key, the metric also will not be split.
     */
    @WithName("metrics.transformations.splitMetricAmongPrincipals")
    Map<String, String> splitTopicMetricAmongPrincipals();

    @WithName("metrics.transformations.config.splitMetricAmongPrincipals.missingKeyHandling")
    Map<String, MissingKeyHandling> splitMetricAmongPrincipalsMissingKeyHandling();

    /**
     * This is the fallback principal that will be used if the context key is missing or empty.
     * If the context key is missing, the metric will be assigned to a single fallback principal.
     * This only applies if the missingKeyHandling for a metric is set to ASSIGN_TO_FALLBACK.
     * @return the fallback principal name
     */
    @WithName("metrics.transformations.config.splitMetricAmongPrincipals.fallbackPrincipal")
    @WithDefault("unknown")
    String splitMetricAmongPrincipalsFallbackPrincipal();

    enum MissingKeyHandling {
        /**
         * If the key is missing, the metric will not be split, but passed through as is.
         */
        PASS_THROUGH,
        /**
         * If the key is missing, the metric will be assigned to a single fallback principal.
         */
        ASSIGN_TO_FALLBACK,
        /**
         * If the key is missing, the metric will be dropped.
         */
        DROP
    }
}
