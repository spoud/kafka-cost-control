package io.spoud.kcc.aggregator.stream;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.smallrye.common.annotation.Identifier;
import io.spoud.kcc.aggregator.CostControlConfigProperties;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.BytesDeserializer;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Retry;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Re-applies today's context rules and pricing rules to stored data from a start time on, the way
 * {@code kafka-streams-application-reset} resets an application plus the OLAP table:
 * <ol>
 *     <li>stop Kafka Streams;</li>
 *     <li>decide the start: the requested time aligned to a window, but no earlier than the first
 *     complete window the raw topics still hold (older windows can't be rebuilt, so they stay);</li>
 *     <li>delete Kafka Streams' internal topics, which hold window state and stream time - left in
 *     place, they made replayed windows count as expired and dropped them;</li>
 *     <li>delete the stored OLAP windows from the start on - a row's id includes its context, so
 *     re-enriched windows would otherwise land next to the old ones;</li>
 *     <li>rewind the raw topics to the start and the pricing rules to their beginning;</li>
 *     <li>wipe the local state and restart the application.</li>
 * </ol>
 * If deleting the internal topics fails (e.g. missing ACLs), nothing else is touched and the
 * application restarts as it was.
 */
@Singleton
public class KafkaStreamManager {
    private final CostControlConfigProperties configProperties;
    private final KafkaStreams kafkaStreams;
    private final Map<String, Object> kafkaConfig;
    private final String applicationId;
    private final AggregatedMetricsRepository aggregatedMetricsRepository;

    public KafkaStreamManager(CostControlConfigProperties configProperties, KafkaStreams kafkaStreams,
                              @Identifier("default-kafka-broker") Map<String, Object> kafkaConfig,
                              @ConfigProperty(name = "kafka.application.id") String applicationId,
                              AggregatedMetricsRepository aggregatedMetricsRepository) {
        this.configProperties = configProperties;
        this.kafkaStreams = kafkaStreams;
        this.kafkaConfig = kafkaConfig;
        this.applicationId = applicationId;
        this.aggregatedMetricsRepository = aggregatedMetricsRepository;
    }

    /**
     * @param requestedStart the time to rebuild from, or null for everything the raw topics hold
     * @return what was done, for the caller
     */
    public String reprocess(Instant requestedStart) {
        Log.infov("Reprocessing requested from {0}, stopping kafka stream", requestedStart);
        boolean closed = kafkaStreams.close(new KafkaStreams.CloseOptions()
                .timeout(Duration.ofMinutes(1))
                .leaveGroup(true));
        if (!closed) {
            Log.errorv("Unable to close kafka streams");
        }
        try (AdminClient admin = AdminClient.create(kafkaConfig); var consumer = offsetLookupConsumer()) {
            var rawPartitions = partitionsOf(consumer, configProperties.rawTopics());
            var start = ReprocessPlan.effectiveStart(requestedStart, configProperties.aggregationWindowSize(),
                    earliestRecordTimes(consumer, rawPartitions));
            Log.infov("Rebuilding from {0}", start);

            var internalTopics = ReprocessPlan.internalTopics(applicationId, admin.listTopics().names().get());
            Log.infov("Deleting Kafka Streams internal topics {0}", internalTopics);
            admin.deleteTopics(internalTopics).all().get();

            int deletedRows = aggregatedMetricsRepository.deleteFrom(start);

            var offsets = new HashMap<TopicPartition, OffsetAndMetadata>();
            offsets.putAll(offsetsAt(consumer, rawPartitions, start));
            consumer.beginningOffsets(partitionsOf(consumer, List.of(configProperties.topicPricingRules())))
                    .forEach((partition, offset) -> offsets.put(partition, new OffsetAndMetadata(offset)));
            Log.infov("Resetting offsets of consumer group {0} to {1}", applicationId, offsets);
            alterStreamsAppOffsets(admin, offsets);

            kafkaStreams.cleanUp();
            var summary = "Reprocessing from %s: deleted %d internal topics and %d stored rows; restarting to rebuild"
                    .formatted(start, internalTopics.size(), deletedRows);
            Log.info(summary);
            return summary;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return fail("interrupted", ex);
        } catch (ExecutionException | RuntimeException ex) {
            return fail(ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage(), ex);
        } finally {
            Log.infov("Restarting the application");
            Quarkus.asyncExit();
        }
    }

    private String fail(String reason, Exception ex) {
        Log.errorv(ex, "Reprocessing failed: {0}", reason);
        return "Reprocessing failed (" + reason + "), see the logs; restarting with the data as it was";
    }

    @Retry(maxRetries = -1, maxDuration = 5L, durationUnit = ChronoUnit.MINUTES,
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

    /** A plain consumer for offset lookups; it never subscribes, so it doesn't join the group. */
    private KafkaConsumer<Bytes, Bytes> offsetLookupConsumer() {
        var props = new Properties();
        props.putAll(kafkaConfig);
        props.remove(ConsumerConfig.GROUP_ID_CONFIG);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, BytesDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, BytesDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    private static List<TopicPartition> partitionsOf(KafkaConsumer<?, ?> consumer, Collection<String> topics) {
        var partitions = new ArrayList<TopicPartition>();
        for (String topic : topics) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic);
            if (infos != null) {
                infos.forEach(info -> partitions.add(new TopicPartition(topic, info.partition())));
            }
        }
        return partitions;
    }

    /** The timestamp of the first record still in each partition (retention may have removed older ones). */
    private static List<Instant> earliestRecordTimes(KafkaConsumer<?, ?> consumer, List<TopicPartition> partitions) {
        return consumer.offsetsForTimes(partitions.stream().collect(Collectors.toMap(p -> p, p -> 0L)))
                .values().stream()
                .filter(found -> found != null)
                .map(found -> Instant.ofEpochMilli(found.timestamp()))
                .toList();
    }

    /** The first offset at or after {@code start} in each partition; the end where nothing is that recent. */
    private static Map<TopicPartition, OffsetAndMetadata> offsetsAt(KafkaConsumer<?, ?> consumer,
                                                                    List<TopicPartition> partitions, Instant start) {
        Map<TopicPartition, OffsetAndTimestamp> found = consumer.offsetsForTimes(
                partitions.stream().collect(Collectors.toMap(p -> p, p -> start.toEpochMilli())));
        Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
        var offsets = new HashMap<TopicPartition, OffsetAndMetadata>();
        for (TopicPartition partition : partitions) {
            var at = found.get(partition);
            offsets.put(partition, new OffsetAndMetadata(at != null ? at.offset() : ends.get(partition)));
        }
        return offsets;
    }
}
