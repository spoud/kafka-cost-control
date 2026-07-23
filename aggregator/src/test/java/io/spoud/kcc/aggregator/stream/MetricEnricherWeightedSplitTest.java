package io.spoud.kcc.aggregator.stream;

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.logging.Log;
import io.spoud.kcc.aggregator.data.RawTelegrafData;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import io.spoud.kcc.aggregator.olap.OlapConfigProperties;
import io.spoud.kcc.aggregator.olap.OlapInfra;
import io.spoud.kcc.aggregator.repository.ContextDataStreamRepository;
import io.spoud.kcc.aggregator.repository.GaugeRepository;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.aggregator.stream.serialization.SerdeFactory;
import io.spoud.kcc.aggregator.stream.weighting.ImputationMode;
import io.spoud.kcc.data.*;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * End-to-end topology tests for the weighted (fair) split. Feeds an authoritative per-topic total plus
 * per-(topic, principal) weight metrics through the real {@link MetricEnricher} topology and asserts the
 * per-principal split coming out of the aggregated topic.
 *
 * <p>Deliberately Mockito-free: it uses lightweight real collaborators (OLAP disabled, context store injected
 * via an override) so it does not depend on a Java agent / inline mock maker.</p>
 */
class MetricEnricherWeightedSplitTest {

    private static final String TOPIC_RAW_TELEGRAF = "metrics-raw-telegraf";
    private static final String TOPIC_PRICING_RULES = "pricing-rules";
    private static final String TOPIC_CONTEXT_DATA = "context-data";
    private static final String TOPIC_AGGREGATED = "aggregated";
    private static final String TOPIC_AGGREGATED_TABLE_FRIENDLY = "aggregated-table-friendly";

    private static final String SENT_BYTES = "confluent_kafka_server_sent_bytes";
    private static final String WEIGHT_METRIC = "kcc_consumption_weight";
    private static final String TOPIC = "spoud_orders";

    private TopologyTestDriver testDriver;
    private TestInputTopic<String, RawTelegrafData> rawTelegrafDataTopic;
    private KeyValueStore<String, ContextData> contextDataStore;
    private TestOutputTopic<AggregatedDataKey, AggregatedDataWindowed> aggregatedTopic;
    private GaugeRepository gaugeRepository;

    /** Minimal OLAP config with the module disabled, so OLAP is an inert no-op. */
    private static final class DisabledOlapConfig implements OlapConfigProperties {
        public boolean enabled() { return false; }
        public Optional<Integer> totalMemoryLimitMb() { return Optional.empty(); }
        public int databaseMemoryLimitPercent() { return 50; }
        public String databaseUrl() { return "jdbc:duckdb:"; }
        public int databaseFlushIntervalSeconds() { return 10; }
        public int databaseMaxBufferedRows() { return 10; }
        public Optional<String> databaseSeedDataPath() { return Optional.empty(); }
        public Optional<Integer> insertSyntheticDays() { return Optional.empty(); }
    }

    private static Properties createKafkaProperties() {
        Properties config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-weighted");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        config.put("schema.registry.url", "mock://test-weighted");
        config.put(StreamsConfig.STATE_DIR_CONFIG, System.getProperty("java.io.tmpdir")
                + "/kafka-streams-test-" + RandomStringUtils.randomAlphanumeric(10));
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
        return config;
    }

    @BeforeEach
    void setup() {
        var configProperties = TestConfigProperties.builder()
                .applicationId("test-weighted")
                .topicAggregated(TOPIC_AGGREGATED)
                .topicAggregatedTableFriendly(TOPIC_AGGREGATED_TABLE_FRIENDLY)
                .topicPricingRules(TOPIC_PRICING_RULES)
                .topicRawData(List.of(TOPIC_RAW_TELEGRAF))
                .topicContextData(TOPIC_CONTEXT_DATA)
                .metricsAggregations(Map.of(SENT_BYTES, "sum"))
                .splitValueAmongListMembers(Map.of())
                .splitMetricAmongPrincipalsFallbackPrincipal("unknown")
                .splitMetricAmongPrincipalsMissingKeyHandling(Map.of())
                .weightedSplitTotalToWeightMetric(Map.of(SENT_BYTES, WEIGHT_METRIC))
                .weightedSplitRosterContextKey(Map.of(SENT_BYTES, "readers"))
                .weightedSplitImputation(Map.of(SENT_BYTES, ImputationMode.MEAN_REPORTER))
                .build();

        OlapConfigProperties olapConfig = new DisabledOlapConfig();
        OlapInfra olapInfra = new OlapInfra(olapConfig); // @PostConstruct not invoked → no DB connection
        MetricReducer metricReducer = new MetricReducer(configProperties);
        MetricNameRepository metricRepository = new MetricNameRepository(metricReducer, olapInfra);
        AggregatedMetricsRepository aggregatedMetricsRepository =
                new AggregatedMetricsRepository(olapConfig, olapInfra, metricRepository);
        gaugeRepository = new GaugeRepository(new SimpleMeterRegistry(), configProperties);

        // The context store only exists after the driver is built, so hand the repository a lazy holder.
        AtomicReference<ReadOnlyKeyValueStore<String, ContextData>> storeRef = new AtomicReference<>();
        ContextDataStreamRepository contextRepo = new ContextDataStreamRepository(null, null) {
            @Override
            public ReadOnlyKeyValueStore<String, ContextData> getStore() {
                return storeRef.get();
            }
        };

        Properties kafkaProperties = createKafkaProperties();
        SerdeFactory serdeFactory = new SerdeFactory(new HashMap<>(Map.of("schema.registry.url", "mock://test-weighted")));
        MetricEnricher metricEnricher = new MetricEnricher(metricRepository, contextRepo, configProperties, serdeFactory,
                gaugeRepository, metricReducer, aggregatedMetricsRepository);
        Topology topology = metricEnricher.metricEnricherTopology();

        testDriver = new TopologyTestDriver(topology, kafkaProperties);
        contextDataStore = testDriver.getKeyValueStore(MetricEnricher.CONTEXT_DATA_TABLE_NAME);
        storeRef.set(contextDataStore);

        rawTelegrafDataTopic = testDriver.createInputTopic(
                TOPIC_RAW_TELEGRAF, new StringSerializer(), serdeFactory.getRawTelegrafSerde().serializer());
        aggregatedTopic = testDriver.createOutputTopic(
                TOPIC_AGGREGATED,
                serdeFactory.getAggregatedKeySerde().deserializer(),
                serdeFactory.getAggregatedWindowedSerde().deserializer());
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

    private void putReaders(String readers) {
        contextDataStore.put("ctx-topic", new ContextData(Instant.now().minusSeconds(30), null, null,
                EntityType.TOPIC, "spoud_.*", Map.of("readers", readers)));
    }

    private RawTelegrafData total(double value) {
        return new RawTelegrafData(Instant.now(), SENT_BYTES, Map.of("gauge", value), Map.of("env", "dev", "topic", TOPIC));
    }

    private RawTelegrafData weight(String principal, double value, String tier) {
        return new RawTelegrafData(Instant.now(), WEIGHT_METRIC, Map.of("gauge", value),
                Map.of("env", "dev", "topic", TOPIC, "principal_id", principal, "tier", tier));
    }

    /** Latest per-principal value for the sent_bytes metric (the topology emits intermediate window states). */
    private Map<String, Double> latestPrincipalValues() {
        return aggregatedTopic.readKeyValuesToList().stream()
                .filter(kv -> kv.key.getEntityType() == EntityType.PRINCIPAL)
                .filter(kv -> kv.key.getInitialMetricName().equals(SENT_BYTES))
                .collect(Collectors.toMap(kv -> kv.key.getName(), kv -> kv.value.getValue(), (a, b) -> b));
    }

    @Test
    @DisplayName("99/1 traffic → 99/1 cost split, not 50/50")
    void heavy_consumer_pays_its_share() {
        putReaders("heavy,light");

        rawTelegrafDataTopic.pipeInput(total(1000.0));
        rawTelegrafDataTopic.pipeInput(weight("heavy", 990.0, "T1"));
        rawTelegrafDataTopic.pipeInput(weight("light", 10.0, "T1"));

        Map<String, Double> values = latestPrincipalValues();
        assertThat(values).containsOnlyKeys("heavy", "light");
        assertThat(values.get("heavy")).isCloseTo(990.0, within(1e-6));
        assertThat(values.get("light")).isCloseTo(10.0, within(1e-6));
    }

    @Test
    @DisplayName("only per-principal (no topic-level) lines are emitted for a weighted metric")
    void no_topic_level_line_survives() {
        putReaders("heavy,light");
        rawTelegrafDataTopic.pipeInput(total(1000.0));
        rawTelegrafDataTopic.pipeInput(weight("heavy", 990.0, "T1"));
        rawTelegrafDataTopic.pipeInput(weight("light", 10.0, "T1"));

        boolean anyTopicLevel = aggregatedTopic.readKeyValuesToList().stream()
                .anyMatch(kv -> kv.key.getInitialMetricName().equals(SENT_BYTES)
                        && kv.key.getEntityType() == EntityType.TOPIC);
        assertThat(anyTopicLevel).isFalse();
    }

    @Test
    @DisplayName("partial coverage: non-reporter gets the mean reporter share, coverage gauge is exposed")
    void partial_coverage_uses_mean_imputation() {
        putReaders("a,b,c"); // c never reports

        rawTelegrafDataTopic.pipeInput(total(1000.0));
        rawTelegrafDataTopic.pipeInput(weight("a", 800.0, "T2"));
        rawTelegrafDataTopic.pipeInput(weight("b", 100.0, "T2"));

        // S=900, mean=450, W=1350
        Map<String, Double> values = latestPrincipalValues();
        assertThat(values.get("a")).isCloseTo(1000.0 * 800 / 1350, within(1e-6));
        assertThat(values.get("b")).isCloseTo(1000.0 * 100 / 1350, within(1e-6));
        assertThat(values.get("c")).isCloseTo(1000.0 * 450 / 1350, within(1e-6));

        assertThat(gaugeRepository.getGaugeValues().entrySet().stream()
                .filter(e -> e.getKey().name().equals("kcc_weighted_split_coverage"))
                .map(Map.Entry::getValue)
                .findFirst()).hasValueSatisfying(v -> assertThat(v).isCloseTo(900.0 / 1350.0, within(1e-6)));
    }

    @Test
    @DisplayName("no telemetry at all → legacy even split across the roster")
    void no_reporters_falls_back_to_even_split() {
        putReaders("a,b");

        rawTelegrafDataTopic.pipeInput(total(1000.0));

        Map<String, Double> values = latestPrincipalValues();
        assertThat(values.get("a")).isCloseTo(500.0, within(1e-6));
        assertThat(values.get("b")).isCloseTo(500.0, within(1e-6));
    }

    @Test
    @DisplayName("a better tier supersedes a coarser one for the same principal")
    void tier_precedence_is_applied() {
        putReaders("heavy,light");

        rawTelegrafDataTopic.pipeInput(total(1000.0));
        rawTelegrafDataTopic.pipeInput(weight("heavy", 999.0, "T3")); // coarse
        rawTelegrafDataTopic.pipeInput(weight("heavy", 900.0, "T1")); // precise, should win
        rawTelegrafDataTopic.pipeInput(weight("light", 100.0, "T1"));

        // heavy weight = 900 (T3 ignored), S = 1000
        Map<String, Double> values = latestPrincipalValues();
        assertThat(values.get("heavy")).isCloseTo(900.0, within(1e-6));
        assertThat(values.get("light")).isCloseTo(100.0, within(1e-6));
    }
}
