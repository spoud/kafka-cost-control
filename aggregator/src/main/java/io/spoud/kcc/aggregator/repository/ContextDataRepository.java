package io.spoud.kcc.aggregator.repository;

import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.kafka.Record;
import io.spoud.kcc.aggregator.data.ContextDataEntity;
import io.spoud.kcc.aggregator.data.ContextTestResponse;
import io.spoud.kcc.aggregator.data.RawTelegrafData;
import io.spoud.kcc.aggregator.stream.CachedContextDataManager;
import io.spoud.kcc.aggregator.stream.MetricEnricher;
import io.spoud.kcc.aggregator.stream.TelegrafDataWrapper;
import io.spoud.kcc.data.ContextData;
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
import java.util.*;
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

    public List<ContextTestResponse> testContext(String testString) {
        List<CachedContextDataManager.CachedContextData> cachedContextData = cachedContextDataManager.getCachedContextData();

        RawTelegrafData dummyTopicData = new RawTelegrafData(Instant.now(), null, Map.of(), Map.of(TelegrafDataWrapper.TOPIC_TAG, testString));
        RawTelegrafData dummyPrincipalData = new RawTelegrafData(Instant.now(), null, Map.of(), Map.of(TelegrafDataWrapper.PRINCIPAL_ID_TAG, testString));
        Optional<TelegrafDataWrapper.AggregatedDataInfo> aggregatedForTopic = findMatchedContext(cachedContextData, dummyTopicData);
        Optional<TelegrafDataWrapper.AggregatedDataInfo> aggregatedForPrincipal = findMatchedContext(cachedContextData, dummyPrincipalData);

        return Stream.of(aggregatedForTopic, aggregatedForPrincipal)
                .flatMap(Optional::stream)
                .map(info -> new ContextTestResponse(info.type(), info.context()))
                .toList();
    }

    private static Optional<TelegrafDataWrapper.AggregatedDataInfo> findMatchedContext(List<CachedContextDataManager.CachedContextData> cachedContextData, RawTelegrafData dummyData) {
        TelegrafDataWrapper wrapper = new TelegrafDataWrapper(dummyData);
        // here we find matching regexes and replace capturing groups
        return wrapper.enrichWithContext(cachedContextData);
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
