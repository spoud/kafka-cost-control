package io.spoud.kcc.aggregator.stream.weighting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Exhaustive, Kafka-free tests for the fair-split core. Each test feeds plain dummy data and asserts the exact
 * allocation, so the billing logic is pinned down independently of the streaming plumbing.
 */
class ConsumptionWeightSplitterTest {

    private static final String FALLBACK = "unknown";
    private final ConsumptionWeightSplitter splitter = new ConsumptionWeightSplitter();

    private static ConsumptionWeightSplitter.Weight w(double bytes) {
        return new ConsumptionWeightSplitter.Weight(bytes, WeightTier.T1_CLIENT_TELEMETRY);
    }

    private void assertConserved(ConsumptionWeightSplitter.Result result, double total) {
        double sum = result.allocations().values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(sum).as("allocations must sum back to the authoritative total").isCloseTo(total, within(1e-6));
    }

    // ---- full telemetry coverage (the 5-year steady state) ----

    @Test
    @DisplayName("the motivating 99/1 case: heavy consumer pays ~99%, not 50%")
    void heavy_consumer_pays_its_share() {
        var result = splitter.split(1000.0,
                Map.of("heavy", w(990.0), "light", w(10.0)),
                List.of("heavy", "light"),
                ImputationMode.MEAN_REPORTER, FALLBACK);

        assertThat(result.allocations().get("heavy")).isCloseTo(990.0, within(1e-6));
        assertThat(result.allocations().get("light")).isCloseTo(10.0, within(1e-6));
        assertThat(result.coverage()).isEqualTo(1.0);
        assertConserved(result, 1000.0);
    }

    @Test
    @DisplayName("equal usage splits evenly, same as the legacy behaviour")
    void equal_usage_splits_evenly() {
        var result = splitter.split(1000.0,
                Map.of("a", w(500.0), "b", w(500.0)),
                List.of("a", "b"),
                ImputationMode.MEAN_REPORTER, FALLBACK);

        assertThat(result.allocations()).containsOnlyKeys("a", "b");
        assertThat(result.allocations().get("a")).isCloseTo(500.0, within(1e-6));
        assertThat(result.allocations().get("b")).isCloseTo(500.0, within(1e-6));
        assertConserved(result, 1000.0);
    }

    @Test
    @DisplayName("a reporter absent from the roster is still billed (union of reporters and roster)")
    void reporter_not_in_roster_is_kept() {
        var result = splitter.split(300.0,
                Map.of("ghost", w(200.0), "known", w(100.0)),
                List.of("known"), // roster is incomplete, "ghost" only known via telemetry
                ImputationMode.MEAN_REPORTER, FALLBACK);

        assertThat(result.allocations().get("ghost")).isCloseTo(200.0, within(1e-6));
        assertThat(result.allocations().get("known")).isCloseTo(100.0, within(1e-6));
        assertConserved(result, 300.0);
    }

    // ---- partial coverage during the telemetry-adoption transition ----

    @Test
    @DisplayName("non-reporters are imputed the mean reporter intensity")
    void mean_imputation_for_non_reporters() {
        // reporters: a=800, b=100 (S=900, mean=450); one non-reporter c → imputed 450; W=1350
        var result = splitter.split(1000.0,
                Map.of("a", w(800.0), "b", w(100.0)),
                List.of("a", "b", "c"),
                ImputationMode.MEAN_REPORTER, FALLBACK);

        assertThat(result.allocations().get("a")).isCloseTo(1000.0 * 800 / 1350, within(1e-6));
        assertThat(result.allocations().get("b")).isCloseTo(1000.0 * 100 / 1350, within(1e-6));
        assertThat(result.allocations().get("c")).isCloseTo(1000.0 * 450 / 1350, within(1e-6));
        assertThat(result.coverage()).isCloseTo(900.0 / 1350.0, within(1e-6));
        assertConserved(result, 1000.0);
    }

    @Test
    @DisplayName("with a single reporter we cannot tell the non-reporter is smaller, so it degrades to even")
    void single_reporter_cannot_distinguish() {
        var result = splitter.split(1000.0,
                Map.of("a", w(990.0)),
                List.of("a", "b"),
                ImputationMode.MEAN_REPORTER, FALLBACK);

        // honest outcome: 50/50, but coverage reports only 50% confidence
        assertThat(result.allocations().get("a")).isCloseTo(500.0, within(1e-6));
        assertThat(result.allocations().get("b")).isCloseTo(500.0, within(1e-6));
        assertThat(result.coverage()).isCloseTo(0.5, within(1e-6));
        assertConserved(result, 1000.0);
    }

    @Test
    @DisplayName("ZERO mode: non-reporters ride free, reporters absorb the whole total")
    void zero_mode_gives_non_reporters_nothing() {
        var result = splitter.split(1000.0,
                Map.of("a", w(800.0), "b", w(200.0)),
                List.of("a", "b", "c"),
                ImputationMode.ZERO, FALLBACK);

        assertThat(result.allocations()).doesNotContainKey("c");
        assertThat(result.allocations().get("a")).isCloseTo(800.0, within(1e-6));
        assertThat(result.allocations().get("b")).isCloseTo(200.0, within(1e-6));
        // coverage is still honest about the un-measured consumer
        assertThat(result.coverage()).isCloseTo(1000.0 / 1500.0, within(1e-6));
        assertConserved(result, 1000.0);
    }

    @Test
    @DisplayName("FALLBACK_PRINCIPAL mode: the non-reporter residual is concentrated on one bucket")
    void fallback_mode_pools_residual() {
        // a=800,b=100 (S=900,mean=450); non-reporters c,d → imputed 450 each; W=900+900=1800
        var result = splitter.split(1000.0,
                Map.of("a", w(800.0), "b", w(100.0)),
                List.of("a", "b", "c", "d"),
                ImputationMode.FALLBACK_PRINCIPAL, FALLBACK);

        assertThat(result.allocations()).doesNotContainKeys("c", "d");
        assertThat(result.allocations().get("a")).isCloseTo(1000.0 * 800 / 1800, within(1e-6));
        assertThat(result.allocations().get("b")).isCloseTo(1000.0 * 100 / 1800, within(1e-6));
        // c and d residual (2 * 450 imputed) collapsed onto the fallback principal
        assertThat(result.allocations().get(FALLBACK)).isCloseTo(1000.0 * 900 / 1800, within(1e-6));
        assertConserved(result, 1000.0);
    }

    // ---- zero coverage falls back to the legacy behaviour ----

    @Test
    @DisplayName("no reporters → even split across the roster")
    void even_split_when_nobody_reports() {
        var result = splitter.split(900.0,
                Map.of(),
                List.of("a", "b", "c"),
                ImputationMode.MEAN_REPORTER, FALLBACK);

        assertThat(result.allocations()).containsOnlyKeys("a", "b", "c");
        assertThat(result.allocations().values()).allMatch(v -> Math.abs(v - 300.0) < 1e-6);
        assertThat(result.coverage()).isEqualTo(0.0);
        assertConserved(result, 900.0);
    }

    @Test
    @DisplayName("no reporters and no roster → everything to the fallback principal")
    void all_to_fallback_when_nothing_known() {
        var result = splitter.split(900.0, Map.of(), List.of(),
                ImputationMode.MEAN_REPORTER, FALLBACK);

        assertThat(result.allocations()).containsExactly(Map.entry(FALLBACK, 900.0));
        assertConserved(result, 900.0);
    }

    @Test
    @DisplayName("reporters that all reported zero are treated as no signal")
    void all_zero_reporters_fall_back_to_even() {
        var result = splitter.split(600.0,
                Map.of("a", w(0.0), "b", w(0.0)),
                List.of("a", "b"),
                ImputationMode.MEAN_REPORTER, FALLBACK);

        assertThat(result.allocations().get("a")).isCloseTo(300.0, within(1e-6));
        assertThat(result.allocations().get("b")).isCloseTo(300.0, within(1e-6));
        assertConserved(result, 600.0);
    }

    // ---- robustness / edge cases ----

    @Test
    @DisplayName("non-positive total produces no allocations")
    void zero_total_is_empty() {
        assertThat(splitter.split(0.0, Map.of("a", w(1.0)), List.of("a"),
                ImputationMode.MEAN_REPORTER, FALLBACK).allocations()).isEmpty();
        assertThat(splitter.split(-5.0, Map.of("a", w(1.0)), List.of("a"),
                ImputationMode.MEAN_REPORTER, FALLBACK).allocations()).isEmpty();
    }

    @Test
    @DisplayName("negative reporter weights are clamped to zero")
    void negative_weight_clamped() {
        var result = splitter.split(100.0,
                Map.of("a", w(-50.0), "b", w(100.0)),
                List.of("a", "b"),
                ImputationMode.MEAN_REPORTER, FALLBACK);

        assertThat(result.allocations().get("a")).isCloseTo(0.0, within(1e-6));
        assertThat(result.allocations().get("b")).isCloseTo(100.0, within(1e-6));
        assertConserved(result, 100.0);
    }

    @Test
    @DisplayName("null roster is treated as no roster (reporters absorb everything)")
    void null_roster_is_safe() {
        var result = splitter.split(200.0,
                Map.of("a", w(150.0), "b", w(50.0)),
                null,
                ImputationMode.MEAN_REPORTER, FALLBACK);

        assertThat(result.allocations().get("a")).isCloseTo(150.0, within(1e-6));
        assertThat(result.allocations().get("b")).isCloseTo(50.0, within(1e-6));
        assertConserved(result, 200.0);
    }
}
