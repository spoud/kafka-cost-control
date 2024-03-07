package io.spoud.kcc.aggregator.stream;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Identifier;
import io.spoud.kcc.aggregator.CostControlConfigProperties;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.BytesDeserializer;
import org.apache.kafka.streams.KafkaStreams;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
@RequiredArgsConstructor
public class KafkaStreamManager {

    private final CostControlConfigProperties configProperties;
    private final KafkaStreams kafkaStreams;

    @Inject
    @Identifier("default-kafka-broker")
    Map<String, Object> kafkaConfig;

    @ConfigProperty(name = "kafka.application.id")
    String applicationId;

    void onStart(@Observes StartupEvent event) {
    }

    void onShutdown(@Observes ShutdownEvent event) {
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

            // try for 5min to reset the offsets
            for (int i = 1; i <= 60; i++) {
                try {
                    adminClient.alterConsumerGroupOffsets(applicationId, toOffset).all().get();
                    break;
                } catch (ExecutionException ex1) {
                    Log.warnv(
                            "Attempt {0}: Unable to reset offset for consumer group \"{1}\", consumer group is certainly still attached, waiting a little bit before retrying",
                            i, applicationId);
                    Thread.sleep(5_000);
                }
            }
        }catch (InterruptedException ex){
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            Log.errorv(ex, "Unable to reset offsets for consumer-group {0}", applicationId);
        }

        Log.infov("Killing app so it reboots and reprocesses everything");
        Quarkus.asyncExit();

        // DOESN'T WORK
        //    Log.infov("Restarting the kafka stream application");
        //    kafkaStreams.start();
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
