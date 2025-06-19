package io.spoud.kcc.aggregator.stream;

import io.quarkus.logging.Log;
import io.spoud.kcc.aggregator.data.Metric;
import io.spoud.kcc.aggregator.data.RawTelegrafData;
import io.spoud.kcc.aggregator.repository.ContextDataRepository;
import io.spoud.kcc.data.EntityType;
import org.apache.commons.lang3.math.NumberUtils;
import java.time.Instant;

import java.util.Map;
import java.util.Optional;

public class TelegrafDataWrapper {

    public static final String GAUGE_FIELD_NAME = "gauge";
    public static final String COUNTER_FIELD_NAME = "counter";
    public static final String TOPIC_TAG = "topic";
    public static final String PRINCIPAL_ID_TAG = "principal_id";
    private final RawTelegrafData telegrafData;

    public TelegrafDataWrapper(final RawTelegrafData telegrafData) {
        this.telegrafData = telegrafData;
    }

    /**
     * Get the timestamp of the telegraf data.
     *
     * @return the timestamp
     */
    public Instant getTimestamp() {
        return telegrafData.timestamp();
    }

    public Optional<Metric> toMetric() {
        Map<String, String> tags = telegrafData.tags();
        if (tags.containsKey(TOPIC_TAG)) {
            return Optional.of(new Metric(EntityType.TOPIC, tags.get(TOPIC_TAG)));
        } else if (tags.containsKey(PRINCIPAL_ID_TAG)) {
            return Optional.of(new Metric(EntityType.PRINCIPAL, tags.get(PRINCIPAL_ID_TAG)));
        }
        return Optional.empty();
    }


    /**
     * Get the value of the metric. If the metric is a gauge, the gauge value is returned. If the metric is a counter, the
     * counter value is returned. If the metric is of another type (neither "gauge" nor "counter" keys are present among
     * the fields), the first key (in alphabetical order) is returned. If no keys are present, 0 is returned.
     * In the latter case, a warning is logged.
     *
     * @return the value of the metric
     */
    public double getValue() {
        return getFirstPresentValue(GAUGE_FIELD_NAME, COUNTER_FIELD_NAME);
    }

    private double getFirstPresentValue(String... keys) {
        for (String key : keys) {
            if (telegrafData.fields().containsKey(key)) {
                return NumberUtils.toDouble(String.valueOf(telegrafData.fields().get(key)));
            }
        }
        // fallback to the first key in the map or 0 if no keys are present
        return telegrafData
                .fields()
                .entrySet()
                .stream()
                .min(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .map(String::valueOf)
                .map(NumberUtils::toDouble)
                .orElseGet(() -> {
                    Log.warnv("Cannot read value of metric \"{0}\". The fields are empty", telegrafData.name());
                    return 0.0;
                });
    }

    public record AggregatedDataInfo(EntityType type, String name, Map<String, String> context) {
    }
}
