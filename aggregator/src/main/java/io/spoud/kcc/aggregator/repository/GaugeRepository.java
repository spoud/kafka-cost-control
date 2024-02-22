package io.spoud.kcc.aggregator.repository;

import com.google.common.util.concurrent.AtomicDouble;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/** Store all the metric values (gauges) observed while running the application. */
@RequiredArgsConstructor
@ApplicationScoped
public class GaugeRepository {
    private final Map<GaugeKey, AtomicDouble> gauges = new HashMap<>();
    private final MeterRegistry meterRegistry;

    public void updateGauge(String name, Tags tags, double value) {
        var key = new GaugeKey(name, tags);
        gauges.computeIfAbsent(key, g -> {
            var atomicDouble = new AtomicDouble(value);
            Gauge.builder(name, atomicDouble, AtomicDouble::get).tags(tags).register(meterRegistry);
            return atomicDouble;
        }).set(value);
    }

    private record GaugeKey(String name, Tags tags) {}
}
