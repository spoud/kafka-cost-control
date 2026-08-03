package io.spoud.kcc.aggregator.repository;

import io.micrometer.core.instrument.Tags;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.spoud.kcc.aggregator.CostControlConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class GaugeRepositoryTest {

    private PrometheusMeterRegistry meterRegistry;
    private GaugeRepository gaugeRepository;

    @BeforeEach
    void setup() {
        meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var configProperties = Mockito.mock(CostControlConfigProperties.class);
        when(configProperties.aggregationWindowSize()).thenReturn(Duration.ofHours(1));
        gaugeRepository = new GaugeRepository(meterRegistry, configProperties);
    }

    @Test
    void updatingGaugeWithFewerTagsThanAnExistingSeriesDoesNotThrow() {
        gaugeRepository.updateGauge("kcc_test_metric", Tags.of("topic", "topicA", "app", "app1"), 10.0, Instant.now());

        // topicB has no matching context-data rule for "app", so it reports fewer tag keys than topicA.
        // Without widening, Micrometer's PrometheusMeterRegistry would throw IllegalArgumentException here.
        gaugeRepository.updateGauge("kcc_test_metric", Tags.of("topic", "topicB"), 20.0, Instant.now());

        var gauges = meterRegistry.find("kcc_test_metric").gauges();
        assertThat(gauges).hasSize(2);

        var topicAValue = meterRegistry.find("kcc_test_metric").tag("topic", "topicA").gauge();
        var topicBValue = meterRegistry.find("kcc_test_metric").tag("topic", "topicB").gauge();
        assertThat(topicAValue).isNotNull();
        assertThat(topicBValue).isNotNull();
        assertThat(topicAValue.value()).isEqualTo(10.0);
        assertThat(topicBValue.value()).isEqualTo(20.0);
        // topicB is padded with an empty "app" tag so it shares topicA's key set.
        assertThat(topicBValue.getId().getTag("app")).isEqualTo("");
    }

    @Test
    void updatingGaugeWithANewTagKeyWidensAlreadyRegisteredSeries() {
        gaugeRepository.updateGauge("kcc_test_metric", Tags.of("topic", "topicA"), 10.0, Instant.now());

        // topicC introduces a brand-new key ("domain") that topicA was never registered with.
        gaugeRepository.updateGauge("kcc_test_metric", Tags.of("topic", "topicC", "domain", "sales"), 30.0, Instant.now());

        var topicAGauge = meterRegistry.find("kcc_test_metric").tag("topic", "topicA").gauge();
        var topicCGauge = meterRegistry.find("kcc_test_metric").tag("topic", "topicC").gauge();
        assertThat(topicAGauge).isNotNull();
        assertThat(topicCGauge).isNotNull();
        // topicA's value survives being re-registered under the widened tag set.
        assertThat(topicAGauge.value()).isEqualTo(10.0);
        assertThat(topicAGauge.getId().getTag("domain")).isEqualTo("");
        assertThat(topicCGauge.value()).isEqualTo(30.0);

        // No stale/orphaned meters left behind from the re-registration.
        assertThat(meterRegistry.find("kcc_test_metric").gauges()).hasSize(2);
    }

    @Test
    void updatingSameTagsTwiceUpdatesValueInPlace() {
        gaugeRepository.updateGauge("kcc_test_metric", Tags.of("topic", "topicA"), 10.0, Instant.now());
        gaugeRepository.updateGauge("kcc_test_metric", Tags.of("topic", "topicA"), 15.0, Instant.now());

        assertThat(meterRegistry.find("kcc_test_metric").gauges()).hasSize(1);
        assertThat(meterRegistry.find("kcc_test_metric").gauge().value()).isEqualTo(15.0);
    }
}
