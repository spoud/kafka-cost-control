package io.spoud.kcc.aggregator.stream;

import io.spoud.kcc.aggregator.CostControlConfigProperties;
import lombok.Builder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Builder
public class TestConfigProperties implements CostControlConfigProperties {
    private String adminPassword;
    private String topicAggregatedTableFriendly;
    private List<String> rawTopics;
    private String applicationId;
    private String topicPricingRules;
    private String topicContextData;
    private List<String> topicRawData;
    private String topicAggregated;
    private Map<String, String> metricsAggregations;
    private Map<String, String> splitValueAmongListMembers;
    private Map<String, MissingKeyHandling> splitMetricAmongPrincipalsMissingKeyHandling;
    private String splitMetricAmongPrincipalsFallbackPrincipal;
    @Builder.Default
    private Duration aggregationWindowSize = Duration.parse("PT1H");

    @Override
    public Duration aggregationWindowSize() {
        return aggregationWindowSize;
    }

    @Override
    public String adminPassword() {
        return adminPassword;
    }

    @Override
    public List<String> rawTopics() {
        return rawTopics;
    }

    @Override
    public String topicPricingRules() {
        return topicPricingRules;
    }

    @Override
    public String topicContextData() {
        return topicContextData;
    }

    @Override
    public List<String> topicRawData() {
        return topicRawData;
    }

    @Override
    public String topicAggregated() {
        return topicAggregated;
    }

    @Override
    public String topicAggregatedTableFriendly() {
        return topicAggregatedTableFriendly;
    }

    @Override
    public Map<String, String> metricsAggregations() {
        return metricsAggregations;
    }

    @Override
    public Map<String, String> splitTopicMetricAmongPrincipals() {
        return splitValueAmongListMembers;
    }

    @Override
    public Map<String, MissingKeyHandling> splitMetricAmongPrincipalsMissingKeyHandling() {
        return splitMetricAmongPrincipalsMissingKeyHandling;
    }

    @Override
    public String splitMetricAmongPrincipalsFallbackPrincipal() {
        return splitMetricAmongPrincipalsFallbackPrincipal;
    }
}
