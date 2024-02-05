package io.spoud.kcc.aggregator.stream;

import io.spoud.kcc.aggregator.data.Metric;
import io.spoud.kcc.aggregator.data.RawTelegrafData;
import io.spoud.kcc.data.EntityType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class TelegrafDataWrapper {

    public static final String GAUGE_FIELD_NAME = "gauge";
    public static final String TOPIC_TAG = "topic";
    public static final String PRINCIPAL_ID_TAG = "principal_id";
    private final RawTelegrafData telegrafData;

    TelegrafDataWrapper(final RawTelegrafData telegrafData) {
        this.telegrafData = telegrafData;
    }

    public Optional<Metric> getEntityType() {
        Map<String, String> tags = telegrafData.tags();
        if (tags.containsKey(TOPIC_TAG)) {
            return Optional.of(new Metric(EntityType.TOPIC, tags.get(TOPIC_TAG)));
        } else if (tags.containsKey(PRINCIPAL_ID_TAG)) {
            return Optional.of(new Metric(EntityType.PRINCIPAL, tags.get(PRINCIPAL_ID_TAG)));
        }
        return Optional.empty();
    }

    public Optional<AggregatedDataInfo> enrichWithContext(List<CachedContextDataManager.CachedContextData> contextData) {
        return getEntityType().map(metric -> {
            Map<String, String> context = new HashMap<>();
            // This is the join with regex
            contextData.forEach(cache -> {
                if (cache.matches(metric, telegrafData.timestamp())) {
                    // TODO add to a list instead of overriding the context
                    context.putAll(cache.getContextData().getContext());
                }
            });
            return new AggregatedDataInfo(metric.type(), metric.objectName(), context);
        });
    }

    public double getValue() {
        // TODO improve this, we only support gauge for now
        return Double.parseDouble(String.valueOf(telegrafData.fields().get(GAUGE_FIELD_NAME)));
    }

    public record AggregatedDataInfo(EntityType type, String name, Map<String, String> context) {
    }
}
