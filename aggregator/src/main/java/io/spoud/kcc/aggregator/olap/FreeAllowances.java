package io.spoud.kcc.aggregator.olap;

import java.util.Map;

/**
 * The pricing rules that make part of each window free (e.g. 10 partitions per cluster), by metric
 * name. Kept behind an interface so the OLAP repository doesn't depend on Kafka Streams.
 */
@FunctionalInterface
public interface FreeAllowances {

    /**
     * @param baseCost   the rule's base cost per row
     * @param costFactor the rule's cost per unit of the raw value
     * @param freeRaw    the free amount per window, in units of the raw value
     */
    record Allowance(double baseCost, double costFactor, double freeRaw) {
    }

    /** Rules with a free amount, keyed by metric name; empty when there are none. */
    Map<String, Allowance> current();

    FreeAllowances NONE = Map::of;
}
