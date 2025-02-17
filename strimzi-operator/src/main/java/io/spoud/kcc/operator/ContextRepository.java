package io.spoud.kcc.operator;

import io.spoud.kcc.data.ContextData;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import io.smallrye.reactive.messaging.kafka.Record;

import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ContextRepository {
    private final ConcurrentHashMap<String, ContextData> contexts = new ConcurrentHashMap<>();

    @Incoming("context-data-in")
    void updateContext(Record<String, ContextData> record) {
        if (record.value() == null) {
            contexts.remove(record.key());
            return;
        }
        contexts.put(record.key(), record.value());
    }

    public ContextData getContext(String key) {
        return contexts.get(key);
    }

    public boolean containsContext(String key, ContextData value) {
        var ctx = contexts.get(key);
        return ctx != null && ctx.getContext().equals(value.getContext()) && ctx.getRegex().equals(value.getRegex());
    }

    void clear() {
        contexts.clear();
    }
}
