package io.spoud.kcc.aggregator.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.quarkus.logging.Log;
import io.spoud.kcc.aggregator.CostControlConfigProperties;
import io.spoud.kcc.aggregator.data.RawTelegrafData;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import io.spoud.kcc.aggregator.repository.ContextDataStreamRepository;
import io.spoud.kcc.aggregator.repository.GaugeRepository;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.aggregator.stream.serialization.SerdeFactory;
import io.spoud.kcc.aggregator.stream.weighting.ConsumptionWeightSplitter;
import io.spoud.kcc.aggregator.stream.weighting.ImputationMode;
import io.spoud.kcc.aggregator.stream.weighting.TopicWeightAccumulator;
import io.spoud.kcc.aggregator.stream.weighting.WeightInput;
import io.spoud.kcc.aggregator.stream.weighting.WeightTier;
import io.spoud.kcc.data.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.AutoOffsetReset;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.WindowStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
@RequiredArgsConstructor
public class MetricEnricher {

    public static final String CONTEXT_DATA_TABLE_NAME = "context-data-store";
    public static final String PRICING_DATA_TABLE_NAME = "pricing-rules-store";
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());
    private final MetricNameRepository metricRepository;
    private final ContextDataStreamRepository contextDataStreamRepository;
    private final CostControlConfigProperties configProperties;
    private final SerdeFactory serdes;
    private final GaugeRepository gaugeRepository;
    private final MetricReducer metricReducer;
    private final AggregatedMetricsRepository aggregatedMetricsRepository;
    // Initialized inline so it is excluded from the @RequiredArgsConstructor (it is stateless).
    private final ConsumptionWeightSplitter weightSplitter = new ConsumptionWeightSplitter();

    public static final String WEIGHT_TOPIC_TAG = "topic";
    public static final String WEIGHT_PRINCIPAL_TAG = "principal_id";
    public static final String WEIGHT_TIER_TAG = "tier";
    // '|' is not a legal Kafka topic-name or telegraf metric-name character, so it is a safe compound-key separator.
    private static final String ACC_KEY_SEPARATOR = "|";

    @Produces
    public Topology metricEnricherTopology() {
        Log.infov("Will start MetricEnricher for topics: {0}", configProperties.rawTopics());

        TimeWindows tumblingWindow = TimeWindows.ofSizeWithNoGrace(configProperties.aggregationWindowSize());

        StreamsBuilder builder = new StreamsBuilder();

        builder.globalTable(
                configProperties.topicContextData(),
                Consumed.with(Serdes.String(), serdes.getContextDataSerde()).withName("context-data"),
                Materialized.<String, ContextData, KeyValueStore<Bytes, byte[]>>as(CONTEXT_DATA_TABLE_NAME)
                        .withCachingDisabled());

        KTable<String, PricingRule> pricingRulesTable = builder.table(
                configProperties.topicPricingRules(),
                Consumed.with(Serdes.String(), serdes.getPricingRuleSerde())
                        .withName("pricing-rules")
                        .withOffsetResetPolicy(AutoOffsetReset.earliest()),
                Materialized.as(PRICING_DATA_TABLE_NAME));

        KStream<String, RawTelegrafData> telegrafDataStream = builder.stream(
                configProperties.topicRawData(),
                Consumed.with(Serdes.String(), serdes.getRawTelegrafSerde()).withName("raw-telegraf"));

        // TODO: principal count per project/cost center
        // TODO: consumer count per project/cost center

        final Map<String, String> weightedTotalToWeightMetric = Optional
                .ofNullable(configProperties.weightedSplitTotalToWeightMetric())
                .orElse(Collections.emptyMap());
        final boolean weightedSplitEnabled = !weightedTotalToWeightMetric.isEmpty();
        // Metrics handled by the weighted-split branch must be kept out of the normal (even-split) path.
        final Set<String> weightedMetrics = weightedSplitEnabled
                ? Stream.concat(weightedTotalToWeightMetric.keySet().stream(), weightedTotalToWeightMetric.values().stream())
                        .collect(Collectors.toSet())
                : Collections.emptySet();

        KStream<String, AggregatedData> enriched = telegrafDataStream
                .peek(
                        (key, value) -> metricRepository.addMetricName(value.name(), value.timestamp()),
                        Named.as("populate-metric-names-list"))
                .selectKey(
                        (key, value) -> value.name(), Named.as("key-by-metricName")) // rekey by raw metric name
                .mapValues(this::addContextToRawData, Named.as("add-context")) // map to raw telegraf data
                .filter(
                        (key, value) -> (value != null && value.getEntityType() != null && value.getName() != null),
                        Named.as("filter-null"));

        // Normal path: everything not routed through the weighted split, using the legacy even split.
        KStream<String, AggregatedData> normalSource = weightedSplitEnabled
                ? enriched.filter((key, value) -> !weightedMetrics.contains(value.getInitialMetricName()),
                        Named.as("exclude-weighted-metrics"))
                : enriched;

        KStream<String, AggregatedDataWindowed> windowed = normalSource
                .flatMapValues(this::splitTopicMetricToPrincipalMetrics, Named.as("map-topic-metric-to-principal-metric"))
                .selectKey(
                        (key, value) -> value.getEntityType() + "_" + value.getName() + "_" + value.getInitialMetricName() + "_" + value.getContext().hashCode(),
                        Named.as("unique-key-for-windowing"))
                .mapValues(v -> {
                    v.setTags(Collections.emptyMap()); // no meaningful way to combine tags in the aggregation, so we just clear them
                    return v;
                })
                .groupByKey(Grouped.as("group-by-key"))
                .windowedBy(tumblingWindow)
                .reduce(metricReducer, Named.as("sum-aggregated-value-by-window"))
                .toStream(Named.as("convert-window-to-stream"))
                .map(MetricEnricher::mapToWindowedAggregate, Named.as("map-to-windowed-aggregated-data"));

        // Weighted path: distribute the authoritative topic total among consumers proportionally to measured usage.
        if (weightedSplitEnabled) {
            windowed = windowed.merge(
                    buildWeightedSplitStream(enriched, tumblingWindow, weightedTotalToWeightMetric),
                    Named.as("merge-weighted-split"));
        }

        windowed
                .leftJoin(pricingRulesTable, this::addPriceToWindowedMetric, Joined.as("join-pricing-rule"))
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
                        tags = tags.and(value.getEntityType().name().toLowerCase(), value.getName());
                        gaugeRepository.updateGauge("kcc_" + value.getInitialMetricName(), tags, value.getValue(), value.getStartTime());
                    } catch (Exception e) {
                        Log.warnv("Error updating gauge for metric {0} and tags {1}", value.getName(), value.getTags(), e);
                    }
                })
                .peek((k, v) -> aggregatedMetricsRepository.insertRow(v),
                        Named.as("insert-into-olap-db"))
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
        contextDataStreamRepository.enrichWithContext(telegrafData).ifPresent(aggregatedDataInfo -> aggregatedData
                .setName(aggregatedDataInfo.name())
                .setContext(aggregatedDataInfo.context())
                .setEntityType(aggregatedDataInfo.type()));

        return aggregatedData.build();
    }

    private AggregatedDataWindowed addPriceToWindowedMetric(AggregatedDataWindowed data, PricingRule pricingRule) {
        final AggregatedDataWindowed.Builder builder = AggregatedDataWindowed.newBuilder(data);
        if (pricingRule != null) {
            builder.setCost(pricingRule.getBaseCost() + pricingRule.getCostFactor() * data.getValue());
        } else {
            Log.debugv("No pricing rules found for \"{0}\"", data.getInitialMetricName());
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

    private List<AggregatedData> splitTopicMetricToPrincipalMetrics(AggregatedData metric) {
        if (metric.getEntityType() != EntityType.TOPIC) {
            return Collections.singletonList(metric);
        }
        return splitValueAmongListMembers(metric)
                .map(this::toPrincipalMetric)
                .toList();
    }

    /// For more information, {@link CostControlConfigProperties#splitTopicMetricAmongPrincipals() see config reference}
    private Stream<AggregatedData> splitValueAmongListMembers(AggregatedData metric) {
        var keyToSplitBy = configProperties.splitTopicMetricAmongPrincipals().get(metric.getInitialMetricName());
        if (keyToSplitBy == null) {
            return Stream.of(metric);
        }
        var missingKeyHandling = configProperties.splitMetricAmongPrincipalsMissingKeyHandling().getOrDefault(metric.getInitialMetricName(), CostControlConfigProperties.MissingKeyHandling.ASSIGN_TO_FALLBACK);
        var splitBy = Optional.of(metric)
                .map(AggregatedData::getContext)
                .map(context -> context.get(keyToSplitBy))
                .map(value -> value.split(","))
                .orElse(missingKeyHandling == CostControlConfigProperties.MissingKeyHandling.ASSIGN_TO_FALLBACK
                        ? new String[]{configProperties.splitMetricAmongPrincipalsFallbackPrincipal()}
                        : null);
        if (splitBy == null) {
            if (missingKeyHandling == CostControlConfigProperties.MissingKeyHandling.DROP) {
                return Stream.empty();
            } else if (missingKeyHandling == CostControlConfigProperties.MissingKeyHandling.PASS_THROUGH) {
                return Stream.of(metric);
            } else {
                throw new IllegalStateException("Unknown missing key handling: " + missingKeyHandling);
            }
        }
        var valuePerSplit = metric.getValue() / splitBy.length;
        var costPerSplit = metric.getCost() != null ? metric.getCost() / splitBy.length : null;
        return Stream.of(splitBy)
                .map(split -> {
                    var newContext = metric.getContext() != null
                            ? new HashMap<>(metric.getContext()) : new HashMap<String, String>();
                    newContext.put(keyToSplitBy, split);
                    return new AggregatedData(metric.getTimestamp(),
                            metric.getEntityType(),
                            metric.getName(),
                            metric.getInitialMetricName(),
                            valuePerSplit,
                            costPerSplit,
                            metric.getTags(),
                            newContext);
                });
    }

    private AggregatedData toPrincipalMetric(AggregatedData metric) {
        var keyToMapBy = configProperties.splitTopicMetricAmongPrincipals().get(metric.getInitialMetricName());
        if (keyToMapBy == null || metric.getEntityType() != EntityType.TOPIC) {
            return metric;
        }
        var principalName = Optional.of(metric)
                .map(AggregatedData::getContext)
                .map(context -> context.get(keyToMapBy))
                .orElse(null);
        if (principalName == null) {
            return metric;
        }
        var newContext = contextDataStreamRepository.getContextDataForName(EntityType.PRINCIPAL,
                principalName, metric.getTimestamp());
        newContext.put("topic", metric.getName());
        return new AggregatedData(metric.getTimestamp(), EntityType.PRINCIPAL, principalName,
                metric.getInitialMetricName(), metric.getValue(), metric.getCost(), metric.getTags(), newContext);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Weighted (fair) split. Unlike the even split above, this distributes an authoritative per-topic total
    // (e.g. sent_bytes) among the topic's consumers proportionally to their measured usage. Because the weights
    // are only known after aggregating a window, this necessarily runs *after* windowing, keyed by topic.
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Builds the weighted-split sub-topology: co-groups the authoritative topic-total records and the
     * per-(topic, principal) weight records by (topic, total-metric) within the tumbling window, then emits one
     * windowed PRINCIPAL record per consumer with its fair share of the total. The output is keyed by the total
     * metric name so it can be merged straight into the main stream just before the pricing join.
     */
    private KStream<String, AggregatedDataWindowed> buildWeightedSplitStream(
            KStream<String, AggregatedData> enriched,
            TimeWindows window,
            Map<String, String> totalToWeight) {

        final Set<String> totalMetrics = totalToWeight.keySet();
        // weight metric name -> total metric name (if a weight metric feeds several totals, the last one wins).
        final Map<String, String> weightToTotal = new HashMap<>();
        totalToWeight.forEach((total, weight) -> weightToTotal.put(weight, total));

        return enriched
                .filter((key, value) -> value.getInitialMetricName() != null
                                && (totalMetrics.contains(value.getInitialMetricName())
                                || weightToTotal.containsKey(value.getInitialMetricName())),
                        Named.as("weighted-filter-relevant"))
                .flatMap((key, value) -> toWeightInputs(value, totalMetrics, weightToTotal),
                        Named.as("weighted-to-input"))
                .groupByKey(Grouped.with("weighted-group-by-topic", Serdes.String(), serdes.getWeightInputSerde()))
                .windowedBy(window)
                .aggregate(
                        TopicWeightAccumulator::new,
                        (key, input, acc) -> acc.add(input),
                        Named.as("weighted-aggregate"),
                        Materialized.<String, TopicWeightAccumulator, WindowStore<Bytes, byte[]>>as("weighted-split-store")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(serdes.getTopicWeightAccumulatorSerde()))
                .toStream(Named.as("weighted-to-stream"))
                .flatMap(this::splitAccumulator, Named.as("weighted-split-emit"));
    }

    /** Route one enriched record to the weighted-split aggregation, keyed by (topic, total-metric). */
    private List<KeyValue<String, WeightInput>> toWeightInputs(
            AggregatedData value, Set<String> totalMetrics, Map<String, String> weightToTotal) {
        final String metric = value.getInitialMetricName();
        if (metric == null) {
            return Collections.emptyList();
        }
        if (totalMetrics.contains(metric)) {
            // Authoritative topic total: the topic name is the entity name (entityType TOPIC).
            if (value.getEntityType() != EntityType.TOPIC || value.getName() == null) {
                return Collections.emptyList();
            }
            return List.of(KeyValue.pair(accKey(value.getName(), metric), WeightInput.forTotal(value.getValue())));
        }
        final String totalMetric = weightToTotal.get(metric);
        if (totalMetric == null) {
            return Collections.emptyList();
        }
        final Map<String, String> tags = value.getTags() == null ? Collections.emptyMap() : value.getTags();
        final String topic = tags.get(WEIGHT_TOPIC_TAG);
        final String principal = tags.get(WEIGHT_PRINCIPAL_TAG);
        if (topic == null || topic.isBlank() || principal == null || principal.isBlank()) {
            Log.debugv("Dropping weight metric \"{0}\" without topic/principal tags", metric);
            return Collections.emptyList();
        }
        final WeightTier tier = WeightTier.fromString(tags.get(WEIGHT_TIER_TAG), WeightTier.T2_OFFSET_PROGRESS);
        return List.of(KeyValue.pair(accKey(topic, totalMetric), WeightInput.forWeight(principal, value.getValue(), tier)));
    }

    /** Apply the weighting rule to one windowed (topic, total-metric) accumulator, emitting per-principal shares. */
    private List<KeyValue<String, AggregatedDataWindowed>> splitAccumulator(
            Windowed<String> windowedKey, TopicWeightAccumulator acc) {
        final String compoundKey = windowedKey.key();
        final int idx = compoundKey.indexOf(ACC_KEY_SEPARATOR);
        if (idx < 0) {
            return Collections.emptyList();
        }
        final String topic = compoundKey.substring(0, idx);
        final String totalMetric = compoundKey.substring(idx + ACC_KEY_SEPARATOR.length());
        final Instant start = windowedKey.window().startTime();
        final Instant end = windowedKey.window().endTime();

        String rosterKey = Optional.ofNullable(configProperties.weightedSplitRosterContextKey())
                .map(m -> m.get(totalMetric)).orElse("readers");
        ImputationMode mode = Optional.ofNullable(configProperties.weightedSplitImputation())
                .map(m -> m.get(totalMetric)).orElse(ImputationMode.MEAN_REPORTER);
        final String fallback = configProperties.splitMetricAmongPrincipalsFallbackPrincipal();

        // Roster of authorized consumers, from the topic context (ACL-derived readers/writers).
        final Map<String, String> topicContext = contextDataStreamRepository.getContextDataForName(EntityType.TOPIC, topic, end);
        final List<String> roster = Optional.ofNullable(topicContext.get(rosterKey))
                .map(s -> Arrays.stream(s.split(","))
                        .map(String::trim)
                        .filter(x -> !x.isEmpty())
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());

        final Map<String, ConsumptionWeightSplitter.Weight> reported = new HashMap<>();
        acc.weights.forEach((principal, entry) ->
                reported.put(principal, new ConsumptionWeightSplitter.Weight(entry.bytes, entry.tier)));

        final ConsumptionWeightSplitter.Result result = weightSplitter.split(acc.total, reported, roster, mode, fallback);

        try {
            gaugeRepository.updateGauge("kcc_weighted_split_coverage",
                    Tags.of("topic", topic, "metric", totalMetric), result.coverage(), start);
        } catch (Exception e) {
            Log.warnv(e, "Unable to update coverage gauge for topic {0}", topic);
        }

        final List<KeyValue<String, AggregatedDataWindowed>> out = new ArrayList<>();
        result.allocations().forEach((principal, splitValue) -> {
            final Map<String, String> ctx = new HashMap<>(
                    contextDataStreamRepository.getContextDataForName(EntityType.PRINCIPAL, principal, end));
            ctx.put("topic", topic);
            out.add(KeyValue.pair(totalMetric, AggregatedDataWindowed.newBuilder()
                    .setStartTime(start)
                    .setEndTime(end)
                    .setEntityType(EntityType.PRINCIPAL)
                    .setName(principal)
                    .setInitialMetricName(totalMetric)
                    .setValue(splitValue)
                    .setCost(null)
                    .setTags(Collections.emptyMap())
                    .setContext(ctx)
                    .build()));
        });
        return out;
    }

    private static String accKey(String topic, String totalMetric) {
        return topic + ACC_KEY_SEPARATOR + totalMetric;
    }
}
