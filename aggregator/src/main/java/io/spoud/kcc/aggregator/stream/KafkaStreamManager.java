package io.spoud.kcc.aggregator.stream;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.smallrye.common.annotation.Identifier;
import io.spoud.kcc.aggregator.CostControlConfigProperties;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.BytesDeserializer;
import org.apache.kafka.streams.KafkaStreams;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Retry;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
public class KafkaStreamManager {
    private final CostControlConfigProperties configProperties;
    private final KafkaStreams kafkaStreams;
    private final Map<String, Object> kafkaConfig;
    private final String applicationId;

    public KafkaStreamManager(CostControlConfigProperties configProperties, KafkaStreams kafkaStreams,
                              @Identifier("default-kafka-broker") Map<String, Object> kafkaConfig,
                              @ConfigProperty(name = "kafka.application.id") String applicationId) {
        this.configProperties = configProperties;
        this.kafkaStreams = kafkaStreams;
        this.kafkaConfig = kafkaConfig;
        this.applicationId = applicationId;
    }

    public void reprocess(Instant startTime) {
        Log.infov("Reprocessing requested for time {0}, stopping kafka stream", startTime);
        KafkaStreams.CloseOptions closeOptions = new KafkaStreams.CloseOptions()
                .timeout(Duration.ofMinutes(1))
                .leaveGroup(true);
        final boolean closed = kafkaStreams.close(closeOptions);
        if (!closed) {
            Log.errorv("Unable to close kafka streams");
        }

        Log.infov("Resetting offsets for consumer-group {0} for topics {1}", applicationId, configProperties.rawTopics());

        try (AdminClient adminClient = AdminClient.create(kafkaConfig)) {
            final Map<TopicPartition, OffsetAndMetadata> topicPartitionOffsetAndMetadataMap =
                    adminClient.listConsumerGroupOffsets(applicationId).partitionsToOffsetAndMetadata().get();

            Map<TopicPartition, OffsetAndMetadata> toOffset =
                    getOffsetForGivenTime(startTime, topicPartitionOffsetAndMetadataMap);

            Log.infov(
                    "Will reset offsets to {0}",
                    toOffset.entrySet().stream()
                            .map(entry -> entry.getKey() + ":" + entry.getValue().offset())
                            .collect(Collectors.joining(",")));

            alterStreamsAppOffsets(adminClient, toOffset);
        } catch (InterruptedException ex){
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            Log.errorv(ex, "Unable to reset offsets for consumer-group {0}", applicationId);
        }

        Log.infov("Killing app so it reboots and reprocesses everything");
        Quarkus.asyncExit();
    }

    @Retry(maxDuration = 5L, durationUnit = ChronoUnit.MINUTES,
            delay = 5L, delayUnit = ChronoUnit.SECONDS, retryOn = {ExecutionException.class})
    public void alterStreamsAppOffsets(AdminClient adminClient, Map<TopicPartition, OffsetAndMetadata> toOffset) throws ExecutionException, InterruptedException {
        try {
            adminClient.alterConsumerGroupOffsets(applicationId, toOffset).all().get();
        } catch (ExecutionException ex) {
            Log.warnv("Failed attempt to reset offset for consumer group \"{0}\": {1} (will retry)",
                    applicationId, ex.getCause().getMessage());
            throw ex;
        }
    }

    private Map<TopicPartition, OffsetAndMetadata> getOffsetForGivenTime(
            Instant time, Map<TopicPartition, OffsetAndMetadata> currentPartitionOffsets) {
        Map<TopicPartition, OffsetAndMetadata> toOffset = new HashMap<>();

        final Stream<TopicPartition> rawInputTopicOnly =
                currentPartitionOffsets.keySet().stream()
                        .filter(topicPartition -> configProperties.rawTopics().contains(topicPartition.topic()));

        if (time == null) {
            // Request time is null, reset offsets to the beginning
            rawInputTopicOnly.forEach(key -> toOffset.put(key, new OffsetAndMetadata(1)));
        } else {
            // Request time is not null, lookup offset for this given time
            final Properties props = new Properties();
            props.putAll(kafkaConfig);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, applicationId);
            props.setProperty(
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, BytesDeserializer.class.getName());
            props.setProperty(
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, BytesDeserializer.class.getName());
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
                final long timestamp = time.toEpochMilli();
                final Map<TopicPartition, Long> offsetLookupRequest =
                        rawInputTopicOnly.collect(Collectors.toMap(key -> key, key -> timestamp));

                consumer.offsetsForTimes(offsetLookupRequest).entrySet().stream()
                        .filter(entry -> entry.getValue() != null)
                        .forEach(
                                (entry) ->
                                        toOffset.put(entry.getKey(), new OffsetAndMetadata(entry.getValue().offset())));
            }
        }
        return toOffset;
    }

}
