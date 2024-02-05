package io.spoud.kcc.aggregator.repository;

import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.kafka.Record;
import io.spoud.kcc.aggregator.data.ContextDataEntity;
import io.spoud.kcc.aggregator.stream.MetricEnricher;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ContextDataRepository {


    private final Emitter<Record<String, ContextData>> contextEmitter;
    private final KafkaStreams kafkaStreams;

    public ContextDataRepository(
            @Channel("context-data-out") Emitter<Record<String, ContextData>> contextEmitter,
            KafkaStreams kafkaStreams
    ) {
        this.contextEmitter = contextEmitter;
        this.kafkaStreams = kafkaStreams;
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

    public <T> ReadOnlyKeyValueStore<String, ContextData> getStore() {
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
