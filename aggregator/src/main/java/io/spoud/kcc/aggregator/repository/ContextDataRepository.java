package io.spoud.kcc.aggregator.repository;

import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.kafka.Record;
import io.spoud.kcc.aggregator.data.ContextDataEntity;
import io.spoud.kcc.aggregator.data.Metric;
import io.spoud.kcc.aggregator.stream.CachedContextDataManager;
import io.spoud.kcc.aggregator.stream.MetricEnricher;
import io.spoud.kcc.data.ContextData;
import io.spoud.kcc.data.EntityType;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@ApplicationScoped
public class ContextDataRepository {

    private final Emitter<Record<String, ContextData>> contextEmitter;
    private final KafkaStreams kafkaStreams;
    private final CachedContextDataManager cachedContextDataManager;

    public ContextDataRepository(
            @Channel("context-data-out") Emitter<Record<String, ContextData>> contextEmitter,
            KafkaStreams kafkaStreams,
            CachedContextDataManager cachedContextDataManager
    ) {
        this.contextEmitter = contextEmitter;
        this.kafkaStreams = kafkaStreams;
        this.cachedContextDataManager = cachedContextDataManager;
    }

    public ContextDataEntity deleteContext(String id) {
        contextEmitter.send(Record.of(id, null)).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                Log.errorv("Unable to send tombstone for context to kafka for key {0}", id, throwable);
            } else {
                Log.infov("Context tombstone send on kafka for key {0}", id);
            }
        });
        return null;
    }

    public ContextDataEntity save(final String key, final ContextData contextData) {
        final String finalKey = key == null ? UUID.randomUUID().toString() : key;
        contextEmitter.send(Record.of(finalKey, contextData)).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                Log.errorv("Unable to send context to kafka with key {0}: {1}", finalKey, contextData, throwable);
            } else {
                Log.infov("Context data updated on kafka with key {0}: {1}", finalKey, contextData);
            }
        });
        return ContextDataEntity.fromAvro(finalKey, contextData);
    }

    public List<ContextDataEntity> getContextObjects() {
        List<ContextDataEntity> list = new ArrayList<>();
        try (final KeyValueIterator<String, ContextData> iterator = getStore().all()) {
            iterator.forEachRemaining(kv -> list.add(ContextDataEntity.fromAvro(kv.key, kv.value)));
        }
        return list;
    }

    public List<ContextDataEntity> testContext(String testString) {
        List<CachedContextDataManager.CachedContextData> cachedContextData = cachedContextDataManager.getCachedContextData();
        Metric dummyTopicMetric = new Metric(EntityType.TOPIC, testString);
        Metric dummyPrincipalMetric = new Metric(EntityType.PRINCIPAL, testString);
        List<ContextDataEntity> matchedTopicList = findMatchedContextData(cachedContextData, dummyTopicMetric);
        List<ContextDataEntity> matchedPrincipalList = findMatchedContextData(cachedContextData, dummyPrincipalMetric);

        return Stream.concat(
                matchedTopicList.stream(),
                matchedPrincipalList.stream()
        ).toList();
    }

    private static List<ContextDataEntity> findMatchedContextData(List<CachedContextDataManager.CachedContextData> cachedContextData, Metric metricToTest) {
        return cachedContextData.stream()
                // the matcher is only returned if it matches (and contextData is in correct time range, i.e. valid for "Instant.now()")
                .filter(contextData -> contextData.getMatcher(metricToTest, Instant.now()).isPresent())
                .map(contextData -> ContextDataEntity.fromAvro(contextData.getKey(), contextData.getContextData()))
                .toList();
    }

    public ReadOnlyKeyValueStore<String, ContextData> getStore() {
        while (true) {
            try {
                return kafkaStreams.store(StoreQueryParameters.fromNameAndType(MetricEnricher.CONTEXT_DATA_TABLE_NAME, QueryableStoreTypes.keyValueStore()));
            } catch (InvalidStateStoreException e) {
                Log.warn("Store not ready yet", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
