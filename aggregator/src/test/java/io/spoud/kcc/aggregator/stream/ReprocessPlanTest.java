package io.spoud.kcc.aggregator.stream;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReprocessPlanTest {

    private static final Duration HOUR = Duration.ofHours(1);

    private static Instant at(String time) {
        return Instant.parse(time);
    }

    @Test
    void alignsToWindowBoundaries() {
        assertThat(ReprocessPlan.alignDown(at("2026-10-01T15:12:30Z"), HOUR)).isEqualTo(at("2026-10-01T15:00:00Z"));
        assertThat(ReprocessPlan.alignUp(at("2026-10-01T15:12:30Z"), HOUR)).isEqualTo(at("2026-10-01T16:00:00Z"));
        assertThat(ReprocessPlan.alignUp(at("2026-10-01T15:00:00Z"), HOUR)).isEqualTo(at("2026-10-01T15:00:00Z"));
    }

    @Test
    void startsAtTheRequestedWindowWhenTheRawTopicsGoBackFurther() {
        var start = ReprocessPlan.effectiveStart(at("2026-10-01T15:12:00Z"), HOUR, List.of(at("2026-09-29T12:57:00Z")));

        assertThat(start).isEqualTo(at("2026-10-01T15:00:00Z"));
    }

    @Test
    void neverStartsBeforeTheFirstCompleteWindowTheRawTopicsHold() {
        // retention removed everything before 12:57, so the 12:00 window can't be rebuilt complete
        var everything = ReprocessPlan.effectiveStart(null, HOUR, List.of(at("2026-09-29T12:57:00Z")));
        var tooEarly = ReprocessPlan.effectiveStart(at("2026-09-01T00:00:00Z"), HOUR, List.of(at("2026-09-29T12:57:00Z")));

        assertThat(everything).isEqualTo(at("2026-09-29T13:00:00Z"));
        assertThat(tooEarly).isEqualTo(at("2026-09-29T13:00:00Z"));
    }

    @Test
    void theLatestStartingPartitionDecides() {
        var start = ReprocessPlan.effectiveStart(null, HOUR,
                List.of(at("2026-09-29T12:57:00Z"), at("2026-09-30T08:20:00Z")));

        assertThat(start).isEqualTo(at("2026-09-30T09:00:00Z"));
    }

    @Test
    void emptyRawTopicsLeaveTheRequestedStart() {
        assertThat(ReprocessPlan.effectiveStart(at("2026-10-01T15:12:00Z"), HOUR, List.of()))
                .isEqualTo(at("2026-10-01T15:00:00Z"));
    }

    @Test
    void picksOnlyThisApplicationsInternalTopics() {
        var topics = List.of(
                "kafka-cost-control-KSTREAM-REDUCE-STATE-STORE-0000000010-changelog",
                "kafka-cost-control-group-by-key-repartition",
                "kafka-cost-control-pricing-rules-store-changelog",
                "metrics-raw-telegraf-confluent-demo",
                "pricing-rules",
                "aggregated",
                "other-app-something-changelog",
                "kafka-cost-control-connect-configs");

        assertThat(ReprocessPlan.internalTopics("kafka-cost-control", topics)).containsExactly(
                "kafka-cost-control-KSTREAM-REDUCE-STATE-STORE-0000000010-changelog",
                "kafka-cost-control-group-by-key-repartition",
                "kafka-cost-control-pricing-rules-store-changelog");
    }
}
