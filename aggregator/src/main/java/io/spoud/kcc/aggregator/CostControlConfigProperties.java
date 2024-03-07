package io.spoud.kcc.aggregator;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;
import jakarta.validation.constraints.NotNull;

import java.util.List;

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
}
