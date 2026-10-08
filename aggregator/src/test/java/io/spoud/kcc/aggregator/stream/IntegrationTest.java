package io.spoud.kcc.aggregator.stream;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.spoud.kcc.aggregator.data.RawTelegrafData;
import io.spoud.kcc.aggregator.bills.BillEntity;
import io.spoud.kcc.aggregator.bills.BillsRepository;
import io.spoud.kcc.aggregator.bills.OtherLine;
import io.spoud.kcc.aggregator.graphql.ContextDataResource;
import io.spoud.kcc.aggregator.graphql.MetricsResource;
import io.spoud.kcc.aggregator.graphql.PricingRulesResource;
import io.spoud.kcc.aggregator.stream.config.KafkaResource;
import io.spoud.kcc.aggregator.stream.config.SchemaRegistryResource;
import io.spoud.kcc.aggregator.stream.utils.KafkaTestUtils;
import io.spoud.kcc.data.ContextData;
import io.spoud.kcc.data.EntityType;
import io.spoud.kcc.data.PricingRule;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.streams.KafkaStreams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@TestSecurity(authorizationEnabled = false)
@QuarkusTestResource(KafkaResource.class)
@QuarkusTestResource(SchemaRegistryResource.class)
public class IntegrationTest {

    private static final String TOPIC_INPUT_RAW_METRICS = "metrics-raw-telegraf-env";
    private static final String TOPIC_INPUT_PRICING_RULES = "pricing-rules";
    private static final String TOPIC_INPUT_CONTEXT_DATA = "context-data";
    private static final String TOPIC_OUTPUT_AGGREGATED = "aggregated";

    @Inject
    MetricsResource metricsResource;
    @Inject
    PricingRulesResource pricingRulesResource;
    @Inject
    ContextDataResource contextDataResource;

    @Inject
    KafkaStreams kafkaStreams;
    @Inject
    BillsRepository billsRepository;

    KafkaProducer<String, RawTelegrafData> rawMetricProducer;
    KafkaProducer<String, ContextData> contextDataProducer;
    KafkaProducer<String, PricingRule> pricingRuleProducer;
    AdminClient adminClient;

    @BeforeEach
    void setup() throws ExecutionException, InterruptedException {
        rawMetricProducer = KafkaTestUtils.createJsonProducer();
        contextDataProducer = KafkaTestUtils.createAvroProducer();
        pricingRuleProducer = KafkaTestUtils.createAvroProducer();
        adminClient = KafkaTestUtils.createAdminClient();

        if (!adminClient.listTopics().names().get().contains(TOPIC_INPUT_RAW_METRICS)) {
            createKafkaTopics();

            rawMetricProducer.send(
                    new ProducerRecord<>(
                            TOPIC_INPUT_RAW_METRICS,
                            "id1",
                            new RawTelegrafData(Instant.now(), "metric-name-1", Map.of("gauge", 1), Map.of())));
            contextDataProducer.send(
                    new ProducerRecord<>(
                            TOPIC_INPUT_CONTEXT_DATA,
                            "id1",
                            new ContextData(
                                    Instant.now(), null, null, EntityType.TOPIC, ".*topic-name.*", Map.of("key", "value"))));
            pricingRuleProducer.send(
                    new ProducerRecord<>(
                            TOPIC_INPUT_PRICING_RULES,
                            "metric_name",
                            new PricingRule(Instant.now(), "metric_name", 1.0, 2.0, null, null, null, null)));
        }

        // await for kafka stream to be fully started
        await()
                .atMost(Duration.ofMinutes(1))
                .untilAsserted(
                        () -> assertThat(kafkaStreams.state()).isEqualTo(KafkaStreams.State.RUNNING));
    }

    private void createKafkaTopics() throws InterruptedException, ExecutionException {
        adminClient
                .createTopics(
                        List.of(
                                new NewTopic(TOPIC_INPUT_RAW_METRICS, 1, (short) 1),
                                new NewTopic(TOPIC_INPUT_PRICING_RULES, 1, (short) 1),
                                new NewTopic(TOPIC_INPUT_CONTEXT_DATA, 1, (short) 1),
                                new NewTopic(TOPIC_OUTPUT_AGGREGATED, 1, (short) 1)))
                .all()
                .get();
    }

    @AfterEach
    void tearDown() {
        rawMetricProducer.close();
        contextDataProducer.close();
        pricingRuleProducer.close();
    }

    @Test
    void should_find_used_metric_names() {
        await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(metricsResource.metricNames()).hasSize(1));
    }

    @Test
    void should_restore_context_data() {
        await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(contextDataResource.contextData()).hasSize(1));
    }

    @Test
    void should_keep_bills_in_a_compacted_topic_it_creates() throws Exception {
        // the repository creates the topic; a bill written by someone else shows up
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(billsRepository.all()).isNotNull());
        var description = adminClient.describeConfigs(List.of(
                new ConfigResource(ConfigResource.Type.TOPIC, "bills"))).all().get().values().iterator().next();
        assertThat(description.get("cleanup.policy").value()).isEqualTo("compact");

        try (KafkaProducer<String, io.spoud.kcc.data.Bill> billProducer = KafkaTestUtils.createAvroProducer()) {
            billProducer.send(new ProducerRecord<>("bills", "2026-08", new BillEntity("2026-08", null, 1.0, 2.0,
                    3.0, 4.0, List.of(), Instant.now(), "elsewhere").toAvro())).get();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(billsRepository.all()).extracting(BillEntity::month).contains("2026-08"));

        billsRepository.save(new BillEntity("2026-09", null, 0.27, 0.22, 8.12, 22.5,
                List.of(new OtherLine("Support", 5.0, OtherLine.Allocation.CONTEXT, Map.of("tenant", "data-platform"))),
                Instant.now(), "test"));
        billsRepository.delete("2026-08");
        assertThat(billsRepository.all()).extracting(BillEntity::month).containsExactly("2026-09");
        // other lines round-trip through the topic with their context
        assertThat(billsRepository.all().getFirst().otherLines()).singleElement()
                .satisfies(line -> assertThat(line.context()).containsEntry("tenant", "data-platform"));
    }

    @Test
    void should_restore_pricing_rules() {
        await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(pricingRulesResource.pricingRules()).hasSize(1));
    }
}
