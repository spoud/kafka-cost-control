package io.spoud.kcc.aggregator.stream.weighting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies the tier-precedence de-duplication that makes the cross-platform signal waterfall safe: a principal
 * measured by several providers in the same window must be counted once, at its best-quality tier.
 */
class TopicWeightAccumulatorTest {

    @Test
    @DisplayName("topic totals accumulate")
    void totals_sum() {
        var acc = new TopicWeightAccumulator();
        acc.add(WeightInput.forTotal(100.0));
        acc.add(WeightInput.forTotal(50.0));
        assertThat(acc.total).isCloseTo(150.0, within(1e-9));
        assertThat(acc.weights).isEmpty();
    }

    @Test
    @DisplayName("same-tier samples for a principal add up")
    void same_tier_sums() {
        var acc = new TopicWeightAccumulator();
        acc.add(WeightInput.forWeight("alice", 10.0, WeightTier.T2_OFFSET_PROGRESS));
        acc.add(WeightInput.forWeight("alice", 5.0, WeightTier.T2_OFFSET_PROGRESS));
        assertThat(acc.weights.get("alice").bytes).isCloseTo(15.0, within(1e-9));
        assertThat(acc.weights.get("alice").tier).isEqualTo(WeightTier.T2_OFFSET_PROGRESS);
    }

    @Test
    @DisplayName("a higher tier supersedes a lower one, regardless of arrival order")
    void higher_tier_wins() {
        var lowFirst = new TopicWeightAccumulator();
        lowFirst.add(WeightInput.forWeight("bob", 999.0, WeightTier.T3_PRINCIPAL_TOTAL));
        lowFirst.add(WeightInput.forWeight("bob", 42.0, WeightTier.T1_CLIENT_TELEMETRY));
        assertThat(lowFirst.weights.get("bob").bytes).isCloseTo(42.0, within(1e-9));
        assertThat(lowFirst.weights.get("bob").tier).isEqualTo(WeightTier.T1_CLIENT_TELEMETRY);

        var highFirst = new TopicWeightAccumulator();
        highFirst.add(WeightInput.forWeight("bob", 42.0, WeightTier.T1_CLIENT_TELEMETRY));
        highFirst.add(WeightInput.forWeight("bob", 999.0, WeightTier.T3_PRINCIPAL_TOTAL));
        assertThat(highFirst.weights.get("bob").bytes).isCloseTo(42.0, within(1e-9));
        assertThat(highFirst.weights.get("bob").tier).isEqualTo(WeightTier.T1_CLIENT_TELEMETRY);
    }

    @Test
    @DisplayName("tier parsing accepts short codes and full names")
    void tier_parsing() {
        assertThat(WeightTier.fromString("T1", WeightTier.T2_OFFSET_PROGRESS)).isEqualTo(WeightTier.T1_CLIENT_TELEMETRY);
        assertThat(WeightTier.fromString("t3", WeightTier.T2_OFFSET_PROGRESS)).isEqualTo(WeightTier.T3_PRINCIPAL_TOTAL);
        assertThat(WeightTier.fromString("T1_CLIENT_TELEMETRY", WeightTier.T2_OFFSET_PROGRESS)).isEqualTo(WeightTier.T1_CLIENT_TELEMETRY);
        assertThat(WeightTier.fromString(null, WeightTier.T2_OFFSET_PROGRESS)).isEqualTo(WeightTier.T2_OFFSET_PROGRESS);
        assertThat(WeightTier.fromString("garbage", WeightTier.T2_OFFSET_PROGRESS)).isEqualTo(WeightTier.T2_OFFSET_PROGRESS);
    }
}
