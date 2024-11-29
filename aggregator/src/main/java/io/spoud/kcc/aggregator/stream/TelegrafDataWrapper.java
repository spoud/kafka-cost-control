package io.spoud.kcc.aggregator.stream;

import io.quarkus.logging.Log;
import io.spoud.kcc.aggregator.data.Metric;
import io.spoud.kcc.aggregator.data.RawTelegrafData;
import io.spoud.kcc.data.EntityType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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

    /**
     * Enrich the current data with context information from the supplied context data. Only context data whose regex
     * matches this resource will be used. The supplied context data is expected to be sorted by creation time.
     * If it is not sorted, it is not guaranteed that the newest context data will be used in case of conflicts.
     *
     * @param contextData the context data from which to pick contexts that the telegraf data should be enriched with
     * @return context-enriched data
     */
    public Optional<AggregatedDataInfo> enrichWithContext(List<CachedContextDataManager.CachedContextData> contextData) {
        return getEntityType().map(metric -> {
            Map<String, String> context = new HashMap<>();
            // This is the join with regex
            contextData.forEach(cachedContext -> {
                cachedContext.getMatcher(metric, telegrafData.timestamp())
                        .ifPresent(matcher -> {
                            // TODO add to a list instead of overriding the context
                            context.putAll(cachedContext.getContextData().getContext().entrySet().stream()
                                    // replace all the regex variable in the value
                                    .map(entry -> {
                                        try {
                                            return Map.entry(entry.getKey(), matcher.replaceAll(entry.getValue()));
                                        } catch (IndexOutOfBoundsException ex) {
                                            Log.warnv(ex, "Unable to replace regex variable for the entry \"{0}\" with the regex \"{1}\" and the context \"{2}={3}\"",
                                                    metric.objectName(), cachedContext.getContextData().getRegex(), entry.getKey(), entry.getValue());
                                            return entry;
                                        }
                                    })
                                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (l, r) -> r)));
                        });
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
