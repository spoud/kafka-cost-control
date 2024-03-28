package io.spoud.kcc.aggregator.stream;

import io.spoud.kcc.aggregator.CostControlConfigProperties;
import lombok.Builder;

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
}
