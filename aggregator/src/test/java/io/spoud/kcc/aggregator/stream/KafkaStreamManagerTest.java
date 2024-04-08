package io.spoud.kcc.aggregator.stream;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConsumerGroupOffsetsResult;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.streams.KafkaStreams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class KafkaStreamManagerTest {
    KafkaStreamManager kafkaStreamManager;

    public static final String TOPIC_RAW_TELEGRAF = "metrics-raw-telegraf";
    public static final String TOPIC_PRICING_RULES = "pricing-rules";
    public static final String TOPIC_CONTEXT_DATA = "context-data";
    public static final String TOPIC_AGGREGATED = "aggregated";
    public static final String TOPIC_AGGREGATED_TABLE_FRIENDLY = "aggregated-table-friendly";
    public static final String APP_ID = "test";

    @BeforeEach
    void setUp() {
        var configProperties = TestConfigProperties.builder()
                .applicationId(APP_ID)
                .topicAggregated(TOPIC_AGGREGATED)
                .topicAggregatedTableFriendly(TOPIC_AGGREGATED_TABLE_FRIENDLY)
                .topicPricingRules(TOPIC_PRICING_RULES)
                .topicRawData(List.of(TOPIC_RAW_TELEGRAF))
                .topicContextData(TOPIC_CONTEXT_DATA)
                .metricsAggregations(Map.of("confluent_kafka_server_retained_bytes", "max"))
                .build();
        var kafkaStreams = Mockito.mock(KafkaStreams.class);
        Mockito.when(kafkaStreams.close(Mockito.any(KafkaStreams.CloseOptions.class))).thenReturn(true);
        kafkaStreamManager = new KafkaStreamManager(configProperties, kafkaStreams, Map.of(), APP_ID);
    }

    @Test
    void should_rethrow_execution_exception() throws ExecutionException, InterruptedException {
        var admin = Mockito.mock(AdminClient.class);
        var result = Mockito.mock(AlterConsumerGroupOffsetsResult.class);
        var future = Mockito.mock(KafkaFuture.class);
        Mockito.when(admin.alterConsumerGroupOffsets(Mockito.eq(APP_ID), Mockito.anyMap()))
                .thenReturn(result);
        Mockito.when(result.all()).thenReturn(future);
        Mockito.when(future.get()).thenThrow(new ExecutionException("Whoops", new RuntimeException("Something went wrong")));

        assertThrows(ExecutionException.class, () -> kafkaStreamManager.alterStreamsAppOffsets(admin, Map.of()));
    }

    @Test
    void should_alter_offsets() throws ExecutionException, InterruptedException {
        var admin = Mockito.spy(AdminClient.class);
        var result = Mockito.mock(AlterConsumerGroupOffsetsResult.class);
        var future = Mockito.mock(KafkaFuture.class);

        var toOffset = Map.of(new TopicPartition(TOPIC_RAW_TELEGRAF, 0), new OffsetAndMetadata(0));

        Mockito.when(admin.alterConsumerGroupOffsets(Mockito.eq(APP_ID), Mockito.anyMap()))
                .thenReturn(result);
        Mockito.when(result.all()).thenReturn(future);
        Mockito.when(future.get()).thenReturn(null);

        assertDoesNotThrow(() -> kafkaStreamManager.alterStreamsAppOffsets(admin, toOffset));
        Mockito.verify(admin).alterConsumerGroupOffsets(APP_ID, toOffset);
    }
}
