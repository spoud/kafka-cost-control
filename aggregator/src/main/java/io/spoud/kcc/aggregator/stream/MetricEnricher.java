package io.spoud.kcc.aggregator.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.quarkus.logging.Log;
import io.spoud.kcc.aggregator.CostControlConfigProperties;
import io.spoud.kcc.aggregator.data.RawTelegrafData;
import io.spoud.kcc.aggregator.repository.GaugeRepository;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.aggregator.stream.serialization.SerdeFactory;
import io.spoud.kcc.data.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.*;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@ApplicationScoped
@RequiredArgsConstructor
public class MetricEnricher {

    public static final String CONTEXT_DATA_TABLE_NAME = "context-data-store";
    public static final String PRICING_DATA_TABLE_NAME = "pricing-rules-store";
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());
    private final MetricNameRepository metricRepository;
    private final CachedContextDataManager cachedContextDataManager;
    private final CostControlConfigProperties configProperties;
    private final SerdeFactory serdes;
    private final GaugeRepository gaugeRepository;
    private final MetricReducer metricReducer;

    @Produces
    public Topology metricEnricherTopology() {
        Log.infov("Will start MetricEnricher for topics: {0}", configProperties.rawTopics());

        TimeWindows tumblingWindow = TimeWindows.ofSizeWithNoGrace(Duration.ofHours(1));

        StreamsBuilder builder = new StreamsBuilder();

        builder.globalTable(
                configProperties.topicContextData(),
                Consumed.with(Serdes.String(), serdes.getContextDataSerde()).withName("context-data"),
                Materialized.as(CONTEXT_DATA_TABLE_NAME));

        KTable<String, PricingRule> pricingRulesTable = builder.table(
                configProperties.topicPricingRules(),
                Consumed.with(Serdes.String(), serdes.getPricingRuleSerde())
                        .withName("pricing-rules")
                        .withOffsetResetPolicy(Topology.AutoOffsetReset.EARLIEST),
                Materialized.as(PRICING_DATA_TABLE_NAME));

        KStream<String, RawTelegrafData> telegrafDataStream = builder.stream(
                configProperties.topicRawData(),
                Consumed.with(Serdes.String(), serdes.getRawTelegrafSerde()).withName("raw-telegraf"));

        // TODO: principal count per project/cost center
        // TODO: consumer count per project/cost center

        telegrafDataStream
                .peek(
                        (key, value) -> metricRepository.addMetricName(value.name(), value.timestamp()),
                        Named.as("populate-metric-names-list"))
                .selectKey(
                        (key, value) -> value.name(), Named.as("key-by-metricName")) // rekey by raw metric name
                .mapValues(this::addContextToRawData, Named.as("add-context")) // map to raw telegraf data
                .filter(
                        (key, value) -> (value != null && value.getEntityType() != null && value.getName() != null),
                        Named.as("filter-null"))
                .selectKey(
                        (key, value) -> value.getEntityType() + "_" + value.getName() + "_" + value.getInitialMetricName(),
                        Named.as("unique-key-for-windowing"))
                .groupByKey(Grouped.as("group-by-key"))
                .windowedBy(tumblingWindow)
                .reduce(metricReducer, Named.as("sum-aggregated-value-by-window"))
                .toStream(Named.as("convert-window-to-stream"))
                .map(MetricEnricher::mapToWindowedAggregate, Named.as("map-to-windowed-aggregated-data"))
                .leftJoin(pricingRulesTable, this::addPriceToWindowedMetric, Joined.as("join-pricing-rule"))
                .flatMapValues(this::splitValueAmongListMembers, Named.as("split-value-among-list-members"))
                .mapValues(this::mapTopicMetricToPrincipalMetricIfNeeded, Named.as("map-topic-metric-to-principal-metric"))
                .selectKey((key, value) -> new AggregatedDataKey(
                        value.getStartTime(),
                        value.getEndTime(),
                        value.getEntityType(),
                        value.getName(), value.getInitialMetricName()), Named.as("rekey-to-metric-name"))
                .peek((key, value) -> {
                    try {
                        var tags = Tags.of(Stream.concat(value.getContext().keySet().stream(), value.getTags().keySet().stream())
                                .map(k -> Tag.of(k, value.getContext().getOrDefault(k, value.getTags().get(k))))
                                .toList());
                        gaugeRepository.updateGauge("kcc_" + value.getInitialMetricName(), tags, value.getValue());
                    } catch (Exception e) {
                        Log.warnv("Error updating gauge for metric {0} and tags {1}", value.getName(), value.getTags(), e);
                    }
                })
                .to(
                        configProperties.topicAggregated(),
                        Produced.with(serdes.getAggregatedKeySerde(), serdes.getAggregatedWindowedSerde()).withName("output-topic"));

        builder.stream(configProperties.topicAggregated(), Consumed.with(serdes.getAggregatedKeySerde(), serdes.getAggregatedWindowedSerde()))
                .mapValues(this::convertMapsToJson, Named.as("convert-to-table-friendly-value"))
                .to(
                        configProperties.topicAggregatedTableFriendly(),
                        Produced.with(serdes.getAggregatedKeySerde(), serdes.getAggregatedTableFriendlySerde())
                                .withName("output-topic-table-friendly"));

        return builder.build();
    }

    private AggregatedData addContextToRawData(RawTelegrafData rawTelegrafData) {
        var telegrafData = new TelegrafDataWrapper(rawTelegrafData);

        AggregatedData.Builder aggregatedData = AggregatedData.newBuilder()
                .setTimestamp(rawTelegrafData.timestamp())
                .setInitialMetricName(rawTelegrafData.name())
                .setValue(telegrafData.getValue())
                .setTags(rawTelegrafData.tags())
                .setContext(Collections.emptyMap());
        List<CachedContextDataManager.CachedContextData> cachedContextData = cachedContextDataManager.getCachedContextData();
        telegrafData.enrichWithContext(cachedContextData).ifPresent(context -> aggregatedData
                .setName(context.name())
                .setContext(context.context())
                .setEntityType(context.type()));

        return aggregatedData.build();
    }

    private AggregatedDataWindowed addPriceToWindowedMetric(AggregatedDataWindowed data, PricingRule pricingRule) {
        final AggregatedDataWindowed.Builder builder = AggregatedDataWindowed.newBuilder(data);
        if (pricingRule != null) {
            builder.setCost(pricingRule.getBaseCost() + pricingRule.getCostFactor() * data.getValue());
        } else {
            Log.infov("No pricing rules found for \"{0}\"", data.getInitialMetricName());
        }
        return builder.build();
    }

    private AggregatedDataTableFriendly convertMapsToJson(AggregatedDataWindowed data) {
        final AggregatedDataTableFriendly.Builder builder = AggregatedDataTableFriendly.newBuilder()
                .setStartTime(data.getStartTime())
                .setEndTime(data.getEndTime())
                .setInitialMetricName(data.getInitialMetricName())
                .setName(data.getName())
                .setValue(data.getValue())
                .setCost(data.getCost())
                .setEntityType(data.getEntityType());
        try {
            builder
                    .setTags(OBJECT_MAPPER.writeValueAsString(data.getTags()))
                    .setContext(OBJECT_MAPPER.writeValueAsString(data.getContext()));
        } catch (JsonProcessingException e) {
            Log.error("Unable to convert map to json string", e);
        }
        return builder.build();
    }

    private static KeyValue<String, AggregatedDataWindowed> mapToWindowedAggregate(Windowed<String> windowKey, AggregatedData value) {
        return KeyValue.pair(
                value.getInitialMetricName(),
                AggregatedDataWindowed.newBuilder()
                        .setStartTime(windowKey.window().startTime())
                        .setEndTime(windowKey.window().endTime())
                        .setInitialMetricName(value.getInitialMetricName())
                        .setName(value.getName())
                        .setValue(value.getValue())
                        .setCost(value.getCost())
                        .setEntityType(value.getEntityType())
                        .setTags(value.getTags())
                        .setContext(value.getContext())
                        .build());
    }

    /// For more information, {@link CostControlConfigProperties#splitValueAmongListMembers() see config reference}
    private List<AggregatedDataWindowed> splitValueAmongListMembers(AggregatedDataWindowed metric) {
        var keyToSplitBy = configProperties.splitValueAmongListMembers().get(metric.getInitialMetricName());
        if (keyToSplitBy == null) {
            return List.of(metric);
        }
        var splitBy = Optional.of(metric)
                .map(AggregatedDataWindowed::getContext)
                .map(context -> context.get(keyToSplitBy))
                .map(value -> value.split(","))
                .orElse(new String[]{});
        if (splitBy.length == 0) {
            return List.of(metric);
        }
        var valuePerSplit = metric.getValue() / splitBy.length;
        var costPerSplit = metric.getCost() != null ? metric.getCost() / splitBy.length : null;
        return Stream.of(splitBy)
                .map(split -> {
                    var newContext = metric.getContext() != null
                            ? new HashMap<>(metric.getContext()) : new HashMap<String, String>();
                    newContext.put(keyToSplitBy, split);
                    return AggregatedDataWindowed.newBuilder()
                            .setStartTime(metric.getStartTime())
                            .setEndTime(metric.getEndTime())
                            .setInitialMetricName(metric.getInitialMetricName())
                            .setName(metric.getName())
                            .setValue(valuePerSplit)
                            .setCost(costPerSplit)
                            .setEntityType(metric.getEntityType())
                            .setTags(metric.getTags())
                            .setContext(newContext)
                            .build();
                })
                .toList();
    }

    private AggregatedDataWindowed mapTopicMetricToPrincipalMetricIfNeeded(AggregatedDataWindowed metric) {
        var keyToMapBy = configProperties.topicMetricToPrincipalMetric().get(metric.getInitialMetricName());
        if (keyToMapBy == null || metric.getEntityType() != EntityType.TOPIC) {
            return metric;
        }
        var principalName = Optional.of(metric)
                .map(AggregatedDataWindowed::getContext)
                .map(context -> context.get(keyToMapBy))
                .orElse(null);
        if (principalName == null) {
            return metric;
        }
        var newContext = metric.getContext() != null
                ? new HashMap<>(metric.getContext()) : new HashMap<String, String>();
        newContext.put("topic", metric.getName());
        newContext.remove(keyToMapBy);
        return AggregatedDataWindowed.newBuilder()
                .setStartTime(metric.getStartTime())
                .setEndTime(metric.getEndTime())
                .setInitialMetricName(metric.getInitialMetricName())
                .setName(principalName)
                .setValue(metric.getValue())
                .setCost(metric.getCost())
                .setEntityType(EntityType.PRINCIPAL)
                .setTags(metric.getTags())
                .setContext(newContext)
                .build();
    }
}
