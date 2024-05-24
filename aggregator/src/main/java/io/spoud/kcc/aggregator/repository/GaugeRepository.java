package io.spoud.kcc.aggregator.repository;

import com.google.common.util.concurrent.AtomicDouble;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Store all the metric values (gauges) observed while running the application. */
@RequiredArgsConstructor
@ApplicationScoped
public class GaugeRepository {
    private final Map<GaugeKey, AtomicDouble> gauges = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    public void updateGauge(String name, Tags tags, double value) {
        var key = new GaugeKey(name, tags);
        gauges.computeIfAbsent(key, g -> {
            var atomicDouble = new AtomicDouble(value);
            Gauge.builder(name, atomicDouble, AtomicDouble::get).tags(tags).register(meterRegistry);
            return atomicDouble;
        }).set(value);
    }

    public Map<GaugeKey, Double> getGaugeValues() {
        var result = new ConcurrentHashMap<GaugeKey, Double>();
        gauges.forEach((key, value) -> result.put(key, value.get()));
        return result;
    }

    public record GaugeKey(String name, Tags tags) {}
}
