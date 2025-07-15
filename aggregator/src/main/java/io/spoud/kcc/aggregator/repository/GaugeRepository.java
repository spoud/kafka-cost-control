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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Store all the metric values (gauges) observed while running the application. */
@ApplicationScoped
public class GaugeRepository {
    private final Map<GaugeKey, AtomicDouble> gauges = new ConcurrentHashMap<>();
    private final Map<GaugeKey, Instant> gaugeTimeouts = new ConcurrentHashMap<>();
    private final Map<GaugeKey, Meter.Id> gaugeIds = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;
    private final Duration gaugeTimeout;
    private Instant currentTime = Instant.MIN;

    public GaugeRepository(MeterRegistry meterRegistry, CostControlConfigProperties configProperties) {
        this.meterRegistry = meterRegistry;
        // If a gauge is not updated for more than the aggregation window size plus 30-second grace period, that metric is considered unavailable.
        this.gaugeTimeout = Duration.parse(configProperties.aggregationWindowSize()).plus(Duration.ofSeconds(30));
    }

    public void updateGauge(String name, Tags tags, double value, Instant eventTime) {
        var key = new GaugeKey(name, tags);
        updateCurrentTime(eventTime);
        gaugeTimeouts.put(key, getCurrentTime().plus(gaugeTimeout));
        gauges.computeIfAbsent(key, g -> {
            var atomicDouble = new AtomicDouble(value);
            var id = Gauge.builder(name, atomicDouble, AtomicDouble::get).tags(tags).register(meterRegistry).getId();
            gaugeIds.put(key, id);
            return atomicDouble;
        }).set(value);
    }

    public Map<GaugeKey, Double> getGaugeValues() {
        var result = new ConcurrentHashMap<GaugeKey, Double>();
        gauges.forEach((key, value) -> result.put(key, value.get()));
        return result;
    }

    @Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void removeExpiredGauges() {
        var now = getCurrentTime();
        gaugeTimeouts.entrySet().removeIf(entry -> {
            if (entry.getValue().isBefore(now)) {
                Log.infof("Gauge '%s' with tags '%s' has expired and will be removed",
                        entry.getKey().name, entry.getKey().tags);
                gauges.remove(entry.getKey());
                var id = gaugeIds.remove(entry.getKey());
                if (id != null)
                    meterRegistry.remove(id);
                return true; // Remove the entry from gaugeTimeouts
            }
            return false; // Keep the entry
        });
    }

    private Instant getCurrentTime() {
        return currentTime;
    }

    private void updateCurrentTime(Instant eventTime) {
        this.currentTime = eventTime.isAfter(currentTime) ? eventTime : currentTime;
    }

    public record GaugeKey(String name, Tags tags) {}
}
