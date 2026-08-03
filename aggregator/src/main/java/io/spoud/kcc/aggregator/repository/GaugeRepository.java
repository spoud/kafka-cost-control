package io.spoud.kcc.aggregator.repository;

import com.google.common.util.concurrent.AtomicDouble;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.spoud.kcc.aggregator.CostControlConfigProperties;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Store all the metric values (gauges) observed while running the application. */
@ApplicationScoped
public class GaugeRepository {
    private final Map<GaugeKey, GaugeInfo> gauges = new ConcurrentHashMap<>();
    // Prometheus requires every series registered under the same gauge name to share the same tag key set.
    // Since context-data rules can attach a different subset of keys to different topics/principals, we track
    // the widest key set seen so far per gauge name and pad every update to match it, growing it (and re-registering
    // already-known gauges under that name) whenever a new key shows up.
    private final Map<String, Set<String>> tagKeysByGaugeName = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;
    private final Duration gaugeTimeout;
    private Instant currentTime = Instant.MIN;

    public GaugeRepository(MeterRegistry meterRegistry, CostControlConfigProperties configProperties) {
        this.meterRegistry = meterRegistry;
        // If a gauge is not updated for more than the aggregation window size plus 30-second grace period, that metric is considered unavailable.
        this.gaugeTimeout = configProperties.aggregationWindowSize().plus(Duration.ofSeconds(30));
    }

    public synchronized void updateGauge(String name, Tags tags, double value, Instant eventTime) {
        updateCurrentTime(eventTime);
        var tagKeys = tagKeysByGaugeName.computeIfAbsent(name, n -> tagKeys(tags));
        if (!tagKeys.containsAll(tagKeys(tags))) {
            tagKeys = new LinkedHashSet<>(tagKeys);
            tagKeys.addAll(tagKeys(tags));
            tagKeysByGaugeName.put(name, tagKeys);
            widenExistingGauges(name, tagKeys);
        }
        var paddedTags = padTags(tags, tagKeys);
        var key = new GaugeKey(name, paddedTags);
        gauges.computeIfAbsent(key, g -> registerGauge(name, paddedTags, value, getCurrentTime().plus(gaugeTimeout)))
                .updateValue(value);
    }

    /** Re-registers every existing gauge under {@code name} so all of them share the new, wider tag key set. */
    private void widenExistingGauges(String name, Set<String> tagKeys) {
        gauges.entrySet().stream()
                .filter(entry -> entry.getKey().name().equals(name))
                .toList()
                .forEach(entry -> {
                    var oldKey = entry.getKey();
                    var newTags = padTags(oldKey.tags(), tagKeys);
                    if (newTags.equals(oldKey.tags())) {
                        return;
                    }
                    var info = entry.getValue();
                    meterRegistry.remove(info.getId());
                    gauges.remove(oldKey);
                    gauges.put(new GaugeKey(name, newTags), registerGauge(name, newTags, info.getGaugeValue().get(), info.getTimeout()));
                });
    }

    private GaugeInfo registerGauge(String name, Tags tags, double value, Instant timeout) {
        var atomicDouble = new AtomicDouble(value);
        var id = Gauge.builder(name, atomicDouble, AtomicDouble::get).tags(tags).register(meterRegistry).getId();
        return new GaugeInfo(id, atomicDouble, timeout);
    }

    private static Set<String> tagKeys(Tags tags) {
        var keys = new LinkedHashSet<String>();
        tags.forEach(tag -> keys.add(tag.getKey()));
        return keys;
    }

    private static Tags padTags(Tags tags, Set<String> keys) {
        var existingKeys = tagKeys(tags);
        var result = tags;
        for (var key : keys) {
            if (!existingKeys.contains(key)) {
                result = result.and(key, "");
            }
        }
        return result;
    }

    public Map<GaugeKey, Double> getGaugeValues() {
        var result = new ConcurrentHashMap<GaugeKey, Double>();
        gauges.forEach((key, value) -> result.put(key, value.gaugeValue.get()));
        return result;
    }

    @Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public synchronized void removeExpiredGauges() {
        var now = getCurrentTime();
        gauges.entrySet().removeIf(entry -> {
            if (entry.getValue().getTimeout().isBefore(now)) {
                Log.infof("Gauge '%s' with tags '%s' has expired and will be removed",
                        entry.getKey().name, entry.getKey().tags);
                meterRegistry.remove(entry.getValue().getId());
                return true;
            }
            return false;
        });
    }

    private Instant getCurrentTime() {
        return currentTime;
    }

    private void updateCurrentTime(Instant eventTime) {
        this.currentTime = eventTime.isAfter(currentTime) ? eventTime : currentTime;
    }

    public record GaugeKey(String name, Tags tags) {}

    @Data
    @AllArgsConstructor
    public class GaugeInfo {
        private final Meter.Id id;
        private final AtomicDouble gaugeValue;
        private Instant timeout;

        public void updateValue(double value) {
            gaugeValue.set(value);
            timeout = getCurrentTime().plus(gaugeTimeout);
        }
    }
}
