 package io.spoud.kcc.aggregator.stream;

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.logging.Log;
import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.data.RawTelegrafData;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import io.spoud.kcc.aggregator.repository.ContextDataRepository;
import io.spoud.kcc.aggregator.repository.GaugeRepository;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.aggregator.stream.serialization.SerdeFactory;
import io.spoud.kcc.data.*;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MetricEnricherTest {
    public static final String TOPIC_RAW_TELEGRAF = "metrics-raw-telegraf";
    public static final String TOPIC_PRICING_RULES = "pricing-rules";
    public static final String TOPIC_CONTEXT_DATA = "context-data";
    public static final String TOPIC_AGGREGATED = "aggregated";
    public static final String TOPIC_AGGREGATED_TABLE_FRIENDLY = "aggregated-table-friendly";

    private TopologyTestDriver testDriver;

    private TestInputTopic<String, RawTelegrafData> rawTelegrafDataTopic;
    private TestInputTopic<String, PricingRule> pricingRulesTopic;
    private KeyValueStore<String, ContextData> contextDataStore;
    private TestOutputTopic<AggregatedDataKey, AggregatedDataWindowed> aggregatedTopic;
    private TestOutputTopic<AggregatedDataKey, AggregatedDataTableFriendly> aggregatedTableFriendlyTopic;
    private MetricNameRepository metricRepository;
    private GaugeRepository gaugeRepository;
    private MetricReducer metricReducer;
    private ResultCaptor<AggregatedData> reducerResult;

    @NotNull
    private static Properties createKafkaProperties() {
        Properties config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "test");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        config.put("schema.registry.url", "mock://test");
        config.put(StreamsConfig.STATE_DIR_CONFIG, "/tmp/kafka-streams-test-" + RandomStringUtils.randomAlphanumeric(10));
        // TODO read from properties
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
        return config;
    }

    @BeforeEach
    void setup() {
        var configProperties = TestConfigProperties.builder()
                .applicationId("test")
                .topicAggregated(TOPIC_AGGREGATED)
                .topicAggregatedTableFriendly(TOPIC_AGGREGATED_TABLE_FRIENDLY)
                .topicPricingRules(TOPIC_PRICING_RULES)
                .topicRawData(List.of(TOPIC_RAW_TELEGRAF))
                .topicContextData(TOPIC_CONTEXT_DATA)
                .metricsAggregations(Map.of("confluent_kafka_server_retained_bytes", "max"))
                .build();
        metricReducer = Mockito.spy(new MetricReducer(configProperties));
        metricRepository = new MetricNameRepository(metricReducer);
        gaugeRepository = new GaugeRepository(new SimpleMeterRegistry());
        ContextDataRepository contextDataRepository = Mockito.mock(ContextDataRepository.class);

        final CachedContextDataManager cachedContextDataManager = new CachedContextDataManager(contextDataRepository);
        Properties kafkaProperties = createKafkaProperties();
        SerdeFactory serdeFactory = new SerdeFactory(new HashMap(kafkaProperties));
        reducerResult = new ResultCaptor<>();
        Mockito.doAnswer(reducerResult).when(metricReducer).apply(Mockito.any(), Mockito.any());
        MetricEnricher metricEnricher = new MetricEnricher(metricRepository, cachedContextDataManager, configProperties, serdeFactory, gaugeRepository, metricReducer, Mockito.mock(AggregatedMetricsRepository.class));
        final Topology topology = metricEnricher.metricEnricherTopology();
        System.out.println(topology.describe());

        testDriver = new TopologyTestDriver(topology, kafkaProperties);
        contextDataStore = testDriver.getKeyValueStore(MetricEnricher.CONTEXT_DATA_TABLE_NAME);
        Mockito.when(contextDataRepository.getStore()).thenReturn(contextDataStore);

        rawTelegrafDataTopic = testDriver.createInputTopic(
                TOPIC_RAW_TELEGRAF,
                new StringSerializer(),
                serdeFactory.getRawTelegrafSerde().serializer());

        pricingRulesTopic = testDriver.createInputTopic(
                TOPIC_PRICING_RULES,
                new StringSerializer(),
                serdeFactory.getPricingRuleSerde().serializer());

        aggregatedTopic = testDriver.createOutputTopic(
                TOPIC_AGGREGATED,
                serdeFactory.getAggregatedKeySerde().deserializer(),
                serdeFactory.getAggregatedWindowedSerde().deserializer());

        aggregatedTableFriendlyTopic = testDriver.createOutputTopic(
                TOPIC_AGGREGATED_TABLE_FRIENDLY,
                serdeFactory.getAggregatedKeySerde().deserializer(),
                serdeFactory.getAggregatedTableFriendlySerde().deserializer());
    }

    @AfterEach
    void tearDown() {
        if (testDriver != null) {
            try {
                testDriver.close();
            } catch (Exception e) {
                Log.warn("Unable to close test driver");
            }
        }
    }

    @Test
    void should_add_context() throws InterruptedException {
        contextDataStore.put(
                "id1",
                new ContextData(
                        Instant.now(),
                        null,
                        null,
                        EntityType.TOPIC,
                        "spoud_.*",
                        Map.of("cost-unit", "my-cost-unit")));
        Thread.sleep(1000);

        rawTelegrafDataTopic.pipeInput(generateTopicRawTelegraf("spoud_topic_1", 1.5));

        assertThat(aggregatedTopic.getQueueSize()).isEqualTo(1);
        final AggregatedDataWindowed aggregated = aggregatedTopic.readValue();
        assertThat(aggregated.getContext()).containsEntry("cost-unit", "my-cost-unit");
        assertThat(aggregated.getValue()).isEqualTo(1.5);
        assertThat(aggregated.getCost()).isNull();
        assertThat(aggregated.getName()).isEqualTo("spoud_topic_1");
        assertThat(aggregated.getInitialMetricName()).isEqualTo("confluent_kafka_server_sent_bytes");
        assertThat(aggregated.getEntityType()).isEqualTo(EntityType.TOPIC);
        assertThat(aggregated.getTags()).containsEntry("env", "dev");
    }

    @Test
    void gauges_should_not_overlap() throws InterruptedException {
        contextDataStore.put(
                "id1",
                new ContextData(
                        Instant.now(),
                        null,
                        null,
                        EntityType.TOPIC,
                        "spoud_.*",
                        Map.of("cost-unit", "my-cost-unit")));
        Thread.sleep(1000);

        // generate metrics for two different topics that will be mapped to the same context
        rawTelegrafDataTopic.pipeInput(generateTopicRawTelegraf("spoud_topic_1", 1.5));
        rawTelegrafDataTopic.pipeInput(generateTopicRawTelegraf("spoud_topic_2", 2.5));

        // make sure that each topic spawns its own gauge even if they share the same context
        assertThat(gaugeRepository.getGaugeValues()).containsKeys(
                new GaugeRepository.GaugeKey("kcc_confluent_kafka_server_sent_bytes", Tags.of("cost-unit", "my-cost-unit", "env", "dev", "topic", "spoud_topic_1")),
                new GaugeRepository.GaugeKey("kcc_confluent_kafka_server_sent_bytes", Tags.of("cost-unit", "my-cost-unit", "env", "dev", "topic", "spoud_topic_2"))
        );
    }

    @Test
    void should_end_up_in_friendly_table() {
        contextDataStore.put(
                "id1",
                new ContextData(
                        Instant.now(),
                        null,
                        null,
                        EntityType.TOPIC,
                        "spoud_.*",
                        Map.of("cost-unit", "my-cost-unit")));

        rawTelegrafDataTopic.pipeInput(generateTopicRawTelegraf("spoud_topic_1", 1.5));

        assertThat(aggregatedTableFriendlyTopic.getQueueSize()).isEqualTo(1);
        final AggregatedDataTableFriendly aggregatedTableFriendly =
                aggregatedTableFriendlyTopic.readValue();
        assertThat(aggregatedTableFriendly.getContext()).isEqualTo("{\"cost-unit\":\"my-cost-unit\"}");
        assertThat(aggregatedTableFriendly.getValue()).isEqualTo(1.5);
        assertThat(aggregatedTableFriendly.getCost()).isNull();
        assertThat(aggregatedTableFriendly.getName()).isEqualTo("spoud_topic_1");
        assertThat(aggregatedTableFriendly.getInitialMetricName())
                .isEqualTo("confluent_kafka_server_sent_bytes");
        assertThat(aggregatedTableFriendly.getEntityType()).isEqualTo(EntityType.TOPIC);
        assertThat(aggregatedTableFriendly.getTags())
                .isEqualTo("{\"env\":\"dev\",\"topic\":\"spoud_topic_1\"}");
    }

    @Test
    void should_reduce_to_max() {
        contextDataStore.put(
                "id1",
                new ContextData(
                        Instant.now(),
                        null,
                        null,
                        EntityType.TOPIC,
                        "spoud_.*",
                        Map.of("cost-unit", "my-cost-unit")));

        rawTelegrafDataTopic.pipeInput(generateTopicRawTelegraf(Instant.now(), "confluent_kafka_server_retained_bytes", "spoud_topic_1", 15.0));
        rawTelegrafDataTopic.pipeInput(generateTopicRawTelegraf(Instant.now(), "confluent_kafka_server_retained_bytes", "spoud_topic_1", 33.0));

        assertThat(aggregatedTableFriendlyTopic.getQueueSize()).isEqualTo(2);
        final AggregatedDataTableFriendly aggregatedTableFriendly =
                aggregatedTableFriendlyTopic.readValuesToList().get(1);
        assertThat(aggregatedTableFriendly.getValue()).isEqualTo(33.0);
        Mockito.verify(metricReducer, Mockito.times(1)).apply(
                Mockito.argThat((AggregatedData data) -> Double.compare(data.getValue(), 15.0) == 0),
                Mockito.argThat((AggregatedData data) -> Double.compare(data.getValue(), 33.0) == 0)
        );
        assertThat(reducerResult.value.getValue()).isEqualTo(33.0); // we have configured the reducer to take the max value (see application.yaml)
    }

    @Test
    void should_reduce_to_sum() {
        contextDataStore.put(
                "id1",
                new ContextData(
                        Instant.now(),
                        null,
                        null,
                        EntityType.TOPIC,
                        "spoud_.*",
                        Map.of("cost-unit", "my-cost-unit")));

        rawTelegrafDataTopic.pipeInput(generateTopicRawTelegraf(Instant.now(), "confluent_kafka_server_sent_bytes", "spoud_topic_1", 15.0));
        rawTelegrafDataTopic.pipeInput(generateTopicRawTelegraf(Instant.now(), "confluent_kafka_server_sent_bytes", "spoud_topic_1", 33.0));

        assertThat(aggregatedTableFriendlyTopic.getQueueSize()).isEqualTo(2);
        final AggregatedDataTableFriendly aggregatedTableFriendly =
                aggregatedTableFriendlyTopic.readValuesToList().get(1);
        assertThat(aggregatedTableFriendly.getValue()).isEqualTo(48.0);
        Mockito.verify(metricReducer, Mockito.times(1)).apply(
                Mockito.argThat((AggregatedData data) -> Double.compare(data.getValue(), 15.0) == 0),
                Mockito.argThat((AggregatedData data) -> Double.compare(data.getValue(), 48.0) == 0) // The reducer should write the sum of the two values into the second argument
        );
        assertThat(reducerResult.value.getValue()).isEqualTo(48.0); // we have not provided any aggregation config for this metric, so it should sum by default
    }

    @Test
    void should_use_pricing_rule() {
        pricingRulesTopic.pipeInput(
                "confluent_kafka_server_sent_bytes",
                new PricingRule(Instant.now(), "confluent_kafka_server_sent_bytes", 0.1, 0.05));

        rawTelegrafDataTopic.pipeInput(generateTopicRawTelegraf("spoud_topic_1", 5));
        rawTelegrafDataTopic.pipeInput(generateTopicRawTelegraf("spoud_topic_1", 5));

        final List<AggregatedDataWindowed> list = aggregatedTopic.readValuesToList();
        assertThat(list).hasSize(2);
        final AggregatedDataWindowed aggregated = list.get(1);
        assertThat(aggregated.getValue()).isEqualTo(10);
        assertThat(aggregated.getCost()).isEqualTo(0.6);
        assertThat(aggregated.getName()).isEqualTo("spoud_topic_1");
        assertThat(aggregated.getInitialMetricName()).isEqualTo("confluent_kafka_server_sent_bytes");
        assertThat(aggregated.getEntityType()).isEqualTo(EntityType.TOPIC);
        assertThat(aggregated.getTags()).containsEntry("env", "dev");
    }

    @Test
    void should_produce_null_cost_when_no_pricing_rule() {
        rawTelegrafDataTopic.pipeInput(generateTopicRawTelegraf("spoud_topic_1", 5));
        rawTelegrafDataTopic.pipeInput(generateTopicRawTelegraf("spoud_topic_2", 5));

        final List<AggregatedDataWindowed> list = aggregatedTopic.readValuesToList();
        assertThat(list).hasSize(2);
        assertThat(list).extracting(AggregatedDataWindowed::getCost).containsOnlyNulls();
    }

    @Test
    void should_merge_context_data() throws InterruptedException {
        contextDataStore.put(
                "id1",
                new ContextData(
                        Instant.now(),
                        null,
                        null,
                        EntityType.TOPIC,
                        "spoud_.*",
                        Map.of("cost-unit", "my-cost-unit")));
        contextDataStore.put(
                "id2",
                new ContextData(
                        Instant.now(), null, null, EntityType.TOPIC, ".*_v1", Map.of("version", "1.0")));
        Thread.sleep(1000);

        rawTelegrafDataTopic.pipeInput(generateTopicRawTelegraf("spoud_topic_v1", 1.5));

        assertThat(aggregatedTopic.getQueueSize()).isEqualTo(1);
        final AggregatedDataWindowed aggregated = aggregatedTopic.readValue();
        assertThat(aggregated.getContext()).containsEntry("cost-unit", "my-cost-unit");
        assertThat(aggregated.getContext()).containsEntry("version", "1.0");
        assertThat(aggregated.getValue()).isEqualTo(1.5);
        assertThat(aggregated.getCost()).isNull();
        assertThat(aggregated.getName()).isEqualTo("spoud_topic_v1");
        assertThat(aggregated.getInitialMetricName()).isEqualTo("confluent_kafka_server_sent_bytes");
        assertThat(aggregated.getEntityType()).isEqualTo(EntityType.TOPIC);
        assertThat(aggregated.getTags()).containsEntry("env", "dev");
    }

    @Test
    void newer_context_should_override_older_context() throws InterruptedException {
        var expectedVersion = "1.0";
        var expectedCostUnit = "my-cost-unit-current";

        contextDataStore.put(
                "current",
                new ContextData(
                        Instant.now(), null, null, EntityType.TOPIC, "spoud_.*", Map.of(
                        "version", expectedVersion)));
        for (int i = 1; i <= 100; i++) {
            // these are old contexts, and we expect their values to not be merged into the final context
            contextDataStore.put(
                    "id" + i,
                    new ContextData(
                            Instant.now().minusSeconds(i + 10),
                            null,
                            null,
                            EntityType.TOPIC,
                            "spoud_.*",
                            Map.of("cost-unit", "old-cost-unit-" + i, "version", "alpha-" + i)));
        }
        contextDataStore.put(
                "current2",
                new ContextData(
                        Instant.now(),
                        null,
                        null,
                        EntityType.TOPIC,
                        "spoud_.*",
                        Map.of("cost-unit", expectedCostUnit)));
        Thread.sleep(1000);

        rawTelegrafDataTopic.pipeInput(generateTopicRawTelegraf("spoud_topic_v1", 1.5));

        assertThat(aggregatedTopic.getQueueSize()).isEqualTo(1);
        final AggregatedDataWindowed aggregated = aggregatedTopic.readValue();
        assertThat(aggregated.getContext()).containsEntry("cost-unit", expectedCostUnit);
        assertThat(aggregated.getContext()).containsEntry("version", expectedVersion);
        assertThat(aggregated.getName()).isEqualTo("spoud_topic_v1");
        assertThat(aggregated.getEntityType()).isEqualTo(EntityType.TOPIC);
    }

    @Test
    void should_create_unique_key_for_raw_data() {
        final RawTelegrafData topic = generateTopicRawTelegraf("spoud_topic_v1", 1.5);
        final RawTelegrafData principal = generatePrincipalRawTelegraf("george", 1.5);
        final RawTelegrafData unknown = generateUnknownRawTelegraf(1.5);
        rawTelegrafDataTopic.pipeInput(topic);
        rawTelegrafDataTopic.pipeInput(principal);
        rawTelegrafDataTopic.pipeInput(unknown);

        final Map<AggregatedDataKey, AggregatedDataTableFriendly> all =
                aggregatedTableFriendlyTopic.readKeyValuesToMap();
        assertThat(all).hasSize(3);
        assertThat(all.keySet())
                .containsExactlyInAnyOrder(
                        new AggregatedDataKey(
                                topic.timestamp().truncatedTo(ChronoUnit.HOURS), topic.timestamp().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1)),
                                EntityType.TOPIC,
                                "spoud_topic_v1",
                                "confluent_kafka_server_sent_bytes"),
                        new AggregatedDataKey(
                                principal.timestamp().truncatedTo(ChronoUnit.HOURS), principal.timestamp().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1)),
                                EntityType.PRINCIPAL,
                                "george",
                                "confluent_kafka_server_request_bytes"),
                        new AggregatedDataKey(
                                unknown.timestamp().truncatedTo(ChronoUnit.HOURS), unknown.timestamp().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1)),
                                EntityType.UNKNOWN,
                                "",
                                "confluent_kafka_server_partition_count"));
    }

    @Test
    void should_have_metric_names() {
        final RawTelegrafData topic = generateTopicRawTelegraf("spoud_topic_v1", 1.5);
        final RawTelegrafData principal = generatePrincipalRawTelegraf("george", 1.5);
        rawTelegrafDataTopic.pipeInput(topic);
        rawTelegrafDataTopic.pipeInput(principal);
        final List<MetricNameEntity> metricNames = metricRepository.getMetricNames();
        assertThat(metricNames)
                .extracting(MetricNameEntity::metricName)
                .containsExactly(
                        "confluent_kafka_server_request_bytes", "confluent_kafka_server_sent_bytes");
        assertThat(metricNames)
                .extracting(MetricNameEntity::lastSeen)
                .contains(topic.timestamp(), principal.timestamp());
        assertThat(metricNames)
                .extracting(MetricNameEntity::aggregationType)
                .containsExactly("SUM", "SUM");
    }

    @Test
    void should_accept_gauges_and_counters() {
        final RawTelegrafData gauge = generateTopicRawTelegraf(Instant.now(), "confluent_kafka_server_request_bytes", "spoud_topic_v1", 15.0, "gauge");
        final RawTelegrafData counter = generateTopicRawTelegraf(Instant.now(), "confluent_kafka_server_partition_count", "spoud_topic_v1", 15.0, "counter");
        final RawTelegrafData bad = generateTopicRawTelegraf(Instant.now(), "confluent_kafka_server_partition_count", "spoud_topic_v1", 15.0, "bad");
        rawTelegrafDataTopic.pipeInput(gauge);
        rawTelegrafDataTopic.pipeInput(counter);
        rawTelegrafDataTopic.pipeInput(bad);
        // no assertion, just making sure that the stream processor does not crash when receiving different types of metrics
    }

    @Test
    void should_use_valid_context_data() {
        Instant t1 = Instant.parse("2024-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2024-02-01T00:00:00Z");
        contextDataStore.put(
                "id1",
                new ContextData(
                        Instant.now(), null, null, EntityType.TOPIC, "prefix_.*", Map.of("prefix", "value")));
        contextDataStore.put(
                "id2",
                new ContextData(
                        Instant.now(), null, t1, EntityType.TOPIC, ".*_postfix", Map.of("k1", "1.0")));
        contextDataStore.put(
                "id3",
                new ContextData(
                        Instant.now(), t1, t2, EntityType.TOPIC, ".*_postfix", Map.of("k2", "2.0")));
        contextDataStore.put(
                "id4",
                new ContextData(
                        Instant.now(), t2, null, EntityType.TOPIC, ".*_postfix", Map.of("k3", "3.0")));

        rawTelegrafDataTopic.pipeInput("1", generateTopicRawTelegraf(t1.minus(Duration.ofDays(1)), "prefix_topic_postfix", 1.0));
        rawTelegrafDataTopic.pipeInput("2", generateTopicRawTelegraf(t1, "prefix_topic_postfix", 2.0));
        rawTelegrafDataTopic.pipeInput("3", generateTopicRawTelegraf(t1.plus(Duration.ofDays(1)), "prefix_topic_postfix", 3.0));
        rawTelegrafDataTopic.pipeInput("4", generateTopicRawTelegraf(t2, "prefix_topic_postfix", 4.0));
        rawTelegrafDataTopic.pipeInput("5", generateTopicRawTelegraf(t2.plus(Duration.ofDays(1)), "prefix_topic_postfix", 5.0));

        final List<AggregatedDataWindowed> result = aggregatedTopic.readValuesToList();
        assertThat(result).hasSize(5);

        assertThat(result.get(0).getContext()).containsExactlyInAnyOrderEntriesOf(Map.of("prefix", "value", "k1", "1.0"));
        assertThat(result.get(1).getContext()).containsExactlyInAnyOrderEntriesOf(Map.of("prefix", "value", "k2", "2.0"));
        assertThat(result.get(2).getContext()).containsExactlyInAnyOrderEntriesOf(Map.of("prefix", "value", "k2", "2.0"));
        assertThat(result.get(3).getContext()).containsExactlyInAnyOrderEntriesOf(Map.of("prefix", "value", "k3", "3.0"));
        assertThat(result.get(4).getContext()).containsExactlyInAnyOrderEntriesOf(Map.of("prefix", "value", "k3", "3.0"));
    }

    @Test
    void should_window_per_hour() {
        final Instant baseTime = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(10));
        rawTelegrafDataTopic.pipeInput("1", generateTopicRawTelegraf(baseTime, "prefix_topic_postfix", 1.0), baseTime);
        rawTelegrafDataTopic.pipeInput("2", generateTopicRawTelegraf(baseTime.plus(Duration.ofMinutes(15)), "prefix_topic_postfix", 2.0), baseTime.plus(Duration.ofMinutes(15)));
        rawTelegrafDataTopic.pipeInput("3", generateTopicRawTelegraf(baseTime.plus(Duration.ofMinutes(30)), "prefix_topic_postfix", 3.0), baseTime.plus(Duration.ofMinutes(30)));
        rawTelegrafDataTopic.pipeInput("4", generateTopicRawTelegraf(baseTime.plus(Duration.ofMinutes(45)), "prefix_topic_postfix", 4.0), baseTime.plus(Duration.ofMinutes(45)));
        rawTelegrafDataTopic.pipeInput("5", generateTopicRawTelegraf(baseTime.plus(Duration.ofMinutes(60)), "prefix_topic_postfix", 5.0), baseTime.plus(Duration.ofMinutes(60)));
        rawTelegrafDataTopic.pipeInput("6", generateTopicRawTelegraf(baseTime.plus(Duration.ofMinutes(75)), "prefix_topic_postfix", 6.0), baseTime.plus(Duration.ofMinutes(75)));

        final Map<String, String> tags = Map.of("env", "dev", "topic", "prefix_topic_postfix");

        final List<KeyValue<AggregatedDataKey, AggregatedDataWindowed>> list = aggregatedTopic.readKeyValuesToList();
        assertThat(list).hasSize(6);

        // keep only last version
        final Map<AggregatedDataKey, AggregatedDataWindowed> map = list.stream().collect(Collectors.toMap(kv -> kv.key, kv -> kv.value, (l, r) -> r));
        assertThat(map).hasSize(2);
        assertThat(map).containsAllEntriesOf(Map.of(
                // first entry
                new AggregatedDataKey(baseTime, baseTime.plus(Duration.ofHours(1)), EntityType.TOPIC, "prefix_topic_postfix", "confluent_kafka_server_sent_bytes"),
                new AggregatedDataWindowed(baseTime, baseTime.plus(Duration.ofHours(1)), EntityType.TOPIC, "prefix_topic_postfix", "confluent_kafka_server_sent_bytes", 10.0, null, tags, Map.of()),

                // second entry
                new AggregatedDataKey(baseTime.plus(Duration.ofHours(1)), baseTime.plus(Duration.ofHours(2)), EntityType.TOPIC, "prefix_topic_postfix", "confluent_kafka_server_sent_bytes"),
                new AggregatedDataWindowed(baseTime.plus(Duration.ofHours(1)), baseTime.plus(Duration.ofHours(2)), EntityType.TOPIC, "prefix_topic_postfix", "confluent_kafka_server_sent_bytes", 11.0, null, tags, Map.of())
        ));
    }

    @Test
    void should_replace_regex_in_context() {
        contextDataStore.put(
                "id1",
                new ContextData(
                        Instant.now(),
                        null,
                        null,
                        EntityType.TOPIC,
                        "^([a-z0-9-_]+)\\.([a-z0-9-_]+)\\.([a-z0-9-_]+)\\.([a-z0-9-_]+)$",
                        Map.of("stage", "$1"
                                , "app", "$2"
                                , "subject", "$3"
                                , "version", "$4")));
        rawTelegrafDataTopic.pipeInput("1", generateTopicRawTelegraf("stage_name.app_name.subject-name.v1", 1.0));
        final List<AggregatedDataWindowed> list = aggregatedTopic.readValuesToList();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getContext()).containsEntry("stage", "stage_name");
        assertThat(list.get(0).getContext()).containsEntry("app", "app_name");
        assertThat(list.get(0).getContext()).containsEntry("subject", "subject-name");
        assertThat(list.get(0).getContext()).containsEntry("version", "v1");
    }

    @Test
    void should_ignore_regex_replacement_issue() {
        contextDataStore.put(
                "id1",
                new ContextData(
                        Instant.now(),
                        null,
                        null,
                        EntityType.TOPIC,
                        "stage_(.+)",
                        Map.of("stage", "$1"
                                , "app", "$2"
                                , "subject", "$3"
                                , "version", "$4")));
        rawTelegrafDataTopic.pipeInput("1", generateTopicRawTelegraf("stage_name.app_name.subject-name.v1", 1.0));
        final List<AggregatedDataWindowed> list = aggregatedTopic.readValuesToList();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getContext()).containsEntry("stage", "name.app_name.subject-name.v1");
        assertThat(list.get(0).getContext()).containsEntry("app", "$2");
        assertThat(list.get(0).getContext()).containsEntry("subject", "$3");
        assertThat(list.get(0).getContext()).containsEntry("version", "$4");
    }

    @Test
    void should_replace_multiple_regex() {
        contextDataStore.put(
                "id1",
                new ContextData(
                        Instant.now(),
                        null,
                        null,
                        EntityType.TOPIC,
                        "berne-parkings.*",
                        Map.of("app", "bern-parking"
                                , "domain", "bern-parking"
                                , "cu", "spoud")));

        contextDataStore.put(
                "id2",
                new ContextData(
                        Instant.now(),
                        null,
                        null,
                        EntityType.TOPIC,
                        ".*(avro|xml|json).*",
                        Map.of("format", "$1")));

        rawTelegrafDataTopic.pipeInput("1", generateTopicRawTelegraf("berne-parkings-json", 1.0));

        final List<AggregatedDataWindowed> list = aggregatedTopic.readValuesToList();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getContext()).containsEntry("format", "json");
        assertThat(list.get(0).getContext()).containsEntry("app", "bern-parking");
        assertThat(list.get(0).getContext()).containsEntry("domain", "bern-parking");
        assertThat(list.get(0).getContext()).containsEntry("cu", "spoud");

    }

    private RawTelegrafData generateTopicRawTelegraf(String topicName, double value) {
        return generateTopicRawTelegraf(Instant.now(), topicName, value);
    }

    private RawTelegrafData generateTopicRawTelegraf(Instant time, String topicName, double value) {
        return generateTopicRawTelegraf(time, "confluent_kafka_server_sent_bytes", topicName, value);
    }

    private RawTelegrafData generateTopicRawTelegraf(Instant time, String metricName, String topicName, double value) {
        return generateTopicRawTelegraf(time, metricName, topicName, value, "gauge");
    }

    private RawTelegrafData generateTopicRawTelegraf(Instant time, String metricName, String topicName, double value, String metricType) {
        return new RawTelegrafData(
                time,
                metricName,
                Map.of(metricType, value),
                Map.of("env", "dev", "topic", topicName));
    }

    private RawTelegrafData generatePrincipalRawTelegraf(String principalId, double value) {
        return new RawTelegrafData(
                Instant.now(),
                "confluent_kafka_server_request_bytes",
                Map.of("gauge", value),
                Map.of("env", "dev", "principal_id", principalId));
    }

    private RawTelegrafData generateUnknownRawTelegraf(double value) {
        return new RawTelegrafData(
                Instant.now(),
                "confluent_kafka_server_partition_count",
                Map.of("gauge", value),
                Map.of("env", "dev"));
    }

    private ReadOnlyKeyValueStore<String, ContextData> apply(String name) {
        return testDriver.getKeyValueStore(name);
    }

    private class ResultCaptor<T> implements Answer {
        public T value;

        @Override
        public T answer(InvocationOnMock invocation) throws Throwable {
            value = (T) invocation.callRealMethod();
            return value;
        }
    }
}
